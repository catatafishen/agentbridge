# Screen Tearing Bug — JCEF OSR

**Status**: Fix 7 applied — boost JCEF OSR frame rate during manual scroll and remove the agent tooltip overlay
**Scope**: JCEF Off-Screen Rendering (OSR) mode in the chat panel  
**Affected area**: `ChatConsolePanel.kt`, `ChatContainer.ts`, `ChatController.ts`, `MessageBubble.ts`, `chat.css`

---

## Problem Description

During streaming (agent response output), the JCEF chat panel exhibits visual tearing, flickering,
or stale-frame artifacts. Content appears to "jump" or "flash" as new text arrives. The issue is
specific to JCEF's Off-Screen Rendering mode where Chromium renders to an off-screen buffer that
Swing composites into the panel — any desync between DOM updates, scroll changes, and buffer
refresh causes visible tearing.

The bug is **recurring** because any code change that increases DOM mutation frequency, adds
synchronous layout-forcing operations, or disrupts the rendering pipeline timing can re-trigger it.

---

## Root Cause Analysis

JCEF OSR tearing happens when:

1. **DOM mutations trigger synchronous forced layouts** — writing `scrollTop` forces the browser to
   compute layout immediately (a "forced reflow"). During streaming, if this happens multiple times
   per frame, the compositor can't keep up with the buffer refreshes.

2. **MutationObserver cascades** — one mutation triggers an observer that creates new DOM nodes,
   which triggers the same or another observer, creating a feedback loop within a single frame.

3. **Frame rate mismatch** — if the CEF frame rate is too low relative to the DOM update rate,
   completed frames get skipped and the user sees stale content.

4. **CSS smooth scroll conflicts** — CSS `scroll-behavior: smooth` causes the browser to animate
   scroll position over time, conflicting with rapid programmatic `scrollTop` changes during
   streaming.

5. **Scroll-time CSS virtualization** — `content-visibility: auto` and paint containment delay
   rendering work until elements enter the viewport. In JCEF OSR, that deferred paint can show as
   stale rows or tearing while the Swing host composites Chromium's off-screen buffer during scroll.

6. **Idle OSR frame rate during manual scroll** — JCEF OSR only repaints the off-screen browser buffer
   at the configured windowless frame rate. Keeping the panel at the idle 30fps cap during a user
   scroll can make Swing composite stale Chromium frames on 60Hz+ displays.

7. **Hover overlays during scroll** — when the pointer stays over the chat pane while rows scroll
   underneath it, `:hover` selectors can create and destroy extra painted layers during the active
   scroll frame. The old `chat-message[data-agent]:hover::before` client-name tooltip was especially
   expensive because it positioned a transformed overlay above every restored agent row.

---

## Architecture: Rendering Pipeline During Streaming

### Lifecycle

```
startStreaming()                               finishResponse()
     │                                              │
     ├── setFrameRate(60)                           ├── setFrameRate(30)
     └── setStreaming(true, false)  [disable        └── restore smooth-scroll preference
            smooth + arms streaming flag]
```

> **Note**: `repaintTimer.start()/stop()` was removed in **Fix 4** — there is no longer a
> periodic forced OSR invalidation. CEF's natural `OnPaint` cycle (capped at the windowless
> frame rate) handles repaints. The `streaming` flag is still maintained because
> `MonitorSwitchRecovery` uses it to defer DOM replay until streaming ends.

### Per-token flow

```
Kotlin appendText()
  └── executeJs("ChatController.appendAgentText(...)")
        └── JS: bubble.appendStreamingText(text)    ← accumulates text, schedules rAF
              └── rAF: renderMarkdown()             ← innerHTML replacement
                    └── MutationObserver fires       ← observes childList + subtree
                          └── rAF: scrollIfNeeded() ← writes scrollTop (debounced)
```

### Key invariants

- **One scroll write per rAF** — the `_scrollRAF` gate in `ChatContainer` ensures only one
  `scrollIfNeeded()` runs per animation frame, regardless of how many mutations occurred.
- **No smooth scroll during streaming** — `setStreaming(true, false)` disables CSS smooth scroll.
- **Programmatic bottom-lock is always instant** — `scrollIfNeeded()`, `forceScroll()`,
  `compensateScroll()`, and the `autoScroll` setter all go through `_scrollToInstant()` even when
  smooth scrolling is enabled.
- **CEF invalidation removed** — Fix 4 removed both the periodic `repaintTimer` and the
  per-`executeJs` `cef.invalidate()` calls. CEF's native `OnPaint` cycle (capped at the
  windowless frame rate) handles repaints. Do not reintroduce manual invalidation —
  see Fix 4 for the rationale.
- **No code block decoration during streaming** — `_setupCodeBlocks()` skips `<pre>` elements
  inside `message-bubble[streaming]` to avoid DOM churn.
- **No CSS scroll virtualization in OSR** — chat rows are painted normally; `content-visibility`,
  `contain-intrinsic-size`, and paint containment are avoided because they defer viewport paint work
  into active scroll frames.
- **Manual scroll gets streaming-rate OSR repaint cadence** — `ChatContainer` notifies Kotlin when
  scrolling starts/ends so `ChatConsolePanel` temporarily raises `setWindowlessFrameRate()` from the
  idle 30fps cap to 60fps during active scroll gestures.
- **No hover-generated overlays while scrolling** — `ChatContainer` applies `is-scrolling` during
  active scroll gestures so message descendants stop receiving pointer hover/click hit-tests until
  scrolling is idle again.

---

## Fix History

### Fix 1 — Original fix (`147c74af`)

**4 compounding issues addressed:**

1. **ResizeObserver synchronous scrollTop** — `ResizeObserver` callback was writing
   `scrollTop = scrollHeight` synchronously on every resize event. Fixed by debouncing through
   a shared `_scrollRAF` rAF gate (coalesce to 1 update per frame).

2. **CSS smooth scroll conflict** — `scroll-behavior: smooth` was active during streaming,
   causing animated scroll to fight with programmatic `scrollTop` changes. Fixed by disabling
   smooth scroll during streaming via `setStreaming(true, false)`.

3. **Low idle frame rate** — `IDLE_FRAME_RATE` was 10fps (100ms frame time), causing stale-frame
   tearing during manual scroll between streaming bursts. Raised to 30fps (33ms).

4. **No forced repaint** — No CEF invalidation safety net. Added `repaintTimer` that calls
   `cef.invalidate()` every 200ms during streaming to force OSR buffer refresh.

### Fix 2 — Autoscroll stutter fix (`f8eb82f5`)

Added `_scrollToInstant()` so programmatic bottom-locking can temporarily set
`scroll-behavior: auto`, perform the scroll, then restore the previous CSS setting. This now backs
`scrollIfNeeded()`, `forceScroll()`, and `compensateScroll()`, preventing stutter loops when smooth
scroll is re-enabled after streaming. During streaming this is a noop since behavior is already
`'auto'`.

### Fix 3 — DOM churn + invalidation throttle (this commit)

**3 issues addressed:**

1. **`_setupCodeBlocks()` mutation loop** (critical) — The `_copyObs` MutationObserver called
   `_setupCodeBlocks()` which processed `<pre>` elements inside streaming bubbles. The selector
   `pre:not(.streaming)` checked for a `.streaming` CSS class on `<pre>`, but the streaming
   attribute is on `<message-bubble>`, not `<pre>`. So during streaming:
    - rAF renders markdown → creates `<pre>` elements
    - `_copyObs` fires → `_setupCodeBlocks()` adds copy/wrap/scratch buttons
    - Next token → `renderMarkdown()` replaces innerHTML → destroys buttons
    - `_copyObs` fires again → re-adds buttons
    - This mutation loop created continuous DOM churn and layout thrashing.

   **Fix**: Changed selector to skip any `<pre>` inside `message-bubble[streaming]` using
   `pre.closest('message-bubble[streaming]')`. Buttons are only added after `finalize()`.

2. **Redundant synchronous scroll in `appendAgentText()`** — `ChatController.appendAgentText()`
   called `this._container()?.scrollIfNeeded()` synchronously after `appendStreamingText()`.
   But `appendStreamingText()` only schedules a rAF — the text hasn't rendered yet, so
   `scrollHeight` is stale. The `MutationObserver` + `ResizeObserver` on `ChatContainer` already
   handle post-render scrolling. The synchronous call was just wasted layout work.

   **Fix**: Removed the synchronous `scrollIfNeeded()` call from `appendAgentText()`.

3. **200ms repaint timer gaps** — With 59+ `executeJs` call sites (tool chips, sub-agents, turn
   stats, nudges, queued messages), JS executions can bunch between 200ms timer ticks, leaving
   DOM changes without a forced CEF repaint for up to 200ms.

   **Fix**: Added throttled per-`executeJs` `cef.invalidate()` during streaming (50ms throttle
   window). The repaintTimer remains as a 200ms safety net; the per-executeJs invalidation catches
   rapid bursts of JS updates.

### Fix 4 — Remove forced OSR invalidation

**Hypothesis revisited.** A new round of bug reports on Windows and Linux confirmed
tearing/flicker still occurred during streaming despite Fixes 1–3. The user suspected
"FPS sync issues". A deeper look at JCEF OSR architecture refined the hypothesis:

- `setWindowlessFrameRate` is capped at 60 fps in CEF — matching 120/144 Hz monitors
  is not possible.
- `cefBrowser.invalidate()` is **not a vsync primitive**. It schedules CEF to repaint
  the OSR buffer on the next available tick, which is then composited by Swing on the EDT.
- The **per-`executeJs` invalidate (50ms throttle)** was being called for every streaming
  token / tool chip / sub-agent update. This forces an OSR paint **between** the synchronous
  `appendChild(textNode)` in `MessageBubble.appendStreamingText()` and the deferred
  `requestAnimationFrame(() => innerHTML = renderMarkdown(...))` — capturing the DOM in a
  half-rendered state. The user perceives this as "tearing".
- The **200ms `repaintTimer`** added a second unsynchronized forced-paint source on top
  of CEF's natural `OnPaint` cycle.

**Fix**: removed both forced invalidation sources. CEF's natural `OnPaint` cycle (capped
at the windowless frame rate) is left to handle repaints. The `streaming` boolean flag
that previously gated the per-call invalidate is kept — `MonitorSwitchRecovery` still
uses it to defer DOM replay until streaming ends.

**What we kept**: 60 fps streaming / 30 fps idle frame rates (still useful for CPU/GPU
load), smooth-scroll suppression during streaming, the `_scrollRAF` debounce gate, the
`_setupCodeBlocks()` streaming-bubble skip, and the `_scrollToInstant()` autoscroll
helper.

### Fix 5 — Remove synchronous `scrollIfNeeded()` from `upsertToolChip()`

**Observation**: Tearing persisted specifically when tool chips were added to the chat panel during
streaming.

**Root cause**: `upsertToolChip()` in `ChatController.ts` called `this._container()?.scrollIfNeeded()`
synchronously immediately after `ctx.meta!.appendChild(chip)`. This writes `scrollTop = scrollHeight`,
which forces a synchronous layout reflow. In JCEF OSR, a forced reflow during streaming can trigger an
intermediate OSR paint that captures the DOM in a half-rendered state (chip appended, but its
`connectedCallback` layout is not yet computed). The `scrollHeight` is also stale at this point for the
same reason. The `MutationObserver` + `ResizeObserver` on `ChatContainer` already schedule a
rAF-debounced `scrollIfNeeded()` for the same mutation, so the synchronous call was redundant.

**Fix**: Removed the synchronous `scrollIfNeeded()` call from `upsertToolChip()`. The observers handle
auto-scroll via rAF after layout is computed, identical to the Fix 3 fix for `appendAgentText()`.

### Fix 6 — Remove scroll-time virtualization and defer remaining scroll writes

**Observation**: Tearing still reproduced on Linux and Windows when the chat pane itself scrolled,
including outside the narrow tool-chip case handled by Fix 5.

**Root causes**:

1. `chat-message { content-visibility: auto; contain-intrinsic-size: auto 120px; }` virtualized row
   painting. In a normal browser this can improve long-list performance, but in JCEF OSR it defers
   paint/layout work into the active scroll frame. The Swing host can then composite an off-screen
   Chromium buffer where newly visible rows are still stale or only partially painted.
2. `chat-container { contain: paint; }` added another paint-containment boundary around the scrolling
   surface, making OSR damage tracking more fragile during scroll.
3. The earlier fixes removed synchronous scroll writes from agent text and initial tool-chip insertion,
   but high-frequency paths such as thinking chunks, sub-agent updates, working indicator display,
   permission prompts, and nudge/queued-message rendering still called `scrollIfNeeded()` directly.

**Fix**: Removed the scroll-time CSS virtualization/paint containment and added
`ChatContainer.scheduleScrollIfNeeded()`, which reuses the existing `_scrollRAF` gate. ChatController
now schedules the remaining autoscroll writes instead of forcing `scrollTop` synchronously in the same
DOM mutation turn.

### Fix 7 — Boost OSR frame rate during active scroll and remove agent tooltip

**Observation**: Tearing still reproduced on Linux and Windows when the chat pane scrolled manually.
That points at the IDE/JCEF OSR repaint cadence: the browser was allowed to stay at the 30fps idle
windowless frame rate while Swing composited a moving off-screen buffer. At the same time, hovering chat
bubbles or tool chips showed the client name. That tooltip was not a native browser title; it was a CSS
pseudo-element on `chat-message[data-agent]:hover::before`.

**Root causes**:

1. Manual scrolling is an active animation, but the panel stayed at `IDLE_FRAME_RATE` (30fps) unless the
   agent was streaming. On 60Hz+ displays, that makes stale Chromium OSR frames more visible while the
   Swing host keeps repainting the tool window.
2. A stationary mouse pointer over the scrolling chat pane makes different rows enter/leave `:hover`
   continuously. The client-name pseudo-element was created and destroyed during scroll frames,
   adding extra paint invalidation during the same gesture.
3. The scroll handler clicked `load-more` synchronously when the user reached the top. That could
   insert history and compensate scroll from inside the scroll event itself.

**Fix**: `ChatContainer` now notifies the Kotlin bridge when scrolling starts and when it has been idle
for 140ms. `ChatConsolePanel` boosts `setWindowlessFrameRate()` to 60fps for that active scroll window
and returns to 30fps only after scrolling ends (unless streaming is still active). The agent tooltip CSS
was removed entirely, message descendants stop receiving pointer hover/click hit-tests during active
scroll, and the load-more trigger is now deferred through `requestAnimationFrame()` instead of mutating
the DOM from the scroll event callback.

---

## Code Locations

| File                       | Component               | Purpose                                                                         |
|----------------------------|-------------------------|---------------------------------------------------------------------------------|
| `ChatConsolePanel.kt`      | `startStreaming()`      | Sets 60fps, marks `streaming=true`, disables smooth scroll                      |
| `ChatConsolePanel.kt`      | `finishResponse()`      | Sets 30fps unless active scroll still needs 60fps, marks `streaming=false`, restores smooth scroll |
| `ChatConsolePanel.kt`      | `executeJs()`           | Plain `executeJavaScript` — no manual `invalidate()` (Fix 4)                    |
| `ChatConsolePanel.kt`      | `setFrameRate()`        | Wraps `setWindowlessFrameRate()`                                                |
| `ChatConsolePanel.kt`      | Scroll bridge handlers  | Temporarily boosts JCEF OSR to 60fps while the user is actively scrolling       |
| `ChatContainer.ts`         | `_scrollRAF`            | rAF debounce gate for scroll writes                                             |
| `ChatContainer.ts`         | `ResizeObserver`        | Debounced via `_scrollRAF` — never writes scrollTop directly                    |
| `ChatContainer.ts`         | `MutationObserver`      | Auto-scroll trigger — debounced via `_scrollRAF`                                |
| `ChatContainer.ts`         | Scroll handler          | Adds `is-scrolling` during active scroll and rAF-defers load-more clicks        |
| `ChatContainer.ts`         | `_copyObs`              | Code block buttons — skips streaming bubbles                                    |
| `ChatContainer.ts`         | `_setupCodeBlocks()`    | Checks `pre.closest('message-bubble[streaming]')`                               |
| `ChatContainer.ts`         | `setStreaming()`        | Toggles CSS smooth-scroll policy between streaming and idle                     |
| `ChatContainer.ts`         | `_scrollToInstant()`    | Temporarily forces `scroll-behavior: auto` for scroll                           |
| `ChatContainer.ts`         | `scheduleScrollIfNeeded()` | rAF-deferred autoscroll entry point for DOM mutation paths                   |
| `ChatController.ts`        | `appendAgentText()`     | No longer calls synchronous `scrollIfNeeded()` (Fix 3)                          |
| `ChatController.ts`        | Remaining autoscroll call sites | Use `scheduleScrollIfNeeded()` instead of direct scroll writes (Fix 6) |
| `ChatController.ts`        | `upsertToolChip()`      | No longer calls synchronous `scrollIfNeeded()` (Fix 5)                          |
| `chat.css`                 | `chat-container`, `chat-message` | No paint containment, content-visibility virtualization, or hover tooltip overlays in OSR |
| `MessageBubble.ts`         | `appendStreamingText()` | rAF-debounced markdown re-render                                                |
| `MonitorSwitchRecovery.kt` | `triggerRecovery()`     | Refreshes OSR and asks the chat panel to replay DOM state after monitor changes |

---

## Potential Future Regression Vectors

When modifying the streaming pipeline, watch for:

1. **New MutationObservers on `_messages`** — any observer that modifies DOM during streaming
   risks creating a mutation loop. Always check for `message-bubble[streaming]` before adding nodes.

2. **Synchronous `scrollTop` writes** — never write `scrollTop` directly during streaming outside
   the `_scrollRAF` gate. Use `scheduleScrollIfNeeded()` for DOM-mutation-driven autoscroll,
   `scrollIfNeeded()` only from inside the rAF gate, or `_scrollToInstant()`-backed helpers for
   explicit snap-to-bottom operations.

3. **New `executeJs` calls during streaming** — these no longer trigger forced
   invalidation, but excessive calls still add EDT overhead via `pushJsEvent()`.
   Batch when possible. Do NOT add a `cef.invalidate()` here — see Fix 4.

4. **CSS `scroll-behavior` changes** — never set `scroll-behavior: smooth` during streaming.
   The `setStreaming(true, false)` call at stream start handles this.

5. **MonitorSwitchRecovery** — after a confirmed monitor/display change, it refreshes JCEF OSR
   and the chat panel replays the DOM from Kotlin state once streaming is idle. False positives
   during streaming could still be catastrophic, so keep the fingerprint gate tight and preserve
   the deferred replay behavior.

6. **Frame rate changes** — don't lower `STREAMING_FRAME_RATE` (60), `IDLE_FRAME_RATE` (30), or remove
   the manual-scroll 60fps boost without testing for tearing on Linux and Windows.

7. **Forced OSR invalidation** — do NOT reintroduce manual `cefBrowser.invalidate()` calls
   keyed off streaming state (per-token, periodic timer, etc.). They paint mid-rAF and
   capture half-rendered DOM. This was the root cause uncovered by Fix 4.

8. **CSS scroll virtualization** — do NOT reintroduce `content-visibility`,
   `contain-intrinsic-size`, or paint containment on the chat scroll container or rows without
   testing JCEF OSR on Linux and Windows during active scroll.

9. **Hover overlays while scrolling** — avoid tooltip-like `:hover::before` / `:hover::after`
   overlays inside `chat-container`. They repaint repeatedly as rows move under a stationary mouse
   during scroll and can re-trigger JCEF OSR tearing.

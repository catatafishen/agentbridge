# Screen Tearing Bug — JCEF OSR

**Status**: Fix 5 applied — synchronous `scrollIfNeeded()` removed from `upsertToolChip()`
**Scope**: JCEF Off-Screen Rendering (OSR) mode in the chat panel  
**Affected area**: `ChatConsolePanel.kt`, `ChatContainer.ts`, `ChatController.ts`, `MessageBubble.ts`

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
- **Programmatic bottom-lock is always instant** — `scrollIfNeeded()`, `forceScroll()`, and
  `compensateScroll()` all go through `_scrollToInstant()` even when smooth scrolling is enabled.
- **CEF invalidation removed** — Fix 4 removed both the periodic `repaintTimer` and the
  per-`executeJs` `cef.invalidate()` calls. CEF's native `OnPaint` cycle (capped at the
  windowless frame rate) handles repaints. Do not reintroduce manual invalidation —
  see Fix 4 for the rationale.
- **No code block decoration during streaming** — `_setupCodeBlocks()` skips `<pre>` elements
  inside `message-bubble[streaming]` to avoid DOM churn.

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

<<<<<<< HEAD

### Fix 4 — Remove forced OSR invalidation (this commit)

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

### Fix 5 — Remove synchronous `scrollIfNeeded()` from `upsertToolChip()` (this commit)

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

---

## Code Locations

| File                       | Component               | Purpose                                                                         |
|----------------------------|-------------------------|---------------------------------------------------------------------------------|
| `ChatConsolePanel.kt`      | `startStreaming()`      | Sets 60fps, marks `streaming=true`, disables smooth scroll                      |
| `ChatConsolePanel.kt`      | `finishResponse()`      | Sets 30fps, marks `streaming=false`, restores smooth scroll                     |
| `ChatConsolePanel.kt`      | `executeJs()`           | Plain `executeJavaScript` — no manual `invalidate()` (Fix 4)                    |
| `ChatConsolePanel.kt`      | `setFrameRate()`        | Wraps `setWindowlessFrameRate()`                                                |
| `ChatContainer.ts`         | `_scrollRAF`            | rAF debounce gate for scroll writes                                             |
| `ChatContainer.ts`         | `ResizeObserver`        | Debounced via `_scrollRAF` — never writes scrollTop directly                    |
| `ChatContainer.ts`         | `MutationObserver`      | Auto-scroll trigger — debounced via `_scrollRAF`                                |
| `ChatContainer.ts`         | `_copyObs`              | Code block buttons — skips streaming bubbles                                    |
| `ChatContainer.ts`         | `_setupCodeBlocks()`    | Checks `pre.closest('message-bubble[streaming]')`                               |
| `ChatContainer.ts`         | `setStreaming()`        | Toggles CSS smooth-scroll policy between streaming and idle                     |
| `ChatContainer.ts`         | `_scrollToInstant()`    | Temporarily forces `scroll-behavior: auto` for scroll                           |
| `ChatController.ts`        | `appendAgentText()`     | No longer calls synchronous `scrollIfNeeded()` (Fix 3)                          |
| `ChatController.ts`        | `upsertToolChip()`      | No longer calls synchronous `scrollIfNeeded()` (Fix 5)                          |
| `MessageBubble.ts`         | `appendStreamingText()` | rAF-debounced markdown re-render                                                |
| `MonitorSwitchRecovery.kt` | `triggerRecovery()`     | Refreshes OSR and asks the chat panel to replay DOM state after monitor changes |

---

## Potential Future Regression Vectors

When modifying the streaming pipeline, watch for:

1. **New MutationObservers on `_messages`** — any observer that modifies DOM during streaming
   risks creating a mutation loop. Always check for `message-bubble[streaming]` before adding nodes.

2. **Synchronous `scrollTop` writes** — never write `scrollTop` directly during streaming outside
   the `_scrollRAF` gate. Use `scrollIfNeeded()` for observer-driven autoscroll, or
   `_scrollToInstant()`-backed helpers for explicit snap-to-bottom operations.

3. **New `executeJs` calls during streaming** — these no longer trigger forced
   invalidation, but excessive calls still add EDT overhead via `pushJsEvent()`.
   Batch when possible. Do NOT add a `cef.invalidate()` here — see Fix 4.

4. **CSS `scroll-behavior` changes** — never set `scroll-behavior: smooth` during streaming.
   The `setStreaming(true, false)` call at stream start handles this.

5. **MonitorSwitchRecovery** — after a confirmed monitor/display change, it refreshes JCEF OSR
   and the chat panel replays the DOM from Kotlin state once streaming is idle. False positives
   during streaming could still be catastrophic, so keep the fingerprint gate tight and preserve
   the deferred replay behavior.

6. **Frame rate changes** — don't lower `STREAMING_FRAME_RATE` (60) or `IDLE_FRAME_RATE` (30)
   without testing for tearing.

7. **Forced OSR invalidation** — do NOT reintroduce manual `cefBrowser.invalidate()` calls
   keyed off streaming state (per-token, periodic timer, etc.). They paint mid-rAF and
   capture half-rendered DOM. This was the root cause uncovered by Fix 4.

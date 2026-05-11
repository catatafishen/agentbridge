# Screen Tearing Bug — JCEF OSR

**Status**: Fix 12 applied — chip status changes use CSS-class-only updates instead of innerHTML replacement to
eliminate spurious MutationObserver triggers during streaming
**Scope**: JCEF Off-Screen Rendering (OSR) mode in the chat panel  
**Affected area**: `ChatConsolePanel.kt`, `ChatContainer.ts`, `ChatController.ts`, `MessageBubble.ts`, `chat.css`,
`ToolChip.ts`, `ThinkingChip.ts`, `SubagentChip.ts`

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
- **Manual scroll gets streaming-rate OSR repaint cadence** — `ChatContainer` adds the `is-scrolling`
  class during active scroll gestures for 140ms after scroll activity stops.
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

### Fix 8 — Defer autoscroll one rAF after DOM mutation (this commit)

**Observation**: After Fixes 1–7, tearing still reproduced on Linux and Windows (confirmed both
OSes by the user). The pattern: a new chip / message / token paints in the correct location, but
**old content nearby** (e.g. the chip just above a freshly inserted one, or sibling rows that
shifted down) **stays visually frozen** until the next "real" paint event — moving the mouse over
the panel (which changes background colors), scrolling, switching monitors. The Working… counter
keeps animating throughout the stale period, so frame production is NOT stalled. Only
`chat-container` is affected; surrounding Swing components paint normally.

**Root cause** — JBR `JBCefOsrHandler.drawByteBuffer` selective copy + Chromium compositor
tile-translation cache reuse:

1. `JBCefOsrHandler.drawByteBuffer` (in `platform/ui.jcef/jcef/JBCefOsrHandler.java`) copies
   only the dirty-rect regions reported by CEF into the cached `BufferedImage` (`myImage`).
   Pixels outside those rects in `myImage` stay frozen from the previous frame. After
   `drawByteBuffer`, JBR adds the *full* component bounds to Swing's `RepaintManager`, so Swing
   redraws the whole component — but it draws the (partially stale) `myImage`.
2. Chromium's compositor caches paint tiles. When a DOM mutation only translates siblings (e.g.
   a new chip pushes content down) AND a `scrollTop` write happens in the **same animation
   frame**, the compositor batches both into one paint and may use *tile translation cache reuse*
   for both:
    - Layout-shifted siblings → reused tiles, translated to new y-positions
    - Scroll-translated content → reused scroll-content layer translation
3. The reported dirty rect collapses to `(new node bounds) ∪ (scroll-revealed strip at bottom)`.
   Pixels in the middle of the viewport — where shifted siblings now sit at new y-positions —
   fall in neither set. JBR's selective copy leaves those pixels stale in `myImage`.
4. `:hover` and manual scroll heal it because they touch styles or off-viewport tiles that force
   re-rasterization, expanding the dirty rect to cover the previously-stale region.

**Why this is JBR's bug, not Chromium's**: from Chromium's perspective the *full buffer* it hands
to JBR each frame is correct. The dirty-rect hint is purely an optimization signal. JBR treats
it as authoritative and skips copying the rest, even though the compositor freely reuses tiles
across frames in ways that can leave the cached `BufferedImage` desynchronized with the rendered
viewport. A safe implementation would either copy the full buffer or invalidate the cache when
the dirty-rect coverage doesn't match the previous frame's reported damage.

**Fix (workaround pending JBR fix)**: defer the autoscroll write by one extra `requestAnimationFrame`
after every DOM-mutation- or resize-driven scroll trigger. This separates the mutation paint and
the scroll-translation paint into two distinct CEF paint frames:

- **Frame N**: mutation paints. No scroll happens, so Chromium's compositor can't use scroll-content
  layer translation. Layout-shifted siblings must be repainted at their new y-positions, expanding
  the dirty rect to cover them. JBR's selective copy now refreshes the right region.
- **Frame N+1**: scroll happens. The dirty rect is the small scroll-revealed strip, but `myImage`
  is already correct from frame N's paint.

Implementation: a single `_scheduleDeferredScroll()` private helper used by the `MutationObserver`,
`ResizeObserver`, and the public `scheduleScrollIfNeeded()` entrypoint. The existing `_scrollRAF`
debounce gate is preserved (one scroll write per active gate, regardless of trigger count); the
gate now holds nested rAF IDs.

**Cost**: ~16ms additional autoscroll latency during streaming. Imperceptible in practice — the
agent stream is much slower than one frame, and users don't notice the bottom-snap arriving one
frame later.

**What this fix does NOT address**:

- Tearing on mutations *without* autoscroll active (user has scrolled up; new content arrives
  off-screen). The bug can still manifest, but is much less noticeable since the user isn't
  watching the affected region. Fixing this would require a non-deferral approach.
- The underlying JBR `drawByteBuffer` selective-copy behavior. **TODO**: file a YouTrack issue
  against JBR linking this fix as the workaround in use.

**Falsifiability**: if Fix 8 does NOT eliminate the tearing, the hypothesis is wrong about
mutation+scroll batching being the trigger, and we need to look elsewhere — most likely a
non-scroll source of compositor tile-translation cache reuse, or a JBR issue independent of
autoscroll. In that case, candidates to investigate next: outline-offset toggle (zero-flicker
paint nudge), a synthetic 1px-wiggle scroll on idle, or a Kotlin-side `CefRenderHandler`
wrapper that always treats dirty rects as full-viewport.

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

**Fix**: `ChatContainer` now marks a scroll as active with the `is-scrolling` CSS class and keeps it
active for 140ms of scroll idle time. The agent tooltip CSS was removed entirely, message descendants
stop receiving pointer hover/click hit-tests during active scroll, and the load-more trigger is now
deferred through `requestAnimationFrame()` instead of mutating the DOM from the scroll event callback.

> **Note**: an earlier version of this fix also boosted `setWindowlessFrameRate()` from 30fps to 60fps
> during active scroll and streaming via a Kotlin bridge callback, but that mechanism was removed as it
> did not measurably reduce tearing and added unnecessary complexity.

### Fix 8 — Defer autoscroll one rAF after DOM mutation (this commit)

**Observation**: After Fixes 1–7, tearing still reproduced on Linux and Windows (confirmed both
OSes by the user). The pattern: a new chip / message / token paints in the correct location, but
**old content nearby** (e.g. the chip just above a freshly inserted one, or sibling rows that
shifted down) **stays visually frozen** until the next "real" paint event — moving the mouse over
the panel (which changes background colors), scrolling, switching monitors. The Working… counter
keeps animating throughout the stale period, so frame production is NOT stalled. Only
`chat-container` is affected; surrounding Swing components paint normally.

**Root cause** — JBR `JBCefOsrHandler.drawByteBuffer` selective copy + Chromium compositor
tile-translation cache reuse:

1. `JBCefOsrHandler.drawByteBuffer` (in `platform/ui.jcef/jcef/JBCefOsrHandler.java`) copies
   only the dirty-rect regions reported by CEF into the cached `BufferedImage` (`myImage`).
   Pixels outside those rects in `myImage` stay frozen from the previous frame. After
   `drawByteBuffer`, JBR adds the *full* component bounds to Swing's `RepaintManager`, so Swing
   redraws the whole component — but it draws the (partially stale) `myImage`.
2. Chromium's compositor caches paint tiles. When a DOM mutation only translates siblings (e.g.
   a new chip pushes content down) AND a `scrollTop` write happens in the **same animation
   frame**, the compositor batches both into one paint and may use *tile translation cache reuse*
   for both:
    - Layout-shifted siblings → reused tiles, translated to new y-positions
    - Scroll-translated content → reused scroll-content layer translation
3. The reported dirty rect collapses to `(new node bounds) ∪ (scroll-revealed strip at bottom)`.
   Pixels in the middle of the viewport — where shifted siblings now sit at new y-positions —
   fall in neither set. JBR's selective copy leaves those pixels stale in `myImage`.
4. `:hover` and manual scroll heal it because they touch styles or off-viewport tiles that force
   re-rasterization, expanding the dirty rect to cover the previously-stale region.

**Why this is JBR's bug, not Chromium's**: from Chromium's perspective the *full buffer* it hands
to JBR each frame is correct. The dirty-rect hint is purely an optimization signal. JBR treats
it as authoritative and skips copying the rest, even though the compositor freely reuses tiles
across frames in ways that can leave the cached `BufferedImage` desynchronized with the rendered
viewport. A safe implementation would either copy the full buffer or invalidate the cache when
the dirty-rect coverage doesn't match the previous frame's reported damage.

**Fix (workaround pending JBR fix)**: defer the autoscroll write by one extra `requestAnimationFrame`
after every DOM-mutation- or resize-driven scroll trigger. This separates the mutation paint and
the scroll-translation paint into two distinct CEF paint frames:

- **Frame N**: mutation paints. No scroll happens, so Chromium's compositor can't use scroll-content
  layer translation. Layout-shifted siblings must be repainted at their new y-positions, expanding
  the dirty rect to cover them. JBR's selective copy now refreshes the right region.
- **Frame N+1**: scroll happens. The dirty rect is the small scroll-revealed strip, but `myImage`
  is already correct from frame N's paint.

Implementation: a single `_scheduleDeferredScroll()` private helper used by the `MutationObserver`,
`ResizeObserver`, and the public `scheduleScrollIfNeeded()` entrypoint. The existing `_scrollRAF`
debounce gate is preserved (one scroll write per active gate, regardless of trigger count); the
gate now holds nested rAF IDs.

**Cost**: ~16ms additional autoscroll latency during streaming. Imperceptible in practice — the
agent stream is much slower than one frame, and users don't notice the bottom-snap arriving one
frame later.

**What this fix does NOT address**:

- Tearing on mutations *without* autoscroll active (user has scrolled up; new content arrives
  off-screen). The bug can still manifest, but is much less noticeable since the user isn't
  watching the affected region. Fixing this would require a non-deferral approach.
- The underlying JBR `drawByteBuffer` selective-copy behavior. **TODO**: file a YouTrack issue
  against JBR linking this fix as the workaround in use.

**Falsifiability**: if Fix 8 does NOT eliminate the tearing, the hypothesis is wrong about
mutation+scroll batching being the trigger, and we need to look elsewhere — most likely a
non-scroll source of compositor tile-translation cache reuse, or a JBR issue independent of
autoscroll. In that case, candidates to investigate next: outline-offset toggle (zero-flicker
paint nudge), a synthetic 1px-wiggle scroll on idle, or a Kotlin-side `CefRenderHandler`
wrapper that always treats dirty rects as full-viewport.

**If Fix 8 confirms the diagnosis, code that can be cleaned up afterward** (do this in a separate
commit, AFTER user-confirmed verification on Linux + Windows over multiple streaming sessions):

- `ChatConsolePanel.kt` scroll-bridge → 60fps boost during manual scroll (Fix 7) may no longer be
  needed if dirty-rect coalescence was the underlying cause. Removing the boost would simplify the
  frame-rate state machine (just streaming-vs-idle).
- `is-scrolling` class + descendant `pointer-events: none` (Fix 7 hover suppression) was added
  specifically to avoid `:hover` repaints during scroll. If Fix 8 fully heals scroll-time tearing,
  this guard becomes redundant and can be removed.
- `MonitorSwitchRecovery` deferred DOM replay logic exists to recover from the same class of
  staleness. It's still useful for legitimate monitor switches, but the replay-on-streaming-end
  guard could be relaxed if tearing during streaming is gone.

Do not remove these speculatively. Each was added because the previous fix wasn't sufficient. Only
remove after Fix 8 is proven stable.

---

### Fix 9 — Extend two-rAF deferral to non-streaming scroll triggers (2025)

**Status**: In testing.

**Problem**: Fix 8 eliminated streaming tearing but two scenarios remained:

1. **User message / nudge append** (`addUserMessage()`): called `forceScroll()` directly after
   inserting the new message DOM node. `forceScroll()` writes `scrollTop` synchronously, which
   means the DOM mutation (message node insert) and the `scrollTop` write landed in the *same* CEF
   paint frame — the identical batching pattern that Fix 8 fixed for streaming.

2. **Tool chip horizontal scroll** (`MessageMeta._scrollToEnd()`): when a new chip was appended to
   `.chip-strip`, the code called `strip.scrollTo({behavior: 'smooth'})` in a single rAF. One-rAF
   deferral is insufficient — it fires in the same frame that Chromium processes the layout change
   from the newly inserted chip, so the horizontal scroll and chip-layout dirty rects batch
   together. The `'smooth'` mode compound the issue by producing multiple sequential scroll-write
   frames, each potentially overlapping with the next chip insertion.

**Fix**:

- `ChatContainer.ts`: Added `scheduleForceScroll()` — sets `_autoScroll = true`, then calls
  `_scheduleDeferredScroll()`. Identical to the `scheduleScrollIfNeeded()` path but also re-arms
  auto-scroll, matching the semantics of the old `forceScroll()` call at the user-message site.

- `ChatController.ts` `addUserMessage()`: replaced `this._container()?.forceScroll()` with
  `this._container()?.scheduleForceScroll()`.

- `MessageMeta.ts` `_scrollToEnd()`: changed from one rAF to two rAFs (matching Fix 8 pattern),
  and changed `behavior: 'smooth'` → `behavior: 'instant'`. Instant makes the horizontal scroll
  atomic in one CEF paint frame; smooth was causing a multi-frame animation chain with each frame
  creating a new scroll-write + potential mutation overlap.

---

### Fix 10 — Defer `set autoScroll` bridge setter (2025)

**Status**: In testing.

**Problem**: When the Kotlin bridge calls `setAutoScroll(true)` (e.g., after the user clicks
a "scroll to bottom" button or when switching conversations), the `set autoScroll` setter
wrote `scrollTop` synchronously via `_scrollToInstant()`. If any DOM mutation occurred in the
same frame (e.g., from a concurrent tool call insertion or message append), the mutation +
scroll landed in the same CEF paint frame — the same batching pattern that causes tearing.

**Fix**: Changed `set autoScroll` to call `_scheduleDeferredScroll()` instead of
`_scrollToInstant()` directly. This ensures all scroll-to-bottom paths, including the
bridge-initiated toggle, go through the two-rAF deferral pattern.

**All scroll-to-bottom paths now deferred**:

| Trigger                   | Method                            | Deferral                    |
|---------------------------|-----------------------------------|-----------------------------|
| DOM mutation              | MutationObserver                  | `_scheduleDeferredScroll()` |
| Resize                    | ResizeObserver                    | `_scheduleDeferredScroll()` |
| User message append       | `scheduleForceScroll()`           | `_scheduleDeferredScroll()` |
| Nudge/queued message      | `scheduleScrollIfNeeded()`        | `_scheduleDeferredScroll()` |
| Markdown finalize         | `scheduleScrollIfNeeded()`        | `_scheduleDeferredScroll()` |
| Bridge `setAutoScroll()`  | `set autoScroll`                  | `_scheduleDeferredScroll()` |
| First tool chip insertion | MutationObserver (implicit)       | `_scheduleDeferredScroll()` |
| Chip-strip horizontal     | `MessageMeta._scrollToEnd()`      | Two-rAF inline              |
| History restore final     | `restoreBatchFinal()` → rAF×2     | Two-rAF wrapper             |
| Nav indicator update      | `MessageMeta.scheduleNavUpdate()` | Two-rAF inline (Fix 11b)    |

### Fix 11 — Render-pending retry and nav-indicator two-rAF deferral (2025)

**Status**: In testing.

**Problem**: Four residual tearing scenarios persisted after Fix 10:

1. **Thought chip added/collapsed during streaming**: The 2-rAF scroll from the chip's
   MutationObserver trigger fires in frame N+1. `MessageBubble.appendStreamingText()` also fires
   its rAF-render in frame N+1 (for tokens that arrived between frames N and N+1). Both land in
   the same paint frame → dirty-rect collapse → tearing. The timing collision is a consequence of
   rAF queue ordering: scrollRAF-outer was queued at frame N's MutationObserver microtask;
   rAF-render was queued in a task between N and N+1, so after scrollRAF-outer. Therefore in
   frame N+1, scrollRAF-outer fires first (good), but rAF-render fires AFTER — meaning the inner
   scrollRAF (N+2) and the next frame's rAF-render (also N+2 if tokens arrive between N+1 and
   N+2) still collide.

2. **Tool chip loading indicator removed during streaming**: `setToolChipState()` →
   `ToolChip._render()` → synchronous innerHTML change → MutationObserver → 2-rAF scroll. Same
   N+2 collision with rAF-renders from concurrent streaming.

3. **Nav indicator (‹ ›) appearance when chip strip overflows**: `scheduleNavUpdate()` used a
   single rAF. The `sharedResizeObserver` fires in frame N's layout phase and queues
   `scheduleNavUpdate()` for frame N+1. The container scroll (from the same chip-addition
   trigger) fires in frame N+1 via its own 2-rAF chain. Nav button class toggle + container
   `scrollTop` write in the same frame → dirty-rect collapse → tearing.

4. **Multiple events on consecutive frames**: Prompt, chip, and thinking arrivals in a tight
   burst use the coalesced gate, but discrete events interleaved with streaming tokens could
   land the scroll in a frame that also has an rAF-render from newer tokens.

**Root cause — rAF queue ordering**: `MessageBubble.appendStreamingText()` queues its rAF-render
during a task (tokens arriving between frames). `_scheduleDeferredScroll()` queues scrollRAF-outer
during the MutationObserver microtask that follows. The task runs *before* the microtask, so
rAF-render is queued *before* scrollRAF-outer. In the next frame, rAF callbacks run in queue
order, so rAF-render fires *before* scrollRAF-outer. This means `renderPending = true` at the
time the *inner* scrollRAF fires — the render for the *next* batch of tokens (queued in a new
task between frames) is still in the queue when the scroll fires, putting mutation and scroll
in the same paint frame.

**Fix**:

- **`ChatContainer.ts` `_flushScrollOrRetry()`** (Fix 11a): When the inner rAF fires, check
  whether any `message-bubble[streaming]` element has `renderPending === true`. If yes, a
  rAF-render for tokens that arrived this inter-frame window is queued *after* the inner rAF
  (per queue order) and will fire in the same paint frame. Defer by one more rAF. Retry up to
  `MAX_SCROLL_RETRIES = 3` times to guarantee progress during sustained heavy streaming.
  After `MAX_SCROLL_RETRIES`, scroll is forced regardless (accepting occasional rare tearing
  during extremely fast streams).

  The `renderPending` check is accurate: it is set `true` when a token arrives (in the task)
  and cleared `false` at the start of the rAF-render callback. At the time the inner scrollRAF
  fires, `renderPending = true` means there is a rAF-render queued in the current frame (already
  in the queue, fires after us). This is precisely the collision we need to avoid.

- **`MessageBubble.ts` `renderPending` getter** (Fix 11a): Exposes `_renderPending` as a read-
  only public property for `ChatContainer` to inspect.

- **`MessageMeta.ts` `scheduleNavUpdate()`** (Fix 11b): Changed from one rAF to two rAFs.
  The `sharedResizeObserver` fires in frame N's layout phase; with one rAF the nav update fired
  in frame N+1 — the same frame as the container scroll. With two rAFs it fires in frame N+2,
  one frame after the scroll, eliminating the nav-button layout-change + scrollTop collision.

---

### Fix 12 — CSS-only chip status updates to eliminate spurious MutationObserver triggers (2025)

**Root cause**: After the chip redesign that introduced `chip-ring` (a persistent `<span>` in all
chip states), `ToolChip._render()`, `SubagentChip._render()`, and `ThinkingChip._render()` continued
to be called from `attributeChangedCallback` on every status/kind change. Each call replaced
`this.innerHTML`, generating two childList DOM mutations (child removal + child insertion). With
`ChatContainer`'s MutationObserver watching `childList: true, subtree: true`, every chip status
transition triggered `_scheduleDeferredScroll()` — producing spurious scroll deferral calls during
streaming that collide with concurrent `MessageBubble` rAF-renders.

The chip redesign's intent (documented in the CSS comment: "no DOM replacement") was correct —
the `chip-ring` span is always present and the `animation: spin` is paused by CSS class rules. Only
`className` changes are needed on status transitions. The implementation diverged from the intent.

**Fix**:

- **`ToolChip.ts`**: Split `_render()` into `_renderAll()` (sets innerHTML, called ONLY from
  `connectedCallback`) and `_applyClasses()` (updates `className`/`classList` only, called from
  `attributeChangedCallback` for `status`/`kind` changes). Label changes update `lastChild.textContent`
  (a `characterData` mutation, less disruptive than `childList`).

- **`ThinkingChip.ts`**: `attributeChangedCallback` now calls `classList.toggle('thinking-active', ...)`
  directly — no `_render()` call, no `innerHTML` write.

- **`SubagentChip.ts`**: `attributeChangedCallback` for `status` changes updates `className`/`classList`
  directly. `label` changes still call `_render()` because label content genuinely changes (and label
  updates are rare compared to status transitions).

---

## Code Locations

| File                       | Component                        | Purpose                                                                                              |
|----------------------------|----------------------------------|------------------------------------------------------------------------------------------------------|
| `ChatConsolePanel.kt`      | `startStreaming()`               | Marks `streaming=true`, disables smooth scroll                                                       |
| `ChatConsolePanel.kt`      | `finishResponse()`               | Marks `streaming=false`, restores smooth scroll                                                      |
| `ChatConsolePanel.kt`      | `executeJs()`                    | Plain `executeJavaScript` — no manual `invalidate()` (Fix 4)                                         |
| `ChatContainer.ts`         | `_scrollRAF`                     | rAF debounce gate for scroll writes (now holds nested 2-rAF chain — Fix 8)                           |
| `ChatContainer.ts`         | `_scheduleDeferredScroll()`      | Two-rAF deferral helper — separates DOM-mutation paint from scroll paint (Fix 8)                     |
| `ChatContainer.ts`         | `ResizeObserver`                 | Debounced via `_scheduleDeferredScroll()` — never writes scrollTop directly                          |
| `ChatContainer.ts`         | `MutationObserver`               | Auto-scroll trigger — debounced via `_scrollRAF`                                                     |
| `ChatContainer.ts`         | Scroll handler                   | Adds `is-scrolling` during active scroll and rAF-defers load-more clicks                             |
| `ChatContainer.ts`         | `_copyObs`                       | Code block buttons — skips streaming bubbles                                                         |
| `ChatContainer.ts`         | `_setupCodeBlocks()`             | Checks `pre.closest('message-bubble[streaming]')`                                                    |
| `ChatContainer.ts`         | `setStreaming()`                 | Toggles CSS smooth-scroll policy between streaming and idle                                          |
| `ChatContainer.ts`         | `_scrollToInstant()`             | Temporarily forces `scroll-behavior: auto` for scroll                                                |
| `ChatContainer.ts`         | `scheduleScrollIfNeeded()`       | rAF-deferred autoscroll entry point for DOM mutation paths                                           |
| `ChatContainer.ts`         | `scheduleForceScroll()`          | Like `scheduleScrollIfNeeded()` but also re-arms auto-scroll — used for user-message appends (Fix 9) |
| `ChatContainer.ts`         | `set autoScroll`                 | Bridge-initiated scroll toggle — deferred via `_scheduleDeferredScroll()` (Fix 10)                   |
| `ChatController.ts`        | `appendAgentText()`              | No longer calls synchronous `scrollIfNeeded()` (Fix 3)                                               |
| `ChatController.ts`        | Remaining autoscroll call sites  | Use `scheduleScrollIfNeeded()` instead of direct scroll writes (Fix 6)                               |
| `ChatController.ts`        | `upsertToolChip()`               | No longer calls synchronous `scrollIfNeeded()` (Fix 5)                                               |
| `ChatController.ts`        | `addUserMessage()`               | Uses `scheduleForceScroll()` — two-rAF deferred, not synchronous `forceScroll()` (Fix 9)             |
| `MessageMeta.ts`           | `_scrollToEnd()`                 | Two-rAF deferred, `behavior: 'instant'` — chip-strip horizontal scroll (Fix 9)                       |
| `chat.css`                 | `chat-container`, `chat-message` | No paint containment, content-visibility virtualization, or hover tooltip overlays in OSR            |
| `MessageBubble.ts`         | `renderPending` getter           | Exposes `_renderPending` for `ChatContainer` to detect pending rAF-render collision (Fix 11a)        |
| `MessageMeta.ts`           | `scheduleNavUpdate()`            | Two-rAF deferred nav update — separates nav button layout change from container scroll (Fix 11b)     |
| `ChatContainer.ts`         | `_flushScrollOrRetry()`          | Retries the scroll when `renderPending` is true; caps at `MAX_SCROLL_RETRIES` (Fix 11a)              |
| `MonitorSwitchRecovery.kt` | `triggerRecovery()`              | Refreshes OSR and asks the chat panel to replay DOM state after monitor changes                      |
| `ToolChip.ts`              | `_applyClasses()`                | CSS-only status/kind update — no innerHTML, no childList DOM mutations (Fix 12)                      |
| `ThinkingChip.ts`          | `attributeChangedCallback()`     | CSS-only thinking-active toggle — no innerHTML on status change (Fix 12)                             |
| `SubagentChip.ts`          | `attributeChangedCallback()`     | CSS-only status update; `_render()` only for label changes (Fix 12)                                  |

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

10. **Opacity-toggle "paint nudges"** — do NOT add CSS opacity transitions or programmatic
    `style.opacity` toggles on `chat-container`, `chat-message`, or any of their descendants as
    a workaround for OSR tearing. This was attempted in earlier iterations and caused **visible
    flickering** even with rAF debouncing. The visible side-effect outweighs the dirty-rect benefit.
    Other zero-flicker alternatives (outline-offset toggle, subpixel scroll wiggle) should be tried
    first if Fix 8's deferral is insufficient.

11. **Same-frame mutation + scrollTop writes** (Fix 8) — never write `scrollTop` in the same
    `requestAnimationFrame` callback as a DOM mutation. Always go through
    `_scheduleDeferredScroll()` (or `scheduleScrollIfNeeded()`) so the scroll lands in the
    *next* paint frame. Same-frame batching enables Chromium compositor tile-translation cache
    reuse, which collapses the dirty rect and leaves shifted siblings stale in JBR's
    `BufferedImage` cache. See Fix 8 for the full mechanism.

12. **rAF-render + scrollRAF-inner in the same frame** (Fix 11a) — the 2-rAF scroll pattern
    guarantees the scroll fires one frame after the triggering mutation. But `MessageBubble`
    queues its rAF-render *before* the MutationObserver microtask (task vs microtask ordering),
    so scrollRAF-inner and rAF-render can still land in the same frame for tokens that arrive
    in the inter-frame window. The `_flushScrollOrRetry()` check ensures the scroll defers if
    `renderPending` is true at the time the inner rAF fires. **Do NOT remove the `renderPending`
    check or change the ordering of `_renderPending = false` relative to `innerHTML =` in
    `MessageBubble._doRender()`** — the correctness of the retry depends on this flag being
    cleared *before* the innerHTML assignment so that a retry in the same frame has a clean
    DOM to measure.

13. **Nav update in same frame as scroll** (Fix 11b) — `scheduleNavUpdate()` MUST use 2 rAFs
    (not 1). A single rAF from a `ResizeObserver` callback lands in the same frame as
    `_scheduleDeferredScroll()`'s inner rAF. The nav button class change (layout shift inside
    the scrolled content) and the `scrollTop` write in the same frame cause dirty-rect collapse.
    **Do not reduce `scheduleNavUpdate()` back to a single rAF** without re-testing chip strip
    overflow during streaming on both Linux and Windows.

14. **`innerHTML` replacement on chip status changes** (Fix 12) — `ToolChip`, `ThinkingChip`, and
    `SubagentChip` MUST NOT call `this.innerHTML = ...` from `attributeChangedCallback` for status or
    kind changes. `innerHTML` replacement removes existing child nodes (childList mutation: removal)
    and inserts new ones (childList mutation: insertion), firing `ChatContainer`'s MutationObserver
    twice per status transition — triggering spurious `_scheduleDeferredScroll()` calls during
    streaming. These collide with concurrent `MessageBubble` rAF-renders in exactly the way Fix 11
    was designed to handle, but Fix 11's retry only applies to `MessageBubble.renderPending`; it does
    not protect against chip-mutation-triggered scrolls.
    **Status/kind changes MUST only update `this.className` / `classList`** — attribute changes on
    elements are NOT observed by `ChatContainer`'s MutationObserver (`attributes: false`), so no
    scroll deferral fires. See `ToolChip._applyClasses()`.
    The chip-ring `<span>` is already in the DOM from `connectedCallback` (initial render); the CSS
    `animation: spin` is paused by `.status-complete .chip-ring { animation: none }` — no DOM node
    replacement is needed on status transitions.


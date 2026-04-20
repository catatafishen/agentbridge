# Screen Tearing Bug — JCEF OSR

**Status**: Recurring — mitigations applied, not confirmed fixed  
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
     ├── repaintTimer.start()  (200ms invalidate)   ├── repaintTimer.stop()
     └── setStreaming(true, false)  [disable smooth] └── restore smooth-scroll preference
```

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
- **CEF invalidation safety net** — `repaintTimer` fires `cef.invalidate()` every 200ms during
  streaming, plus throttled per-`executeJs` invalidation (50ms) catches inter-timer gaps.
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

---

## Code Locations

| File | Component | Purpose |
|---|---|---|
| `ChatConsolePanel.kt` | `startStreaming()` | Sets 60fps, starts repaintTimer, disables smooth scroll |
| `ChatConsolePanel.kt` | `finishResponse()` | Sets 30fps, stops repaintTimer, restores smooth scroll |
| `ChatConsolePanel.kt` | `repaintTimer` | 200ms periodic `cef.invalidate()` during streaming |
| `ChatConsolePanel.kt` | `executeJs()` | Throttled per-call `cef.invalidate()` (50ms) during streaming |
| `ChatConsolePanel.kt` | `setFrameRate()` | Wraps `setWindowlessFrameRate()` |
| `ChatContainer.ts` | `_scrollRAF` | rAF debounce gate for scroll writes |
| `ChatContainer.ts` | `ResizeObserver` | Debounced via `_scrollRAF` — never writes scrollTop directly |
| `ChatContainer.ts` | `MutationObserver` | Auto-scroll trigger — debounced via `_scrollRAF` |
| `ChatContainer.ts` | `_copyObs` | Code block buttons — skips streaming bubbles |
| `ChatContainer.ts` | `_setupCodeBlocks()` | Checks `pre.closest('message-bubble[streaming]')` |
| `ChatContainer.ts` | `setStreaming()` | Toggles CSS smooth-scroll policy between streaming and idle |
| `ChatContainer.ts` | `_scrollToInstant()` | Temporarily forces `scroll-behavior: auto` for scroll |
| `ChatController.ts` | `appendAgentText()` | No longer calls synchronous `scrollIfNeeded()` |
| `MessageBubble.ts` | `appendStreamingText()` | rAF-debounced markdown re-render |
| `MonitorSwitchRecovery.kt` | `checkAndRecover()` | Calls invalidate/wasResized on monitor change |

---

## Potential Future Regression Vectors

When modifying the streaming pipeline, watch for:

1. **New MutationObservers on `_messages`** — any observer that modifies DOM during streaming
   risks creating a mutation loop. Always check for `message-bubble[streaming]` before adding nodes.

2. **Synchronous `scrollTop` writes** — never write `scrollTop` directly during streaming outside
   the `_scrollRAF` gate. Use `scrollIfNeeded()` for observer-driven autoscroll, or
   `_scrollToInstant()`-backed helpers for explicit snap-to-bottom operations.

3. **New `executeJs` calls during streaming** — each call now triggers throttled invalidation,
   but excessive calls still add EDT overhead via `pushJsEvent()`. Batch when possible.

4. **CSS `scroll-behavior` changes** — never set `scroll-behavior: smooth` during streaming.
   The `setStreaming(true, false)` call at stream start handles this.

5. **MonitorSwitchRecovery** — if triggered during streaming, it calls `setWindowVisibility`,
   `notifyScreenInfoChanged`, `wasResized`, `invalidate`. False positives during streaming could
   be catastrophic. The fingerprint check should prevent this, but verify after changes.

6. **Frame rate changes** — don't lower `STREAMING_FRAME_RATE` (60) or `IDLE_FRAME_RATE` (30)
   without testing for tearing.

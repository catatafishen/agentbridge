package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.RENDER_INTERVAL_MS
import com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.RESIZE_SETTLE_MS
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.TextUI
import javax.swing.text.*
import javax.swing.text.html.BlockView
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.InlineView
import javax.swing.text.html.StyleSheet

/**
 * A [JEditorPane]-based component that renders streaming markdown as HTML.
 *
 * Markdown text is accumulated via [appendMarkdown] and converted to HTML using
 * [MarkdownRenderer.markdownToHtml] with file-link resolution from [FileNavigator].
 * Re-rendering is throttled to at most [RENDER_INTERVAL_MS] ms (~30 fps) during streaming.
 * Each chunk either renders immediately (if enough time has elapsed) or schedules a render
 * at the end of the current interval window, so bursts of rapid tokens never suppress
 * visible updates the way a debounce would.
 *
 * Height is computed accurately after each render via the same HTML layout engine that
 * handles window-resize reflows for all bubbles. [onHeightGrew] fires through [setBounds]
 * whenever the layout manager assigns a taller height — accurate, zero-latency, no
 * char-counting heuristics.
 *
 * The embedded [HTMLEditorKit] stylesheet is generated from the current IDE theme
 * colors so that code blocks, tables, headings, and links look correct in both
 * light and dark themes.
 */
class NativeMarkdownPane(private val fileNavigator: FileNavigator) : JEditorPane() {

    /**
     * Called whenever the layout manager assigns a taller height to this pane.
     * [NativeChatPanel] wires this to scroll-to-bottom so auto-scroll follows the
     * expanding bubble without relying on [java.awt.event.ComponentListener].
     */
    var onHeightGrew: (() -> Unit)? = null

    private val rawText = StringBuilder()

    /** Returns the raw (unformatted) markdown text accumulated so far. */
    fun getRawText(): String = rawText.toString()

    /** Timestamp (ms) of the most recent [renderNow] call; used by the streaming throttle. */
    private var lastRenderTime = 0L

    /** True while the render timer is scheduled within the current throttle window. */
    private var renderScheduled = false
    private val renderTimer = Timer(1) { renderNow() }.apply { isRepeats = false }
    private val schemeDisposable = Disposer.newDisposable("NativeMarkdownPane")

    /**
     * Version counter incremented on every [renderNow]. Used together with [cachedForWidth]
     * to decide when [getPreferredSize] can skip the expensive HTML re-layout.
     */
    private var contentVersion = 0
    private var cachedForWidth = -1
    private var cachedForVersion = -1
    private var cachedHeight = -1

    /**
     * Cached rendered pixels for this pane. Keyed by [paintCacheW] × [paintCacheH] ×
     * [paintCacheVersion]. When all three match the current state, [paint] draws from this
     * image (~0.05 ms) instead of invoking the full HTML view rendering chain (~2–10 ms on
     * Windows GDI+). Cleared to null only when the component is made invisible or disposed;
     * otherwise always holds the last successfully rendered frame.
     */
    private var paintCache: BufferedImage? = null
    private var paintCacheW = -1
    private var paintCacheH = -1
    private var paintCacheVersion = -1

    private var lastContentChangeMs: Long = 0L

    /** Guards against scheduling multiple async repaints from the stale-frame tab-switch path. */
    private var staleRepaintPending = false

    /**
     * Guards against scheduling multiple [revalidate] calls from the lazy-layout path in
     * [getPreferredSize]. Cleared by the invokeLater callback that triggers the revalidation.
     */
    private var lazyRevalidatePending = false

    /**
     * Single-shot timer used to recover an accurate layout after a burst of resize-driven
     * [getPreferredSize] calls returned a tolerance-matched stale height. Restarted on every
     * tolerance hit; fires once the resize burst has been quiet for [RESIZE_SETTLE_MS].
     * When it fires, the cache is invalidated and [revalidate] is called so the parent
     * re-queries at the actual current width and gets a fresh accurate measurement.
     */
    private val resizeSettleTimer = Timer(RESIZE_SETTLE_MS) {
        cachedForWidth = -1
        paintCache = null   // force a fresh paint at the final settled width
        revalidate()
        repaint()
    }.apply { isRepeats = false }

    init {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()

        // Prevent StyledEditorKit's AttributeTracker from calling getParagraphElement()
        // during setText() — which NPEs when the document root element is transiently null
        // while being replaced. NEVER_UPDATE is safe here because the pane is read-only.
        val caret = DefaultCaret()
        caret.updatePolicy = DefaultCaret.NEVER_UPDATE
        setCaret(caret)

        val kit = ScrollableHTMLEditorKit()
        kit.styleSheet = createStyleSheet()
        editorKit = kit

        addMouseWheelListener { e -> handleWheel(e) }

        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                fileNavigator.handleFileLink(e.description)
            }
        }

        PlatformApiCompat.subscribeEditorColorSchemeChanges(schemeDisposable) {
            SwingUtilities.invokeLater { rebuildStylesheet() }
        }
    }

    /**
     * Appends a chunk of raw markdown during streaming.
     *
     * Uses a **throttle** (not a debounce): if enough time has elapsed since the
     * last render, the new content is rendered immediately. Otherwise, a render is
     * scheduled for the end of the current [RENDER_INTERVAL_MS] window, so the last
     * chunk in any burst is always shown.
     *
     * This mirrors the JCEF `appendStreamingText` strategy (rAF-batched re-render)
     * and avoids the old debounce pitfall where rapid chunks would keep restarting
     * the timer, suppressing all visible updates until the stream paused.
     */
    fun appendMarkdown(text: String) {
        val wasEmpty = rawText.isEmpty()
        rawText.append(text)
        if (wasEmpty) {
            // First token: always render immediately so the bubble appears at once.
            renderNow()
            return
        }
        val elapsed = System.currentTimeMillis() - lastRenderTime
        if (elapsed >= RENDER_INTERVAL_MS) {
            renderTimer.stop()
            renderScheduled = false
            renderNow()
        } else if (!renderScheduled) {
            renderScheduled = true
            renderTimer.initialDelay = (RENDER_INTERVAL_MS - elapsed).toInt().coerceAtLeast(1)
            renderTimer.restart()
        }
        // else: a render is already scheduled within this interval window.
    }

    /** Sets the full markdown text and renders immediately (for history replay). */
    fun setCompleteMarkdown(text: String) {
        rawText.setLength(0)
        rawText.append(text)
        renderNow()
        // Mark as "old" immediately so burst-freeze works from the first resize event.
        // renderNow() sets lastContentChangeMs = now, which would prevent freezing for 1.5s.
        lastContentChangeMs = 0L
    }

    /** Forces an immediate HTML render, resetting the throttle state. */
    fun renderNow() {
        renderTimer.stop()
        renderScheduled = false
        lastRenderTime = System.currentTimeMillis()
        contentVersion++
        lastContentChangeMs = lastRenderTime
        val displayText = QUICK_REPLY_TAG_REGEX.replace(rawText.toString(), "").trimEnd()
        val html = fileNavigator.markdownToHtml(displayText)
        // Replace the stale document with a fresh empty one before setText(). When the existing
        // document's element tree is in a transient null state, AbstractDocument.handleRemove()
        // NPEs at Utilities.isComposedTextElement(null) because getCharacterElement(0) returns null.
        // A fresh document has length 0, so AbstractDocument.replace(0, 0, …) skips remove() entirely
        // (guarded by `if (length > 0)`) and goes straight to insertString().
        document = (editorKit as HTMLEditorKit).createDefaultDocument()
        text = "<html><body>$html</body></html>"
        // The fresh document builds a new view tree, so an in-progress strip drag
        // would keep scrolling a detached view; drop the interaction state.
        stripDrag = null
        if (stripCursorActive) {
            cursor = Cursor.getDefaultCursor()
            stripCursorActive = false
        }
    }

    /**
     * Signals that this pane's streaming has ended. Renders any pending content and
     * triggers a revalidation so the bubble snaps to its final accurate height.
     *
     * Called from [com.github.catatafishen.agentbridge.ui.NativeChatPanel.finalizeTurn]
     * (markdown pane) and from [com.github.catatafishen.agentbridge.ui.NativeChatPanel.collapseThinking]
     * (thinking pane).
     */
    fun notifyStreamDone() {
        renderNow()
        // Streaming is finished — mark as "old" so burst-freeze applies immediately.
        lastContentChangeMs = 0L
        revalidate()
        onHeightGrew?.invoke()
    }

    /** Rebuilds the stylesheet when the IDE editor font size changes. */
    fun onFontSizeChanged() {
        rebuildStylesheet()
    }

    /** Stops the render timer and disconnects the color scheme subscription. */
    fun dispose() {
        renderTimer.stop()
        resizeSettleTimer.stop()
        Disposer.dispose(schemeDisposable)
    }

    /**
     * Fires [onHeightGrew] whenever the layout manager assigns a taller height than before.
     * This is the scroll-to-bottom trigger during streaming — accurate and zero-latency
     * because it reacts to the actual layout result rather than a heuristic estimate.
     */
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val prev = this.height
        super.setBounds(x, y, width, height)
        if (height > prev) onHeightGrew?.invoke()
    }

    /**
     * Rebuilds the stylesheet from the current IDE theme and editor font size, and
     * re-renders existing content. Called on LAF changes (via [updateUI]) and on
     * editor color scheme changes (e.g. Alt+Shift+. / Alt+Shift+,).
     *
     * Guard: editorKit is only our HTMLEditorKit after the init block completes.
     * During the super-constructor's initial updateUI() call it is still the default
     * PlainEditorKit, so the cast returns null and we exit early.
     */
    private fun rebuildStylesheet() {
        val kit = editorKit as? HTMLEditorKit ?: return
        kit.styleSheet = createStyleSheet()
        if (rawText.isNotEmpty()) {
            SwingUtilities.invokeLater { renderNow() }
        }
    }

    /**
     * Called by Swing whenever the Look and Feel changes. Delegates to [rebuildStylesheet]
     * which rebuilds from the new theme colors and re-renders existing content.
     *
     * Note: [rawText] is not yet initialized during the super-constructor's initial call,
     * so [rebuildStylesheet]'s cast guard (editorKit as? HTMLEditorKit) exits early safely.
     */
    override fun updateUI() {
        super.updateUI()
        // Defer: updateUI() must fully complete before swapping the stylesheet, otherwise
        // setting `text` while views are in a transient state causes BadLocationException.
        SwingUtilities.invokeLater { rebuildStylesheet() }
    }

    /**
     * Prevents [BoxLayout] in the bubble row from expanding the row to accommodate the
     * HTML content's natural minimum width (e.g. long `pre` lines). Without this override
     * the bubble would overflow the viewport whenever a code block's longest line is wider
     * than the available space.
     */
    override fun getMinimumSize(): Dimension = Dimension(0, 0)

    /**
     * Paints from a cached [BufferedImage] when the component bounds and content version have
     * not changed since the last render. On a cache miss, delegates to [super.paint] (full
     * HTML rendering) and stores the result.
     *
     * **During a resize burst** (within [RESIZE_BURST_WINDOW_NANOS] of the last observed
     * width change): reuses the previous frame when it is no wider than the component. When
     * the component narrows, renders immediately at the new width to avoid clipping text.
     * Once the burst settles, [resizeSettleTimer] forces a revalidation for an accurate layout.
     *
     * **Text selection**: if the user has selected text, the cache is bypassed so the
     * selection highlight is always painted live.
     */
    override fun paint(g: Graphics) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Never cache while text is selected — selection highlight must update live.
        if (selectionStart != selectionEnd) {
            super.paint(g)
            return
        }

        // Exact cache hit: same bounds and same content — just blit.
        if (paintCache != null &&
            paintCacheW == w && paintCacheH == h &&
            paintCacheVersion == contentVersion
        ) {
            UIUtil.drawImage(g, paintCache!!, 0, 0, null)
            return
        }

        // During a resize burst, defer rendering only when the cached frame cannot crop text.
        if (paintCache != null && ResizeBurstClock.isBurstActive()) {
            resizeSettleTimer.restart()
            // A wider cached frame would clip its right-hand text when the pane narrows.
            // Re-render shrink operations immediately; only defer paints that cannot crop text.
            // Also never reuse a frame shorter than the pane: the missing bottom would stay blank.
            if (paintCacheW <= w && paintCacheH >= h) {
                UIUtil.drawImage(g, paintCache!!, 0, 0, null)
                return
            }
        }

        // Stale-frame (tab switch after resize): content unchanged but dimensions differ.
        // Blit the old frame immediately for a zero-cost first paint, then schedule a single
        // accurate repaint on the next EDT pass so the correct frame appears right after.
        if (paintCache != null && paintCacheW <= w && paintCacheH >= h &&
            paintCacheVersion == contentVersion && !staleRepaintPending
        ) {
            UIUtil.drawImage(g, paintCache!!, 0, 0, null)
            staleRepaintPending = true
            SwingUtilities.invokeLater {
                staleRepaintPending = false
                if (isShowing) {
                    paintCache = null; repaint()
                }
            }
            return
        }

        // Cache miss: render to a fresh BufferedImage, then blit.
        val img = UIUtil.createImage(this, w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            super.paint(g2)
        } finally {
            g2.dispose()
        }
        paintCache = img
        paintCacheW = w
        paintCacheH = h
        paintCacheVersion = contentVersion
        UIUtil.drawImage(g, img, 0, 0, null)
    }

    /**
     *
     * **Caching**: result is cached by `(parentWidth, contentVersion)`. Same width and
     * same content → return immediately. Any other combination — new content, or a width
     * change (e.g. window resize) — triggers a full HTML re-layout via `rootView.setSize`
     * + `getPreferredSpan`. This is the same path window-resize uses for all bubbles
     * simultaneously, so the per-render cost at ~30 fps is well within EDT budget.
     */
    override fun getPreferredSize(): Dimension {
        val p = parent ?: return super.getPreferredSize()
        val ins = p.insets

        val pw = when {
            p.maximumSize.width in 1 until Short.MAX_VALUE.toInt() ->
                p.maximumSize.width - ins.left - ins.right

            p.width > 0 ->
                p.width - ins.left - ins.right

            else -> return Dimension(0, cachedHeight.takeIf { it > 0 } ?: 1)
        }.takeIf { it > 0 } ?: return Dimension(0, cachedHeight.takeIf { it > 0 } ?: 1)

        // Exact cache hit: same width and same content.
        if ((pw == cachedForWidth) && (contentVersion == cachedForVersion)) {
            return Dimension(pw, cachedHeight)
        }

        // Frozen-during-drag cache hit: a resize burst is active AND this pane's content
        // has not changed in the last [LIVE_PANE_AGE_MS] ms (older history bubble). Skip
        // the HTML re-layout entirely for any width and return the previously cached height.
        if (cachedHeight > 0 &&
            contentVersion == cachedForVersion &&
            ResizeBurstClock.isBurstActive() &&
            (System.currentTimeMillis() - lastContentChangeMs) > LIVE_PANE_AGE_MS
        ) {
            resizeSettleTimer.restart()
            return Dimension(pw, cachedHeight)
        }

        // Tolerance cache hit: width has nudged slightly (typical during a window resize
        // drag or side-panel animation). Returning the previously cached height avoids
        // expensive HTML re-layout on every pixel of drag.
        if (cachedForWidth > 0 &&
            contentVersion == cachedForVersion &&
            (kotlin.math.abs(pw - cachedForWidth) <= RESIZE_TOLERANCE_PX)
        ) {
            resizeSettleTimer.restart()
            return Dimension(pw, cachedHeight)
        }

        // Lazy layout: panel became visible after an off-screen resize (no burst active).
        // Return the stale height immediately so the panel opens without blocking the EDT,
        // then revalidate on the next pass for accurate heights. Guard: cachedForWidth > 0
        // so this never fires after the settle timer resets cachedForWidth to -1.
        if (cachedForWidth > 0 && cachedHeight > 0 &&
            contentVersion == cachedForVersion && !lazyRevalidatePending
        ) {
            lazyRevalidatePending = true
            SwingUtilities.invokeLater {
                lazyRevalidatePending = false
                if (isShowing) {
                    cachedForWidth = -1; revalidate()
                }
            }
            return Dimension(pw, cachedHeight)
        }

        // Accurate layout.
        val textUI = ui as? TextUI
        if (textUI != null) {
            val rootView = textUI.getRootView(this)
            try {
                rootView.setSize(pw.toFloat(), Short.MAX_VALUE.toFloat())
                val h = rootView.getPreferredSpan(View.Y_AXIS).toInt().coerceAtLeast(1)
                cachedForWidth = pw
                cachedForVersion = contentVersion
                cachedHeight = h
                return Dimension(pw, h)
            } catch (_: Throwable) {
                // Multiple Swing internal exceptions can be thrown here when renderNow()
                // replaced the document while a layout pass was already in flight.
                // Fall through to the fallback below.
            }
        }
        // Document is in a transient state. Prefer the last known height so the
        // layout does not collapse while the document stabilises.
        if (cachedHeight > 0) return Dimension(pw, cachedHeight)
        // First render: attempt the slower JEditorPane path with the same guard.
        return try {
            setSize(pw, Short.MAX_VALUE.toInt())
            Dimension(pw, super.getPreferredSize().height)
        } catch (_: Throwable) {
            Dimension(pw, 1)  // safe minimum; revalidated on next cycle
        }
    }

    private fun createStyleSheet(): StyleSheet {
        val ss = StyleSheet()

        val fg = colorToHex(UIUtil.getLabelForeground())
        val mutedFg = colorToHex(UIUtil.getContextHelpForeground())
        val codeBg = colorToHex(NativeChatColors.CODE_BG)
        val tblBorder = colorToHex(NativeChatColors.TABLE_BORDER)
        val link = colorToHex(NativeChatColors.LINK)
        val labelFont = UIUtil.getLabelFont()
        val basePt = PlatformApiCompat.getEditorFontSize()
        val codeFontPt = (basePt * 0.92).toInt()

        ss.addRule("body { margin: 0; padding: 0; color: $fg; font-family: ${labelFont.family}; font-size: ${basePt}pt; line-height: 150%; }")
        ss.addRule("p { margin: 2px 0; }")
        ss.addRule("code { background-color: $codeBg; font-family: monospace; font-size: ${codeFontPt}pt; }")
        ss.addRule("pre { background-color: $codeBg; padding: 8px 12px; border-left: 3px solid $tblBorder; margin: 6px 0; }")
        ss.addRule("pre code { background-color: transparent; }")
        // Diff coloring inside ```diff code fences (e.g. edit-approval bubbles). Uses the
        // same success/fail colors the tool renderers use, so it follows the IDE theme.
        // HTMLEditorKit CSS has no descendant selectors — plain class rules only, and colors
        // must be hex strings (Color.toString() is not valid CSS).
        val diffAddColor = colorToHex(ToolRenderers.SUCCESS_COLOR)
        val diffDelColor = colorToHex(ToolRenderers.FAIL_COLOR)
        ss.addRule(".diff-add { color: $diffAddColor; }")
        ss.addRule(".diff-del { color: $diffDelColor; }")
        ss.addRule("table { border-collapse: collapse; margin: 6px 0; width: 100%; }")
        ss.addRule("th { font-weight: bold; border-bottom: 2px solid $tblBorder; padding: 4px 8px; text-align: left; color: $mutedFg; }")
        ss.addRule("td { border-bottom: 1px solid $tblBorder; padding: 4px 8px; }")
        ss.addRule("h2 { font-size: ${basePt + 3}pt; font-weight: bold; margin: 10px 0 5px 0; border-bottom: 1px solid $tblBorder; padding-bottom: 3px; }")
        ss.addRule("h3 { font-size: ${basePt + 1}pt; font-weight: bold; margin: 8px 0 4px 0; }")
        ss.addRule("h4 { font-size: ${basePt}pt; font-weight: bold; margin: 6px 0 3px 0; }")
        ss.addRule("h5 { font-size: ${basePt}pt; font-weight: bold; margin: 4px 0 2px 0; }")
        ss.addRule("a { color: $link; }")
        ss.addRule("ul { margin: 4px 0; }")
        ss.addRule("ol { margin: 4px 0; }")
        ss.addRule("li { margin: 3px 0; }")
        ss.addRule("blockquote { border-left: 3px solid $tblBorder; background-color: $codeBg; margin: 6px 4px; padding: 2px; color: $mutedFg; }")
        ss.addRule("hr { border: none; border-top: 1px solid $tblBorder; margin: 8px 0; }")

        return ss
    }

    /**
     * Clears the cached rendered frame so the next paint re-renders. Needed after
     * code-block horizontal scroll changes: [contentVersion] only tracks content
     * edits, so without this the paint cache would keep blitting the frame from
     * before the scroll and the block would appear frozen.
     */
    private fun invalidatePaintCache() {
        paintCache = null
    }

    /**
     * Wheel routing: Shift+wheel over a code block scrolls it horizontally; every
     * other wheel event is forwarded to the enclosing scroll pane so the chat keeps
     * scrolling vertically. A plain wheel must never be trapped by a code block.
     */
    internal fun handleWheel(e: MouseWheelEvent) {
        if (e.isShiftDown) {
            val view = findScrollableCodeViewAt(e.point)
            if (view != null && view.isScrollableNow()) {
                e.consume()
                view.scroll((e.wheelRotation * JBUI.scale(15)).toInt())
                invalidatePaintCache()
                repaint()
                return
            }
        }
        // Registering any MouseWheelListener blocks automatic Swing propagation,
        // so forward vertical scroll events explicitly to the enclosing scroll pane.
        val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane
            ?: return
        val pt = SwingUtilities.convertPoint(this, e.point, sp)
        sp.dispatchEvent(
            MouseWheelEvent(
                sp, e.id, e.`when`, e.modifiersEx, pt.x, pt.y,
                e.xOnScreen, e.yOnScreen, e.clickCount, e.isPopupTrigger,
                e.scrollType, e.scrollAmount, e.wheelRotation, e.preciseWheelRotation
            )
        )
    }

    /** State of an in-progress drag on a code block's scrollbar strip. */
    private class StripDrag(
        val view: ScrollableCodeView,
        val grabOffsetInThumb: Int,
        val trackX: Int,
        val trackW: Int,
        val thumbW: Int,
        val maxScroll: Int,
    )

    private var stripDrag: StripDrag? = null
    private var stripCursorActive = false

    /**
     * Intercepts mouse events aimed at a code block's scrollbar strip BEFORE any
     * listener (including the caret's selection handlers) sees them, so dragging the
     * strip scrolls the block instead of selecting text. Events not aimed at a strip
     * pass through untouched.
     */
    override fun processMouseEvent(e: MouseEvent) {
        if (handleStripMouse(e)) return
        super.processMouseEvent(e)
    }

    override fun processMouseMotionEvent(e: MouseEvent) {
        if (handleStripMouse(e)) return
        if (e.id == MouseEvent.MOUSE_DRAGGED && SwingUtilities.isLeftMouseButton(e)) {
            handleSelectionAutoScroll(e)
        }
        super.processMouseMotionEvent(e)
    }

    /**
     * Auto-scrolls a code block horizontally while the user drags a text selection
     * inside it: dragging the selection past the block's right (or left) edge
     * scrolls the block along with the selection, the way editors do. Never
     * consumes the event; the caret still extends the selection, and because the
     * scroll is part of the view geometry (ScrollableCodeView.getOffset), the
     * selection endpoint maps to the text under the cursor. The further the cursor
     * is dragged beyond the edge, the further the block scrolls.
     */
    internal fun handleSelectionAutoScroll(e: MouseEvent) {
        val view = findScrollableCodeViewAt(e.point) ?: return
        if (!view.isScrollableNow()) return
        val r = view.allocationBounds() ?: return
        val margin = JBUI.scale(24)
        val contentX = (e.x - r.x) + view.currentScrollX()
        val target = when {
            e.x > r.x + r.width - margin -> contentX - (r.width - margin)
            e.x < r.x + margin -> contentX - margin
            else -> return
        }
        val clamped = target.coerceIn(0, view.maxScrollNow())
        if (clamped != view.currentScrollX()) {
            view.scrollTo(clamped)
            invalidatePaintCache()
            repaint()
        }
    }

    /** Returns true when the event was consumed by strip interaction. */
    internal fun handleStripMouse(e: MouseEvent): Boolean {
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> {
                if (!SwingUtilities.isLeftMouseButton(e)) return false
                val view = findScrollableCodeViewAt(e.point) ?: return false
                if (!view.isStripHit(e.point)) return false
                val geo = view.dragGeometry() ?: return false
                val grab = if (e.x >= geo.thumbX && e.x <= geo.thumbX + geo.thumbW) {
                    e.x - geo.thumbX // grabbed the thumb: keep the grab point under the cursor
                } else {
                    geo.thumbW / 2 // grabbed the track: center the thumb under the cursor
                }
                stripDrag = StripDrag(view, grab, geo.trackX, geo.trackW, geo.thumbW, geo.maxScroll)
                // A press jumps the thumb immediately, so a plain click on the track
                // scrolls; for a press on the thumb this is a no-op.
                val avail = (geo.trackW - geo.thumbW).coerceAtLeast(1)
                val x = (e.x - grab - geo.trackX).coerceIn(0, avail)
                view.scrollTo(((x.toFloat() / avail) * geo.maxScroll).toInt())
                invalidatePaintCache()
                repaint()
                e.consume()
                return true
            }
            MouseEvent.MOUSE_DRAGGED -> {
                val d = stripDrag ?: return false
                val avail = (d.trackW - d.thumbW).coerceAtLeast(1)
                val x = (e.x - d.grabOffsetInThumb - d.trackX).coerceIn(0, avail)
                d.view.scrollTo(((x.toFloat() / avail) * d.maxScroll).toInt())
                invalidatePaintCache()
                repaint()
                e.consume()
                return true
            }
            MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED -> {
                if (stripDrag == null) return false
                stripDrag = null
                e.consume()
                return true
            }
            MouseEvent.MOUSE_MOVED -> {
                // Hand cursor over the strip as the only visible affordance; restored
                // to default once the cursor leaves it. Never consumes the event.
                val hit = findScrollableCodeViewAt(e.point)?.isStripHit(e.point) == true
                if (hit && !stripCursorActive) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    stripCursorActive = true
                } else if (!hit && stripCursorActive) {
                    cursor = Cursor.getDefaultCursor()
                    stripCursorActive = false
                }
                return false
            }
        }
        return false
    }

    /** Returns the [ScrollableCodeView] whose allocated area contains [pt], or null. */
    private fun findScrollableCodeViewAt(pt: Point): ScrollableCodeView? {
        val ui = ui as? TextUI ?: return null

        @Suppress("DEPRECATION")
        val pos = ui.viewToModel(this, pt) // char offset under cursor
        return findScrollableCodeViewAt(ui.getRootView(this), pos)
    }

    private fun findScrollableCodeViewAt(view: View, pos: Int): ScrollableCodeView? {
        if (view is ScrollableCodeView && pos in view.startOffset until view.endOffset) return view
        for (i in 0 until view.viewCount) {
            val child = try {
                view.getView(i)
            } catch (_: Throwable) {
                continue
            }
            val found = findScrollableCodeViewAt(child, pos)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val RENDER_INTERVAL_MS = 30  // ~30fps cap; mirrors JCEF's rAF throttle

        /**
         * Width delta (px) within which [getPreferredSize] reuses the previously cached
         * height instead of performing a full HTML re-layout. Sized to absorb the small
         * per-event width changes Swing fires during a window resize drag or a tool-window
         * panel animation, while keeping any visual right-edge mismatch imperceptible.
         */
        private const val RESIZE_TOLERANCE_PX = 16

        /**
         * Window (ms) after the last fast-path [getPreferredSize] call before
         * we invalidate the cache and revalidate to obtain a pixel-accurate final layout.
         * Must be strictly greater than [ResizeBurstClock]'s burst window (200 ms)
         * so the settle timer always fires after the burst window has expired.
         */
        private const val RESIZE_SETTLE_MS = 250

        /**
         * Age threshold (ms): panes whose [lastContentChangeMs] is more recent than this
         * are considered "live" and always get an accurate HTML layout, even during a
         * resize burst. Panes older than this are frozen during the burst and revalidated
         * once it settles.
         */
        private const val LIVE_PANE_AGE_MS: Long = 1500

        private fun colorToHex(c: Color): String =
            "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }
}

/**
 * A [BlockView] for `<pre>` elements that clips to its allocated width and paints a
 * thin inline scrollbar when the content is wider than the allocation.
 *
 * **Why not JScrollPane**: wrapping the whole [NativeMarkdownPane] in a JScrollPane caused
 * a layout feedback loop (slow bubble-width expansion on hover). Per-block clipping keeps
 * the outer component unaware of the code block's natural width.
 */
private class ScrollableCodeView(elem: Element) : BlockView(elem, View.Y_AXIS) {

    /** Current horizontal scroll position in pixels. */
    var scrollX = 0
        private set

    /** Natural (unconstrained) content width — only valid after [setSize] has been called. */
    private var naturalWidth = 0f

    private var lastAllocWidth = 0f
    private var lastPaintBounds: Rectangle? = null

    companion object {
        private const val SCROLLBAR_H = 6  // px, thin track
        private const val STRIP_HIT_H = 14  // px, generous grab zone; the visual track stays 6px
        private const val SCROLLBAR_TRACK_ALPHA = 60
        private const val SCROLLBAR_THUMB_ALPHA = 140
    }

    /** Reports 0 so the outer pane never inflates to code-block natural width. */
    override fun getPreferredSpan(axis: Int): Float =
        if (axis == X_AXIS) 0f else super.getPreferredSpan(axis)

    /**
     * Reports 0 on the X axis for the same reason as [getPreferredSpan].
     * [BoxView.layoutMinorAxis] never allocates a child less than its minimum
     * span (spans = max(childMin, targetSpan)), and this view's children are
     * uncapped inline views whose minimum is the longest code line, so without
     * this override the minimum propagated up and the whole body laid out at
     * the code width.
     */
    override fun getMinimumSpan(axis: Int): Float =
        if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)

    /**
     * Lays out children at their natural width (calling `super.getPreferredSpan` bypasses
     * our override and returns the real content width). Resets [scrollX] if content changed.
     */
    override fun setSize(width: Float, height: Float) {
        val nw = super.getPreferredSpan(X_AXIS)
        if (nw != naturalWidth) {
            naturalWidth = nw
            scrollX = 0
        }
        super.setSize(nw, height)
    }

    /** Moves the horizontal scroll by [delta] pixels, clamped to valid range. */
    fun scroll(delta: Int) {
        val maxScroll = (naturalWidth - lastAllocWidth).coerceAtLeast(0f)
        scrollX = (scrollX + delta).coerceIn(0, maxScroll.toInt())
    }

    /** Absolute scroll position, clamped; used by strip dragging. */
    fun scrollTo(x: Int) {
        val maxScroll = (naturalWidth - lastAllocWidth).coerceAtLeast(0f)
        scrollX = x.coerceIn(0, maxScroll.toInt())
    }

    fun maxScrollNow(): Int = (naturalWidth - lastAllocWidth).coerceAtLeast(0f).toInt()

    fun isScrollableNow(): Boolean = naturalWidth > lastAllocWidth + 1f

    /** Component-space drag strip (taller than the 6px visual track for easy grabbing). */
    fun stripHitZone(): Rectangle? = lastPaintBounds?.let {
        Rectangle(it.x, it.y + it.height - JBUI.scale(STRIP_HIT_H), it.width, JBUI.scale(STRIP_HIT_H))
    }

    /** True when the component-space point lands on this view's drag strip. */
    fun isStripHit(pt: Point): Boolean = isScrollableNow() && stripHitZone()?.contains(pt) == true

    /** Current scroll position, for tests. */
    internal fun currentScrollX(): Int = scrollX

    /** Thumb/track geometry in component space; null when the block cannot scroll. */
    fun dragGeometry(): DragGeometry? {
        val r = lastPaintBounds ?: return null
        val maxScroll = maxScrollNow()
        if (maxScroll <= 0) return null
        val thumbFrac = (r.width / naturalWidth).coerceIn(0.05f, 1f)
        val thumbW = (r.width * thumbFrac).toInt()
            .coerceAtLeast(JBUI.scale(20))
            .coerceAtMost(r.width)
        val thumbX = r.x + ((scrollX.toFloat() / maxScroll) * (r.width - thumbW)).toInt()
        return DragGeometry(r.x, r.width, thumbW, thumbX, maxScroll)
    }

    class DragGeometry(val trackX: Int, val trackW: Int, val thumbW: Int, val thumbX: Int, val maxScroll: Int)

    /**
     * Shifts child positions left by the current scroll. All geometry consumers
     * (BoxView.paint, getChildAllocation for viewToModel/modelToView, caret and
     * selection painting) go through this method, so the scroll becomes part of
     * the view geometry: what is painted, what is hit-tested and what is selected
     * stay glued together. Without this, a selection inside a scrolled block would
     * map to the wrong characters (paint translated by the scroll, hit-testing did
     * not), and selection-driven auto-scroll would drift.
     */
    protected override fun getOffset(axis: Int, childIndex: Int): Int =
        if (axis == X_AXIS) super.getOffset(axis, childIndex) - scrollX
        else super.getOffset(axis, childIndex)

    /** Component-space allocation from the last paint, for hit testing. */
    fun allocationBounds(): Rectangle? = lastPaintBounds?.let { Rectangle(it) }

    override fun paint(g: Graphics, allocation: Shape) {
        val r = (allocation as? Rectangle) ?: allocation.bounds
        lastAllocWidth = r.width.toFloat()
        lastPaintBounds = Rectangle(r)

        // A pure widening resize does not change the natural width, so the
        // setSize() reset above will not have fired; re-clamp the scroll
        // against the new allocation and clear it entirely once the block no
        // longer overflows. Without this, a block scrolled before the resize
        // keeps shifting its children left with no scrollbar in sight.
        if (scrollX != 0) {
            scrollX = if (naturalWidth > r.width) scrollX.coerceAtMost(maxScrollNow()) else 0
        }

        val scrollable = naturalWidth > r.width

        val g2 = g as Graphics2D
        val savedClip = g2.clip

        // Clip to the allocated area; children paint at their scrolled positions
        // (see getOffset) and are clipped here, so no translate is needed.
        g2.setClip(r.x, r.y, r.width, r.height)
        super.paint(g2, r)

        g2.clip = savedClip

        // Overlay scrollbar on top — avoids cutting off the last line of code
        if (scrollable) paintScrollbar(g2, r)
    }

    private fun paintScrollbar(g2: Graphics2D, r: Rectangle) {
        val geo = dragGeometry() ?: return
        val trackY = r.y + r.height - JBUI.scale(SCROLLBAR_H)
        val trackH = JBUI.scale(SCROLLBAR_H)

        // Track
        g2.color = JBColor(Color(128, 128, 128, SCROLLBAR_TRACK_ALPHA), Color(200, 200, 200, SCROLLBAR_TRACK_ALPHA))
        g2.fillRect(geo.trackX, trackY, geo.trackW, trackH)

        // Thumb
        g2.color = JBColor(Color(128, 128, 128, SCROLLBAR_THUMB_ALPHA), Color(200, 200, 200, SCROLLBAR_THUMB_ALPHA))
        g2.fillRoundRect(geo.thumbX, trackY + 1, geo.thumbW, trackH - 2, trackH, trackH)
    }
}

/**
 * A text run that lets a paragraph shrink to the bubble even when the run holds one very long unbreakable
 * token (a path, URL, identifier or error string in an inline code span).
 *
 * Swing lays a paragraph out at `max(minimumSpan, availableWidth)`, and the minimum span of a text run is its
 * widest unbreakable chunk. So one long token made the whole paragraph wider than the bubble and the tail was
 * clipped, since the chat has no horizontal scroll. Capping the minimum at [MAX_WORD_EM] lets the run break
 * inside such a token (Swing then breaks it at the character that does not fit). The cap is not zero on purpose:
 * words up to that length keep their whole-word protection, so table columns are still sized around their
 * words and ordinary text lays out exactly as before.
 */
internal class WrappingInlineView(elem: Element) : InlineView(elem) {

    override fun getMinimumSpan(axis: Int): Float {
        val minimum = super.getMinimumSpan(axis)
        return if (axis == X_AXIS) minimum.coerceAtMost(font.size2D * MAX_WORD_EM) else minimum
    }

    companion object {
        /** Longest word, in font-size units (about 15 characters), that is never broken to make room. */
        const val MAX_WORD_EM = 8f
    }
}

/**
 * An [HTMLEditorKit] whose view factory creates [ScrollableCodeView] for `<pre>` elements and
 * [WrappingInlineView] for the text everywhere else. The body can shrink below its children's
 * minimum width so a wide table does not force surrounding paragraphs beyond the pane.
 */
internal class ScrollableHTMLEditorKit : HTMLEditorKit() {
    private val factory = object : HTMLFactory() {
        override fun create(elem: Element): View {
            val tag = tagOf(elem)
            if (tag == HTML.Tag.PRE) return ScrollableCodeView(elem)
            if (tag == HTML.Tag.BODY) return object : BlockView(elem, View.Y_AXIS) {
                // A table's minimum width must not widen sibling prose beyond the pane.
                override fun getMinimumSpan(axis: Int): Float =
                    if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)
            }
            // Code blocks keep their lines intact and scroll sideways instead.
            if (tag == HTML.Tag.CONTENT && !isInsideCodeBlock(elem)) return WrappingInlineView(elem)
            return super.create(elem)
        }
    }

    override fun getViewFactory(): ViewFactory = factory

    private fun tagOf(elem: Element): HTML.Tag? =
        elem.attributes?.getAttribute(StyleConstants.NameAttribute) as? HTML.Tag

    private fun isInsideCodeBlock(elem: Element): Boolean =
        generateSequence(elem.parentElement) { it.parentElement }.any { tagOf(it) == HTML.Tag.PRE }
}

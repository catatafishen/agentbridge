package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.LIVE_PANE_AGE_MS
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.RENDER_INTERVAL_MS
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.RESIZE_SETTLE_MS
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.lastResizeNanos
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.geom.AffineTransform
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.TextUI
import javax.swing.text.*
import javax.swing.text.html.BlockView
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
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

    private var lastContentChangeMs: Long = 0L

    /**
     * Single-shot timer used to recover an accurate layout after a burst of resize-driven
     * [getPreferredSize] calls returned a tolerance-matched stale height. Restarted on every
     * tolerance hit; fires once the resize burst has been quiet for [RESIZE_SETTLE_MS].
     * When it fires, the cache is invalidated and [revalidate] is called so the parent
     * re-queries at the actual current width and gets a fresh accurate measurement.
     */
    private val resizeSettleTimer = Timer(RESIZE_SETTLE_MS) {
        cachedForWidth = -1
        revalidate()
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

        addMouseWheelListener { e ->
            if (e.isShiftDown) {
                val view = findScrollableCodeViewAt(e.point) ?: return@addMouseWheelListener
                e.consume()
                view.scroll((e.wheelRotation * JBUI.scale(15)).toInt())
                repaint()
            }
        }

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
     * Computes the preferred height for the HTML content at the parent's available width.
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

        // Width changed since the last accurate measurement — record this as a tick in the
        // process-wide resize burst clock so OTHER panes can treat themselves as "during
        // resize" without each needing its own ComponentListener on the scroll viewport.
        // Skip the very first measurement (cachedForWidth < 0) to avoid mis-classifying
        // initial layout as a resize burst.
        if (cachedForWidth > 0 && pw != cachedForWidth) {
            lastResizeNanos = System.nanoTime()
        }

        // Frozen-during-drag cache hit: a resize burst is active AND this pane's content
        // has not changed in the last [LIVE_PANE_AGE_MS] ms (older history bubble). Skip
        // the HTML re-layout entirely for any width and return the previously cached height.
        if (cachedHeight > 0 &&
            contentVersion == cachedForVersion &&
            (System.nanoTime() - lastResizeNanos) < RESIZE_BURST_WINDOW_NANOS &&
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
         * Quiet-window (ms) after the last tolerance-matched [getPreferredSize] call before
         * we invalidate the cache and revalidate to obtain a pixel-accurate final layout.
         * Long enough to cover the gap between successive resize events; short enough to
         * feel instantaneous once the user releases the mouse.
         */
        private const val RESIZE_SETTLE_MS = 150

        /**
         * Window (ms) during which any pane that observed a width change "broadcasts"
         * an active resize burst via [lastResizeNanos]. Other panes that see [getPreferredSize]
         * calls within this window may take the frozen-during-drag fast path if they are
         * also stale per [LIVE_PANE_AGE_MS]. Slightly longer than [RESIZE_SETTLE_MS] so
         * the freeze decision and the settle-revalidate decision use overlapping windows.
         */
        private const val RESIZE_BURST_WINDOW_NANOS: Long = 200L * 1_000_000

        /**
         * Age threshold (ms): panes whose [lastContentChangeMs] is more recent than this
         * are considered "live" and always get an accurate HTML layout, even during a
         * resize burst. Panes older than this are frozen during the burst and revalidated
         * once it settles. Sized to comfortably cover the gap between two consecutive
         * agent message renders during normal streaming, so the actively-streaming bubble
         * is never frozen mid-stream while everything above it is.
         */
        private const val LIVE_PANE_AGE_MS: Long = 1500

        /**
         * Process-wide clock (nanoTime) of the last width-change observation across ALL
         * panes. Updated by any pane whose [getPreferredSize] sees a width different from
         * its own cached width; consulted by every pane to decide whether the user is
         * currently dragging the window / animating a side panel. Volatile because Swing
         * may call [getPreferredSize] from non-EDT validation paths in rare cases and we
         * want fresh reads without locking.
         */
        @Volatile
        private var lastResizeNanos: Long = 0L

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

    companion object {
        private const val SCROLLBAR_H = 6  // px — thin track
        private const val SCROLLBAR_TRACK_ALPHA = 60
        private const val SCROLLBAR_THUMB_ALPHA = 140
    }

    /** Reports 0 so the outer pane never inflates to code-block natural width. */
    override fun getPreferredSpan(axis: Int): Float =
        if (axis == X_AXIS) 0f else super.getPreferredSpan(axis)

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

    private var lastAllocWidth = 0f

    override fun paint(g: Graphics, allocation: Shape) {
        val r = (allocation as? Rectangle) ?: allocation.bounds
        lastAllocWidth = r.width.toFloat()

        val scrollable = naturalWidth > r.width

        val g2 = g as Graphics2D
        val savedTransform = g2.transform.clone() as AffineTransform
        val savedClip = g2.clip

        // Clip to allocated area and translate for horizontal scroll
        g2.setClip(r.x, r.y, r.width, r.height)
        g2.translate(-scrollX.toDouble(), 0.0)
        super.paint(g2, Rectangle(r.x, r.y, naturalWidth.toInt(), r.height))

        // Restore
        g2.transform = savedTransform
        g2.clip = savedClip

        // Overlay scrollbar on top — avoids cutting off the last line of code
        if (scrollable) paintScrollbar(g2, r)
    }

    private fun paintScrollbar(g2: Graphics2D, r: Rectangle) {
        val trackY = r.y + r.height - JBUI.scale(SCROLLBAR_H)
        val trackH = JBUI.scale(SCROLLBAR_H)
        val maxScroll = (naturalWidth - r.width).coerceAtLeast(1f)
        val thumbFrac = (r.width / naturalWidth).coerceIn(0.05f, 1f)
        val thumbW = (r.width * thumbFrac).toInt().coerceAtLeast(JBUI.scale(20))
        val thumbX = r.x + ((scrollX / maxScroll) * (r.width - thumbW)).toInt()

        // Track
        g2.color = JBColor(Color(128, 128, 128, SCROLLBAR_TRACK_ALPHA), Color(200, 200, 200, SCROLLBAR_TRACK_ALPHA))
        g2.fillRect(r.x, trackY, r.width, trackH)

        // Thumb
        g2.color = JBColor(Color(128, 128, 128, SCROLLBAR_THUMB_ALPHA), Color(200, 200, 200, SCROLLBAR_THUMB_ALPHA))
        g2.fillRoundRect(thumbX, trackY + 1, thumbW, trackH - 2, trackH, trackH)
    }
}

/** An [HTMLEditorKit] whose view factory creates [ScrollableCodeView] for `<pre>` elements. */
private class ScrollableHTMLEditorKit : HTMLEditorKit() {
    private val factory = object : HTMLFactory() {
        override fun create(elem: Element): View {
            val tag = elem.attributes?.getAttribute(StyleConstants.NameAttribute) as? HTML.Tag
            if (tag == HTML.Tag.PRE) return ScrollableCodeView(elem)
            return super.create(elem)
        }
    }

    override fun getViewFactory(): ViewFactory = factory
}

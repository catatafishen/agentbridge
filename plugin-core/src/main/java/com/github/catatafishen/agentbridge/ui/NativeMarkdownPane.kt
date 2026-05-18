package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.LARGE_CONTENT_LAYOUT_THRESHOLD
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane.Companion.RENDER_INTERVAL_MS
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.TextUI
import javax.swing.text.DefaultCaret
import javax.swing.text.View
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
 * The embedded [HTMLEditorKit] stylesheet is generated from the current IDE theme
 * colors so that code blocks, tables, headings, and links look correct in both
 * light and dark themes.
 */
class NativeMarkdownPane(private val fileNavigator: FileNavigator) : JEditorPane() {

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
     * Chars accumulated in the current visual line since the last explicit (`\n`) or
     * natural (char-count) line break. Used by [appendMarkdown] to cheaply detect
     * when a line wraps and grow [cachedHeight] by one line-height immediately,
     * without waiting for the [heightRevalidateTimer].
     */
    private var streamingLineChars = 0

    /**
     * Set to true when [heightRevalidateTimer] fires, forcing one accurate layout
     * computation after streaming pauses.
     */
    private var forceRecompute = false

    /**
     * Fired 500 ms after the last [renderNow] call. Forces one accurate [getPreferredSize]
     * computation once streaming has paused, so the bubble snaps to its correct final height
     * without blocking the EDT on every token during the stream.
     */
    private val heightRevalidateTimer = Timer(500) {
        forceRecompute = true
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

        val kit = HTMLEditorKit()
        kit.styleSheet = createStyleSheet()
        editorKit = kit

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
     *
     * **Incremental height**: before the render throttle fires, each incoming chunk is
     * scanned for visual line breaks (explicit `\n` and natural char-count wraps).
     * For each line added, [cachedHeight] is bumped by one [lineHeightEstimate] and
     * [revalidate] is called immediately — keeping the bubble growing in near-real-time
     * without a full HTML layout.
     */
    fun appendMarkdown(text: String) {
        val wasEmpty = rawText.isEmpty()
        rawText.append(text)
        if (wasEmpty) {
            // First token: always render immediately so the bubble appears at once.
            renderNow()
            return
        }
        // Grow the cached height by one line per visual line added (explicit \n or char-count
        // wrap). This avoids the 500 ms gap where new lines overflow the bubble without it growing.
        if (cachedHeight > 0) {
            val linesAdded = countAndAccumulateLinesAdded(text)
            if (linesAdded > 0) {
                cachedHeight += linesAdded * lineHeightEstimate()
                revalidate()
            }
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
        streamingLineChars = 0
        renderNow()
        // History replay: no streaming throttle — snap to accurate height immediately.
        heightRevalidateTimer.stop()
        forceRecompute = true
        revalidate()
    }

    /** Forces an immediate HTML render, resetting the throttle state. */
    fun renderNow() {
        renderTimer.stop()
        renderScheduled = false
        lastRenderTime = System.currentTimeMillis()
        contentVersion++
        heightRevalidateTimer.restart()
        val html = fileNavigator.markdownToHtml(rawText.toString())
        // Replace the stale document with a fresh empty one before setText(). When the existing
        // document's element tree is in a transient null state, AbstractDocument.handleRemove()
        // NPEs at Utilities.isComposedTextElement(null) because getCharacterElement(0) returns null.
        // A fresh document has length 0, so AbstractDocument.replace(0, 0, …) skips remove() entirely
        // (guarded by `if (length > 0)`) and goes straight to insertString().
        document = (editorKit as HTMLEditorKit).createDefaultDocument()
        text = "<html><body>$html</body></html>"
    }

    /**
     * Signals that this pane's streaming has ended. Renders any pending content, then
     * cancels the [heightRevalidateTimer] and immediately triggers one accurate height
     * layout — bypassing the 500 ms delay that exists to avoid expensive layouts during
     * in-flight streaming.
     *
     * Called from [com.github.catatafishen.agentbridge.ui.NativeChatPanel.finalizeTurn]
     * (markdown pane) and from [com.github.catatafishen.agentbridge.ui.NativeChatPanel.collapseThinking]
     * (thinking pane).
     */
    fun notifyStreamDone() {
        renderNow()
        heightRevalidateTimer.stop()
        forceRecompute = true
        revalidate()
    }

    /** Rebuilds the stylesheet when the IDE editor font size changes. */
    fun onFontSizeChanged() {
        rebuildStylesheet()
    }

    /** Stops the render timer and disconnects the color scheme subscription. */
    fun dispose() {
        renderTimer.stop()
        heightRevalidateTimer.stop()
        Disposer.dispose(schemeDisposable)
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
     * Computes the preferred height for the HTML content at the parent's available width.
     *
     * **Caching**: result is cached by `(parentWidth, contentVersion)`. Same width and
     * unchanged content → return immediately.
     *
     * **Streaming deferral**: during active streaming, [contentVersion] changes on every
     * [renderNow] call (~30 ms). The cache check above already prevents recomputing within
     * a single layout pass, but the first call per token would still trigger an expensive
     * layout. To avoid this, the method returns the stale cached height immediately while
     * streaming, and [heightRevalidateTimer] forces one accurate recompute 500 ms after
     * the last token. Confirmed necessary by freeze dumps:
     * threadDumps-freeze-20260517-084456, 084931, 20260518-090325, 134417, 171222, 171611.
     *
     * **Large-content guard**: for [rawText] beyond [LARGE_CONTENT_LAYOUT_THRESHOLD] chars,
     * the full HTML layout triggers O(N²) work in [BoxView.updateLayoutArray] that can
     * freeze the EDT for 30+ seconds even when only called once. A fast line-count estimate
     * is used instead; accuracy is within ~20 % which is acceptable for chat bubbles.
     */
    override fun getPreferredSize(): Dimension {
        val p = parent ?: return super.getPreferredSize()
        val ins = p.insets
        val pw = when {
            p.maximumSize.width in 1 until Short.MAX_VALUE.toInt() ->
                p.maximumSize.width - ins.left - ins.right

            p.width > 0 ->
                p.width - ins.left - ins.right

            else -> return super.getPreferredSize()
        }.takeIf { it > 0 } ?: return super.getPreferredSize()

        // Exact cache hit: same width and same content.
        if (pw == cachedForWidth && contentVersion == cachedForVersion) {
            return Dimension(pw, cachedHeight)
        }

        // Very large content: full HTML layout triggers O(N²) work in BoxView.updateLayoutArray
        // that causes 30+ second EDT freezes. Use a fast line-count estimate instead.
        // Apply the same streaming deferral as small content: don't change the estimated
        // height on every 30 ms render. A growing height → setBounds with new size →
        // componentResized → BasicTextUI.rootView.setSize() → O(N²) layout on every token.
        if (rawText.length > LARGE_CONTENT_LAYOUT_THRESHOLD) {
            if (cachedHeight > 0 && pw == cachedForWidth && !forceRecompute) {
                return Dimension(pw, cachedHeight)
            }
            val h = estimateHeightFromLineCount(pw)
            cachedForWidth = pw
            cachedForVersion = contentVersion
            cachedHeight = h
            forceRecompute = false
            return Dimension(pw, h)
        }

        // Stale cache (same width, content just changed): return immediately during streaming.
        // heightRevalidateTimer fires 500 ms after the last renderNow() and sets forceRecompute,
        // triggering one accurate layout after the stream ends.
        if (cachedHeight > 0 && pw == cachedForWidth && !forceRecompute) {
            return Dimension(pw, cachedHeight)
        }
        forceRecompute = false

        // Accurate layout for small/medium content (≤ LARGE_CONTENT_LAYOUT_THRESHOLD chars).
        // setSize() alone does not synchronously force the HTML view hierarchy to re-layout
        // at pw — views retain their previous allocation until the next paint.
        // Calling rootView.setSize() directly forces a layout pass at pw, so
        // getPreferredSpan(Y_AXIS) returns the correct height for the current content.
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
                // replaced the document while a layout pass was already in flight:
                //  - javax.swing.text.StateInvariantError (extends AssertionError):
                //    GlyphView detects stale element references.
                //  - ArrayIndexOutOfBoundsException (extends RuntimeException):
                //    BoxView.updateChildSizes finds its sizes array stale after the
                //    document was replaced but the view count changed.
                //  - NullPointerException: TextLayout not yet computed (GlyphPainter2).
                // Fall through to the fallback below. The next validation cycle will
                // have fresh views and produce the correct size.
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

    /**
     * Counts the number of visual lines added by [text] and updates [streamingLineChars].
     * Each explicit `\n` counts as one line. Each time [streamingLineChars] reaches
     * [charsPerLineEstimate], a natural word-wrap line is counted.
     */
    private fun countAndAccumulateLinesAdded(text: String): Int {
        val cpl = charsPerLineEstimate()
        var lines = 0
        for (ch in text) {
            if (ch == '\n') {
                streamingLineChars = 0
                lines++
            } else if (cpl > 0) {
                streamingLineChars++
                if (streamingLineChars >= cpl) {
                    streamingLineChars = 0
                    lines++
                }
            }
        }
        return lines
    }

    private fun charsPerLineEstimate(): Int {
        val pw = cachedForWidth.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: return 0
        val fontSize = UIUtil.getLabelFont().size
        val avgCharWidth = (fontSize * 0.55).toInt().coerceAtLeast(1)
        return maxOf(1, pw / avgCharWidth)
    }

    private fun lineHeightEstimate(): Int = (UIUtil.getLabelFont().size * 1.7).toInt()

    /**
     * Fast O(N) height estimate used when [rawText] exceeds [LARGE_CONTENT_LAYOUT_THRESHOLD].
     * Counts how many display lines each raw-markdown line would wrap into at the current
     * font size and panel width, then multiplies by an approximate line height.
     * Accuracy is within ~20 %; acceptable for chat bubbles in a scrollable panel.
     */
    private fun estimateHeightFromLineCount(pw: Int): Int {
        val cpl = maxOf(
            1,
            charsPerLineEstimate().takeIf { it > 0 } ?: (pw / ((UIUtil.getLabelFont().size * 0.55).toInt()
                .coerceAtLeast(1))))
        val lh = lineHeightEstimate()
        val lineCount = rawText.lines().sumOf { line -> maxOf(1, (line.length + cpl - 1) / cpl) }
        return ((lineCount + 2) * lh).coerceAtLeast(cachedHeight.coerceAtLeast(50))
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

    companion object {
        private const val RENDER_INTERVAL_MS = 30  // ~30fps cap; mirrors JCEF's rAF throttle

        /**
         * Raw markdown character threshold above which [getPreferredSize] switches from an
         * accurate HTML layout to a fast line-count estimate. Beyond this size, the full
         * HTML layout triggers O(N²) work in [BoxView.updateLayoutArray] that can freeze the
         * EDT for 30+ seconds. 8 000 chars ≈ 1 200–1 500 words, covering most AI responses.
         */
        private const val LARGE_CONTENT_LAYOUT_THRESHOLD = 8_000

        private fun colorToHex(c: Color): String =
            "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }
}

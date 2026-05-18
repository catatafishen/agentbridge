package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
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

    /** Timestamp (ms) of the most recent [renderNow] call; used by the streaming throttle. */
    private var lastRenderTime = 0L

    /** True while the render timer is scheduled within the current throttle window. */
    private var renderScheduled = false
    private val renderTimer = Timer(1) { renderNow() }.apply { isRepeats = false }
    private val schemeDisposable = Disposer.newDisposable("NativeMarkdownPane")

    /**
     * Version counter incremented on every [renderNow] / [setCompleteMarkdown]. Used together
     * with [cachedForWidth] to decide when [getPreferredSize] can skip the expensive
     * HTML re-layout.
     */
    private var contentVersion = 0
    private var cachedForWidth = -1
    private var cachedForVersion = -1
    private var cachedHeight = -1

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
        val html = fileNavigator.markdownToHtml(rawText.toString())
        text = "<html><body>$html</body></html>"
    }

    /** Rebuilds the stylesheet when the IDE editor font size changes. */
    fun onFontSizeChanged() {
        rebuildStylesheet()
    }

    /** Stops the render timer and disconnects the color scheme subscription. */
    fun dispose() {
        renderTimer.stop()
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

        if (pw == cachedForWidth && contentVersion == cachedForVersion) {
            return Dimension(pw, cachedHeight)
        }

        // setSize() alone does not synchronously force the HTML view hierarchy to
        // re-layout at pw — views retain their previous allocation until the next paint.
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

        private fun colorToHex(c: Color): String =
            "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }
}

package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import javax.swing.JEditorPane
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.TextUI
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * A [JEditorPane]-based component that renders streaming markdown as HTML.
 *
 * Markdown text is accumulated via [appendMarkdown] and converted to HTML using
 * [MarkdownRenderer.markdownToHtml] with file-link resolution from [FileNavigator].
 * Re-rendering is debounced (150 ms) during streaming to avoid excessive layout work.
 *
 * The embedded [HTMLEditorKit] stylesheet is generated from the current IDE theme
 * colors so that code blocks, tables, headings, and links look correct in both
 * light and dark themes.
 */
class NativeMarkdownPane(private val fileNavigator: FileNavigator) : JEditorPane() {

    private val rawText = StringBuilder()
    private val renderTimer = Timer(RENDER_DEBOUNCE_MS) { renderNow() }.apply { isRepeats = false }

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
        @Suppress("MagicConstant")
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)

        val kit = HTMLEditorKit()
        kit.styleSheet = createStyleSheet()
        editorKit = kit

        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                fileNavigator.handleFileLink(e.description)
            }
        }
    }

    /** Appends a chunk of raw markdown (streaming). A debounced render is scheduled. */
    fun appendMarkdown(text: String) {
        rawText.append(text)
        renderTimer.restart()
    }

    /** Returns all accumulated raw markdown text. */
    fun getRawText(): String = rawText.toString()

    /** Sets the full markdown text and renders immediately (for history replay). */
    fun setCompleteMarkdown(text: String) {
        rawText.setLength(0)
        rawText.append(text)
        renderNow()
    }

    /** Forces an immediate HTML render, cancelling any pending debounced render. */
    fun renderNow() {
        renderTimer.stop()
        contentVersion++
        val html = fileNavigator.markdownToHtml(rawText.toString())
        text = "<html><body>$html</body></html>"
    }

    /** Stops the render timer. Call when the parent panel is disposed. */
    fun dispose() {
        renderTimer.stop()
    }

    /**
     * The preferred width matches the parent container; the preferred height is
     * calculated from the HTML layout so that the parent layout allocates the
     * correct vertical space.
     *
     * Result is cached by (parent width, content version) to avoid repeating the expensive
     * HTML re-layout on every call. [BoxLayout.checkRequests] calls [getPreferredSize]
     * multiple times per layout pass; without the cache this caused 30+ second EDT freezes
     * on large AI responses (see threadDumps-freeze-* in IntelliJ logs).
     */
    override fun getPreferredSize(): Dimension {
        val pw = parent?.width?.takeIf { it > 0 } ?: return super.getPreferredSize()
        if (pw == cachedForWidth && contentVersion == cachedForVersion) {
            return Dimension(pw, cachedHeight)
        }
        val h = computePreferredHeight(pw)
        cachedForWidth = pw
        cachedForVersion = contentVersion
        cachedHeight = h
        return Dimension(pw, h)
    }

    /**
     * Computes the preferred height for a given width by performing the HTML layout.
     * Uses [TextUI.getRootView] directly to avoid the side effects of calling [setSize]
     * on the component itself (which would trigger another layout pass).
     */
    private fun computePreferredHeight(pw: Int): Int {
        val textUI = ui as? TextUI
        if (textUI != null) {
            val rootView = textUI.getRootView(this)
            try {
                rootView.setSize(pw.toFloat(), Short.MAX_VALUE.toFloat())
                return rootView.getPreferredSpan(View.Y_AXIS).toInt().coerceAtLeast(1)
            } catch (_: Throwable) {
                // BoxView/GlyphView can throw StateInvariantError (AssertionError) or
                // ArrayIndexOutOfBoundsException if renderNow() replaced the document while
                // a layout pass was already in flight. Fall through to the setSize fallback.
            }
        }
        setSize(pw, Short.MAX_VALUE.toInt())
        return super.getPreferredSize().height
    }

    private fun createStyleSheet(): StyleSheet {
        val ss = StyleSheet()

        val fg = colorToHex(UIUtil.getLabelForeground())
        val mutedFg = colorToHex(UIUtil.getContextHelpForeground())
        val codeBg = colorToHex(NativeChatColors.CODE_BG)
        val tblBorder = colorToHex(NativeChatColors.TABLE_BORDER)
        val link = colorToHex(NativeChatColors.LINK)
        val font = UIUtil.getLabelFont()
        val codeFontPt = (font.size * 0.92).toInt()

        ss.addRule("body { margin: 0; padding: 0; color: $fg; font-family: ${font.family}; font-size: ${font.size}pt; }")
        ss.addRule("p { margin: 2px 0; }")
        ss.addRule("code { background-color: $codeBg; font-family: monospace; font-size: ${codeFontPt}pt; }")
        ss.addRule("pre { background-color: $codeBg; padding: 8px 12px; border-left: 3px solid $tblBorder; margin: 6px 0; }")
        ss.addRule("pre code { background-color: transparent; padding: 0; }")
        ss.addRule("table { border-collapse: collapse; margin: 6px 0; width: 100%; }")
        ss.addRule("th { font-weight: bold; border-bottom: 2px solid $tblBorder; padding: 4px 8px; text-align: left; color: $mutedFg; }")
        ss.addRule("td { border-bottom: 1px solid $tblBorder; padding: 4px 8px; }")
        ss.addRule("h2 { font-size: ${font.size + 3}pt; font-weight: bold; margin: 10px 0 5px 0; border-bottom: 1px solid $tblBorder; padding-bottom: 3px; }")
        ss.addRule("h3 { font-size: ${font.size + 1}pt; font-weight: bold; margin: 8px 0 4px 0; }")
        ss.addRule("h4 { font-size: ${font.size}pt; font-weight: bold; margin: 6px 0 3px 0; }")
        ss.addRule("h5 { font-size: ${font.size}pt; font-weight: bold; margin: 4px 0 2px 0; }")
        ss.addRule("a { color: $link; }")
        ss.addRule("ul { margin: 4px 0; }")
        ss.addRule("ol { margin: 4px 0; }")
        ss.addRule("li { margin: 2px 0; }")
        ss.addRule("blockquote { border-left: 3px solid $tblBorder; margin: 6px 10px; color: $mutedFg; }")
        ss.addRule("hr { border: none; border-top: 1px solid $tblBorder; margin: 8px 0; }")
        ss.addRule("b { font-weight: bold; }")

        return ss
    }

    companion object {
        private const val RENDER_DEBOUNCE_MS = 150

        private fun colorToHex(c: Color): String =
            "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }
}

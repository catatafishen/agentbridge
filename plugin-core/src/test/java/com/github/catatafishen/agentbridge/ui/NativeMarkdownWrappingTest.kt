package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JEditorPane
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit

/**
 * Layout tests for issue #1139: a long unbreakable token (an inline code span holding a path, URL or
 * identifier) used to push its whole paragraph past the bubble width, where the chat, which cannot scroll
 * sideways, clipped it. These lay real HTML out with Swing's own engine and measure the lines.
 */
class NativeMarkdownWrappingTest {

    private val longPath =
        "/home/user/IdeaProjects/some-project/plugin-core/src/main/java/com/example/agentbridge/ui/NativeMarkdownPane.kt"

    private val width = 300

    private class Measure(val widestLine: Float, val height: Int, val lineCount: Int)

    private fun layout(kit: HTMLEditorKit, body: String, width: Int): View {
        val pane = JEditorPane()
        pane.contentType = "text/html"
        pane.editorKit = kit
        pane.text = "<html><body style='font-size:13pt'>$body</body></html>"
        val root = pane.ui.getRootView(pane)
        root.setSize(width.toFloat(), Short.MAX_VALUE.toFloat())
        return root
    }

    private fun collect(view: View, out: MutableList<View>, predicate: (View) -> Boolean) {
        if (predicate(view)) out += view
        for (i in 0 until view.viewCount) collect(view.getView(i), out, predicate)
    }

    private fun measure(kit: HTMLEditorKit, body: String, width: Int = this.width): Measure {
        val root = layout(kit, body, width)
        val rows = mutableListOf<View>()
        collect(root, rows) { it.javaClass.simpleName == "Row" }
        return Measure(
            widestLine = rows.maxOfOrNull { it.getPreferredSpan(View.X_AXIS) } ?: 0f,
            height = root.getPreferredSpan(View.Y_AXIS).toInt(),
            lineCount = rows.size,
        )
    }

    private fun paragraph(inner: String) = "<p>Intro text $inner and the end of the sentence.</p>"

    private fun code(text: String) = "<code>&#8239;$text&#8239;</code>"

    private val longTokens = mapOf(
        "a long path in an inline code span" to code(longPath),
        "an unbroken run of letters" to code("A".repeat(160)),
        "an underscored identifier" to code("some_very_long_identifier_name_without_any_spaces_or_slashes_anywhere_ok"),
        "a quoted error naming a long tool" to
            code("Error: No such tool available: mcp__agentbridge__read_file_that_does_not_exist while handling it"),
        "a long URL in plain prose" to
            "https://example.com/a/very/long/path/with/many/segments/and/query?x=1&amp;y=2&amp;token=abcdefghijklmnopqrstuvwxyz0123456789",
        "a link whose text is a long path" to "<a href='openfile://x'>$longPath</a>",
    )

    @Test
    fun `the plain Swing kit does overflow, so the measurement below means something`() {
        val plain = HTMLEditorKit()

        longTokens.forEach { (name, html) ->
            val m = measure(plain, paragraph(html))
            assertTrue(m.widestLine > width + 1, "$name should overflow the plain kit, widest line was ${m.widestLine}")
        }
    }

    @Test
    fun `a long unbreakable token wraps inside the width instead of widening its paragraph`() {
        val kit = ScrollableHTMLEditorKit()

        longTokens.forEach { (name, html) ->
            val m = measure(kit, paragraph(html))
            assertTrue(m.widestLine <= width + 1, "$name overflows: widest line ${m.widestLine} > $width")
        }
    }

    @Test
    fun `a token that cannot fit on one line is continued on the next lines, not cut off`() {
        val plain = measure(HTMLEditorKit(), paragraph(code("A".repeat(160))))
        val fixed = measure(ScrollableHTMLEditorKit(), paragraph(code("A".repeat(160))))

        assertTrue(fixed.lineCount > plain.lineCount, "the extra lines carry the rest of the token")
        assertTrue(fixed.height > plain.height)
    }

    @Test
    fun `ordinary prose is laid out exactly as before`() {
        val prose = paragraph("the quick brown fox jumps over the lazy dog and keeps running through the forest")

        listOf(120, 300, 900).forEach { w ->
            val plain = measure(HTMLEditorKit(), prose, w)
            val fixed = measure(ScrollableHTMLEditorKit(), prose, w)

            assertEquals(plain.lineCount, fixed.lineCount, "line count at width $w")
            assertEquals(plain.height, fixed.height, "height at width $w")
        }
    }

    @Test
    fun `words that fit are never broken, so short inline code is unchanged`() {
        val html = paragraph(code("configuration") + " and " + code("internationally"))

        val plain = measure(HTMLEditorKit(), html, 400)
        val fixed = measure(ScrollableHTMLEditorKit(), html, 400)

        assertEquals(plain.lineCount, fixed.lineCount)
        assertEquals(plain.height, fixed.height)
    }

    @Test
    fun `a wide layout is unchanged even when it holds a long token`() {
        val html = paragraph(code(longPath))

        val plain = measure(HTMLEditorKit(), html, 1200)
        val fixed = measure(ScrollableHTMLEditorKit(), html, 1200)

        assertEquals(plain.lineCount, fixed.lineCount)
        assertEquals(plain.height, fixed.height)
    }

    @Test
    fun `a table of ordinary words is sized exactly as before`() {
        val table = "<table><tr><th>Status</th><th>Tool</th><th>Description</th></tr>" +
            "<tr><td>Done</td><td>search</td><td>${"word ".repeat(40)}</td></tr></table>"

        val plain = measure(HTMLEditorKit(), table)
        val fixed = measure(ScrollableHTMLEditorKit(), table)

        assertEquals(plain.height, fixed.height, "a capped minimum must not squeeze columns that were fine")
        assertEquals(plain.lineCount, fixed.lineCount)
    }

    @Test
    fun `a long path in a table cell no longer forces the table wider than the bubble`() {
        val table = "<table><tr><th>File</th><th>Change</th></tr>" +
            "<tr><td>${code(longPath)}</td><td>edited</td></tr></table>"

        val tableMin = { kit: HTMLEditorKit ->
            val tables = mutableListOf<View>()
            collect(layout(kit, table, width), tables) { it.javaClass.simpleName == "TableView" }
            tables.first().getMinimumSpan(View.X_AXIS)
        }

        assertTrue(tableMin(HTMLEditorKit()) > width, "the plain kit's table cannot fit")
        assertTrue(tableMin(ScrollableHTMLEditorKit()) <= width, "the table now fits the bubble")
    }

    @Test
    fun `text inside a code block is left alone, so long lines keep scrolling instead of wrapping`() {
        val block = "<pre><code>$longPath</code></pre>"

        val narrow = layout(ScrollableHTMLEditorKit(), block, width)
        val wide = layout(ScrollableHTMLEditorKit(), block, 3000)
        val wrapped = mutableListOf<View>()
        collect(narrow, wrapped) { it is WrappingInlineView }

        assertTrue(wrapped.isEmpty(), "code block text must not use the wrapping view")
        assertEquals(
            wide.getPreferredSpan(View.Y_AXIS), narrow.getPreferredSpan(View.Y_AXIS),
            "a code block is the same height at any width: its lines are not wrapped",
        )
    }

    @Test
    fun `the cap on a word is a few characters wider than ordinary words but far below a path`() {
        // Guards the constant: ordinary words (up to ~15 characters at this size) are kept whole, a path is not.
        assertTrue(WrappingInlineView.MAX_WORD_EM in 6f..12f)
    }
}

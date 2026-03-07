package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.UIManager

/**
 * Renders a file-reference chip inline in the editor via [Inlay].
 *
 * Each chip is painted as a rounded rectangle with an icon character and the file name.
 * The associated [ContextItemData] is carried on the renderer so it can be collected at send time.
 */
class ContextChipRenderer(val contextData: ContextItemData) : EditorCustomElementRenderer {

    private companion object {
        private const val H_PAD = 6
        private const val ICON_GAP = 3
        private const val ARC = 8
        private const val SELECTION_ICON = "\u2702"
        private const val FILE_ICON = "\uD83D\uDCC4"
    }

    private val icon: String = if (contextData.isSelection) SELECTION_ICON else FILE_ICON
    private val label: String = contextData.name

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(chipFont(editor))
        return H_PAD + metrics.stringWidth(icon) + ICON_GAP + metrics.stringWidth(label) + H_PAD
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val bg = UIManager.getColor("ActionButton.hoverBackground")
                ?: JBColor(Color(0xDF, 0xE1, 0xE5), Color(0x35, 0x3B, 0x48))
            val fg = UIManager.getColor("Label.foreground") ?: JBColor.foreground()

            val y = targetRegion.y + 1
            val h = targetRegion.height - 2
            g2.color = bg
            g2.fill(
                RoundRectangle2D.Float(
                    targetRegion.x.toFloat(), y.toFloat(),
                    targetRegion.width.toFloat(), h.toFloat(),
                    ARC.toFloat(), ARC.toFloat()
                )
            )

            val font = chipFont(inlay.editor)
            g2.font = font
            g2.color = fg
            val metrics = g2.fontMetrics
            val textY = y + (h + metrics.ascent - metrics.descent) / 2

            var x = targetRegion.x + H_PAD
            g2.drawString(icon, x, textY)
            x += metrics.stringWidth(icon) + ICON_GAP
            g2.drawString(label, x, textY)
        } finally {
            g2.dispose()
        }
    }

    private fun chipFont(editor: com.intellij.openapi.editor.Editor): Font {
        val editorFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return editorFont.deriveFont(editorFont.size2D * 0.85f)
    }
}

/**
 * Data associated with an inline context chip.
 * Mirrors the fields of the old `ContextItem` but is a standalone data class
 * so the renderer can carry it without depending on the tool window's private types.
 */
data class ContextItemData(
    val path: String,
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val fileTypeName: String?,
    val isSelection: Boolean
)

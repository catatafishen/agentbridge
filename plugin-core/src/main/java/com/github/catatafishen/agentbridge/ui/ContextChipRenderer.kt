package com.github.catatafishen.agentbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.Icon
import javax.swing.UIManager

class ContextChipRenderer(val contextData: ContextItemData) : EditorCustomElementRenderer {

    private companion object {
        private const val H_PAD = 3
        private const val ICON_GAP = 3
    }

    private val label: String = contextData.name

    private val leadingIcon: Icon? = when (contextData.attachmentKind) {
        AttachmentKind.IMAGE -> AllIcons.FileTypes.Image
        AttachmentKind.BINARY -> AllIcons.FileTypes.Any_type
        AttachmentKind.TEXT -> null
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics =
            inlay.editor.contentComponent.getFontMetrics(inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val iconWidth = leadingIcon?.let { it.iconWidth + ICON_GAP } ?: 0
        return H_PAD + iconWidth + metrics.stringWidth(label) + H_PAD
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val linkColor = UIManager.getColor("Link.activeForeground")
                ?: UIManager.getColor("link.foreground")
                ?: JBColor(Color(0x58, 0x9D, 0xF6), Color(0x58, 0x9D, 0xF6))

            val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
            g2.font = font
            val metrics = g2.fontMetrics
            val textY = targetRegion.y + (targetRegion.height + metrics.ascent - metrics.descent) / 2

            var x = targetRegion.x + H_PAD
            val icon = leadingIcon
            if (icon != null) {
                val iconY = targetRegion.y + (targetRegion.height - icon.iconHeight) / 2
                icon.paintIcon(inlay.editor.contentComponent, g2, x, iconY)
                x += icon.iconWidth + ICON_GAP
            }

            g2.color = linkColor
            g2.drawString(label, x, textY)
        } finally {
            g2.dispose()
        }
    }
}

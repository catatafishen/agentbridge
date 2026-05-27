package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ToolPermission
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList

/**
 * A permission level offered in the Tools page combo boxes.
 *
 * Each option carries its own semantic color (green=Allow, amber=Ask, red=Deny)
 * so the combo is meaningful regardless of which tool kind owns it. The combo's
 * own background is **not** tinted by the tool kind — the tool card already
 * carries the kind color via [ToolChipCard].
 */
enum class PermOption(val label: String, val color: JBColor, val permission: ToolPermission) {
    ALLOW("Allow", JBColor(Color(0x3F, 0x84, 0x3F), Color(120, 180, 120)), ToolPermission.ALLOW),
    ASK("Ask", JBColor(Color(0xB5, 0x86, 0x00), Color(220, 175, 70)), ToolPermission.ASK),
    DENY("Deny", JBColor(Color(0xC0, 0x3F, 0x3F), Color(225, 110, 110)), ToolPermission.DENY);

    companion object {
        @JvmField
        val ALLOW_ASK: Array<PermOption> = arrayOf(ALLOW, ASK)

        @JvmField
        val ALL: Array<PermOption> = arrayOf(ALLOW, ASK, DENY)

        @JvmStatic
        fun forPermission(p: ToolPermission): PermOption = when (p) {
            ToolPermission.ALLOW -> ALLOW
            ToolPermission.ASK -> ASK
            ToolPermission.DENY -> DENY
        }
    }
}

/**
 * Combo box for picking a [PermOption]. Renders each item with a small colored
 * swatch (green / amber / red). When the combo itself is disabled (because the
 * owning tool is unchecked), the **selected-item** display text is replaced
 * with a grey "Disabled" label instead of the underlying permission — making
 * the disabled state instantly obvious without losing the stored value.
 */
class PermissionComboBox(options: Array<PermOption>) : ComboBox<PermOption>(options) {

    init {
        renderer = PermRenderer(this)
        setMinimumAndPreferredWidth(JBUI.scale(112))
    }

    // Cap the max size to the preferred size so BoxLayout parents (Quick
    // Permissions row, outside-project row) can't stretch the combo to fill
    // available horizontal space — every PermissionComboBox in the panel ends
    // up the same visual width as the per-tool Permission combos.
    override fun getMaximumSize(): Dimension = preferredSize

    /** Set the selection to match a [ToolPermission]. */
    fun selectPermission(p: ToolPermission) {
        selectedItem = PermOption.forPermission(p)
    }

    /** The currently selected [ToolPermission], defaulting to ALLOW. */
    fun selectedPermission(): ToolPermission =
        (selectedItem as? PermOption)?.permission ?: ToolPermission.ALLOW

    private class PermRenderer(private val combo: PermissionComboBox) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val label = super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus
            ) as JLabel

            // index == -1 means "render the currently selected item in the combo button"
            if (index == -1 && !combo.isEnabled) {
                label.text = "Disabled"
                label.icon = SwatchIcon(JBColor.GRAY)
                label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                return label
            }

            val opt = value as? PermOption
            if (opt != null) {
                label.text = opt.label
                label.icon = SwatchIcon(opt.color)
            }
            return label
        }
    }

    private class SwatchIcon(private val color: JBColor) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val size = JBUI.scale(SIZE)
                g2.color = color
                g2.fillRoundRect(x, y + JBUI.scale(1), size, size - JBUI.scale(2), JBUI.scale(4), JBUI.scale(4))
                g2.color = color.darker()
                g2.drawRoundRect(x, y + JBUI.scale(1), size, size - JBUI.scale(2), JBUI.scale(4), JBUI.scale(4))
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = JBUI.scale(SIZE) + JBUI.scale(4)
        override fun getIconHeight(): Int = JBUI.scale(SIZE)

        companion object {
            private const val SIZE = 10
        }
    }
}

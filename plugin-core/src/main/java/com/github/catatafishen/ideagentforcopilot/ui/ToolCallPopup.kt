package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import javax.swing.*

internal object ToolCallPopup {

    private var currentPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    private val KIND_COLORS = mapOf(
        "read" to JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185)),
        "edit" to JBColor(Color(0xA0, 0x7A, 0x3A), Color(205, 155, 95)),
        "execute" to JBColor(Color(0x4A, 0x90, 0x4A), Color(130, 190, 130)),
        "search" to JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185)),
        "think" to JBColor(Color(0x7A, 0x70, 0xA8), Color(170, 155, 210)),
        "other" to JBColor(Color(0x78, 0x7C, 0x80), Color(160, 165, 170)),
    )

    private fun popupWidth() = JBUI.scale(650)
    private fun popupHeight() = JBUI.scale(420)

    /**
     * Shows a popup with tool result and optional parameters.
     *
     * @param kind            tool kind ("read", "edit", "execute", etc.) — drives the background tint
     * @param paramsPanel     Swing component for the parameters section, or null to omit
     * @param resultPanel     Swing component for the result section
     * @param toolDescription optional one-liner description for MCP tools, shown at the top of the popup
     */
    fun show(
        project: Project,
        title: String,
        kind: String,
        paramsPanel: JComponent?,
        resultPanel: JComponent,
        toolDescription: String? = null,
    ) {
        currentPopup?.cancel()

        val kindColor = KIND_COLORS[kind] ?: KIND_COLORS["other"]!!
        val panelBg = UIUtil.getPanelBackground()
        val tintedBg = ToolRenderers.blendColor(kindColor, panelBg, 0.07)

        val contentPanel = buildContentPanel(tintedBg, resultPanel, paramsPanel, toolDescription)

        val width = popupWidth()
        val height = popupHeight()

        val scrollPane = JBScrollPane(
            contentPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            preferredSize = Dimension(width, height)
            border = JBUI.Borders.empty()
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, scrollPane)
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUI.scale(350), JBUI.scale(180)))
            .createPopup()
        currentPopup = popup

        val frame = WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            val rootPane = frame.rootPane
            val relPoint = com.intellij.ui.awt.RelativePoint(
                rootPane,
                java.awt.Point(
                    (rootPane.width - width) / 2,
                    (rootPane.height - height) / 2,
                )
            )
            popup.show(relPoint)
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun buildContentPanel(
        bg: Color,
        resultPanel: JComponent,
        paramsPanel: JComponent?,
        toolDescription: String? = null,
    ): JBPanel<JBPanel<*>> {
        val panel = object : JBPanel<JBPanel<*>>(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) =
                16

            override fun getScrollableBlockIncrement(
                visibleRect: java.awt.Rectangle,
                orientation: Int,
                direction: Int,
            ) = height

            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            border = JBUI.Borders.empty(8, 12)
        }

        if (toolDescription != null) {
            val descLabel = JBLabel("<html><body style='width:580px'>$toolDescription</body></html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(0, 0, 6, 0)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            panel.add(descLabel)
            panel.add(JSeparator().apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            })
            panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        if (paramsPanel != null) {
            panel.add(sectionLabel("Parameters"))
            paramsPanel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(paramsPanel)
            panel.add(Box.createVerticalStrut(JBUI.scale(6)))
            panel.add(JSeparator().apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            })
        }

        panel.add(sectionLabel("Result"))
        resultPanel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(resultPanel)

        panel.add(Box.createVerticalGlue())

        return panel
    }

    private fun sectionLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont().asBold()
            border = JBUI.Borders.empty(4, 0, 6, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }
}

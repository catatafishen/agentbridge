package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers
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

    private fun kindColorFor(kind: String, project: Project): JBColor {
        val settings = McpServerSettings.getInstance(project)
        return when (kind.lowercase()) {
            "read", "file", "git_read" -> ToolKindColors.readColor(settings)
            "search" -> ToolKindColors.searchColor(settings)
            "edit", "delete", "move", "write", "git_write" -> ToolKindColors.editColor(settings)
            "execute", "run", "terminal", "shell" -> ToolKindColors.executeColor(settings)
            else -> ChatTheme.THINK_COLOR
        }
    }

    data class Request(
        val project: Project,
        val title: String,
        val kind: String,
        val toolName: String,
        val paramsJson: String?,
        val resultPanel: JComponent,
        val toolDescription: String? = null,
        val autoDenied: Boolean = false,
        val denialReason: String? = null,
        val failed: Boolean = false
    )

    private fun popupWidth() = JBUI.scale(650)
    private fun popupHeight() = JBUI.scale(420)

    fun show(request: Request) {
        currentPopup?.cancel()

        val kindColor = kindColorFor(request.kind, request.project)
        val panelBg = UIUtil.getPanelBackground()
        val tintedBg = ToolRenderers.blendColor(kindColor, panelBg, 0.07)

        val contentPanel = buildContentPanel(
            tintedBg,
            request.toolName,
            request.paramsJson,
            request.project,
            request.resultPanel,
            request.toolDescription,
            request.autoDenied,
            request.denialReason,
            request.failed
        )

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
            .setTitle(request.title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUI.scale(350), JBUI.scale(180)))
            .createPopup()
        currentPopup = popup

        val frame = WindowManager.getInstance().getFrame(request.project)
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
        toolName: String,
        paramsJson: String?,
        project: Project,
        resultPanel: JComponent,
        toolDescription: String? = null,
        autoDenied: Boolean = false,
        denialReason: String? = null,
        failed: Boolean = false,
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

        if (autoDenied) {
            val denialPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = bg
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.RED, 1),
                    JBUI.Borders.empty(8)
                )
                alignmentX = JComponent.LEFT_ALIGNMENT

                add(JBLabel("Tool call was automatically denied by the plugin.").apply {
                    foreground = JBColor.RED
                    font = JBUI.Fonts.label().asBold()
                    alignmentX = JComponent.LEFT_ALIGNMENT
                })
                if (denialReason != null) {
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(JBLabel("<html><body style='width:580px'>Reason: $denialReason</body></html>").apply {
                        foreground = JBColor.RED
                        alignmentX = JComponent.LEFT_ALIGNMENT
                    })
                }
            }
            panel.add(denialPanel)
            panel.add(Box.createVerticalStrut(JBUI.scale(12)))
        }

        if (toolDescription != null) {
            val descLabel = JBLabel("<html><body style='width:580px'>$toolDescription</body></html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(6)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            panel.add(descLabel)
            panel.add(JSeparator().apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            })
            panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        val paramsComponent = ToolCallParamsPanel.build(toolName, paramsJson, project, bg)
        paramsComponent.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(paramsComponent)
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(JSeparator().apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })

        panel.add(sectionLabel(if (failed) "Error" else "Result"))
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

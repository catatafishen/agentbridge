package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.hooks.DefaultHookProvisioner
import com.github.catatafishen.agentbridge.services.hooks.HookHashRegistry
import com.github.catatafishen.agentbridge.services.hooks.HookRegistry
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Displays the status of each managed hook file and lets the user revert individual files
 * to their bundled plugin defaults.
 *
 * Each row shows the relative filename, a colored status badge (Up to date / Plugin version /
 * Modified / Missing / Unknown), and an optional Revert button for files that can be restored.
 *
 * Call [refresh] after any operation that modifies hook files.
 */
class HookFileStatusPanel(private val project: Project) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        refresh()
    }

    /** Reloads all file statuses from disk and repaints the panel. */
    fun refresh() {
        removeAll()
        val hooksDir = DefaultHookProvisioner.resolveHooksDir(project)
        val storedHashes = HookHashRegistry.load(hooksDir)
        val bundledHashes = HookHashRegistry.loadBundledHashes()

        bundledHashes.keys
            .filter { !it.endsWith(".history") }
            .sorted()
            .forEach { filename ->
                val status = HookHashRegistry.computeFileStatus(filename, hooksDir, storedHashes, bundledHashes)
                add(buildRow(filename, status))
            }

        revalidate()
        repaint()
    }

    private fun buildRow(filename: String, status: HookHashRegistry.FileStatus): JPanel {
        val row = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        row.add(JBLabel(filename).apply {
            font = JBUI.Fonts.smallFont()
            preferredSize = Dimension(JBUI.scale(280), preferredSize.height)
            minimumSize = preferredSize
            maximumSize = Dimension(JBUI.scale(280), Short.MAX_VALUE.toInt())
        })
        row.add(Box.createHorizontalStrut(JBUI.scale(8)))
        row.add(JBLabel(status.label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = statusColor(status)
            preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
            minimumSize = preferredSize
            maximumSize = Dimension(JBUI.scale(120), Short.MAX_VALUE.toInt())
        })

        if (status.canRevert()) {
            row.add(Box.createHorizontalStrut(JBUI.scale(8)))
            row.add(JButton("Revert").apply {
                font = JBUI.Fonts.smallFont()
                addActionListener {
                    if (DefaultHookProvisioner.revertFile(project, filename)) {
                        HookRegistry.getInstance(project).reload()
                        refresh()
                    }
                }
            })
        }

        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun statusColor(status: HookHashRegistry.FileStatus) = when (status) {
        HookHashRegistry.FileStatus.UP_TO_DATE -> JBColor(0x3d8350, 0x499c54)
        HookHashRegistry.FileStatus.OFFICIAL -> UIUtil.getLabelForeground()
        HookHashRegistry.FileStatus.MODIFIED -> JBColor(0xc77b2b, 0xe8a54b)
        HookHashRegistry.FileStatus.MISSING -> JBColor.RED
        HookHashRegistry.FileStatus.UNKNOWN -> UIUtil.getContextHelpForeground()
    }
}

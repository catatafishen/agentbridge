package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.ui.PermissionsPanel
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import javax.swing.JComponent

class ToolPermissionsConfigurable(private val project: Project) : SearchableConfigurable {

    private var permissionsPanel: PermissionsPanel? = null

    override fun getId(): String = "com.github.catatafishen.agentbridge.tool-permissions"
    override fun getDisplayName(): String = "Tool Permissions"

    override fun createComponent(): JComponent {
        val settings = ActiveAgentManager.getInstance(project).settings
        val panel = PermissionsPanel(
            settings,
            ToolRegistry.getInstance(project),
            McpServerSettings.getInstance(project)
        )
        permissionsPanel = panel
        return panel.component
    }

    override fun isModified(): Boolean = permissionsPanel?.isModified == true
    override fun apply() { permissionsPanel?.save() }
    override fun reset() { permissionsPanel?.reload() }
    override fun disposeUIResources() { permissionsPanel = null }
}

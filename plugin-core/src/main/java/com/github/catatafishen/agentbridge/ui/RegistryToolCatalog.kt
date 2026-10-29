package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings
import com.github.catatafishen.agentbridge.services.ToolPermission
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project

/** [ToolCatalog] backed by the live [ToolRegistry] and the user's tool permission settings. */
class RegistryToolCatalog(private val project: Project) : ToolCatalog {
    private val registry get() = ToolRegistry.getInstance(project)

    override fun inputSchema(toolName: String): JsonObject? =
        registry.findById(toolName)?.takeIf { it.hasExecutionHandler() }?.inputSchema()

    override fun allToolNames(): Collection<String> = registry.allTools.map { it.id() }

    override fun isDenied(toolName: String): Boolean =
        ToolLayerSettings.getInstance(project).getToolPermission(toolName) == ToolPermission.DENY
}

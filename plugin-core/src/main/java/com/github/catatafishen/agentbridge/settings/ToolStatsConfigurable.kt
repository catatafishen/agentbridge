package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * Settings page controlling the tool-call statistics database. Lives under
 * the **Storage** parent page; the underlying file location is governed by
 * the shared storage root configured there.
 *
 * Built with the official IntelliJ Platform Kotlin UI DSL v2.
 */
class ToolStatsConfigurable :
    BoundConfigurable("Tool Statistics"),
    SearchableConfigurable {

    private val settings: AgentBridgeStorageSettings = AgentBridgeStorageSettings.getInstance()

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row {
            checkBox("Record tool call statistics")
                .bindSelected(settings::isToolStatsEnabled, settings::setToolStatsEnabled)
                .comment(
                    "When enabled, every MCP tool call is logged to a per-project SQLite " +
                        "database and surfaced in the Tool Statistics and Session Stats panels. " +
                        "Disable to skip recording entirely (no data is collected)."
                )
        }
        separator()
        row {
            comment(
                "The database file is stored under the storage root configured on the " +
                    "parent <b>Storage</b> page."
            )
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.storage.toolStats"
    }
}

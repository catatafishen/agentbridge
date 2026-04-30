package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Root settings page for AgentBridge storage. Holds the shared storage root
 * directory; child pages (Tool Statistics, Memory, Chat History) configure
 * specific stores below it.
 *
 * Built with the official IntelliJ Platform Kotlin UI DSL v2 (BoundConfigurable).
 */
class AgentBridgeStorageConfigurable :
    BoundConfigurable("Storage"),
    SearchableConfigurable {

    private val settings = AgentBridgeStorageSettings.getInstance()

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row {
            comment(
                "Configure where AgentBridge stores per-project data files such as " +
                    "tool-call statistics and semantic memory."
            )
        }
        separator()
        row("Storage root:") {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select AgentBridge Storage Directory")
                .withDescription(
                    "AgentBridge per-project data files such as tool-call statistics and " +
                        "semantic memory will be stored under this directory."
                )
            val field = TextFieldWithBrowseButton().apply {
                @Suppress("DEPRECATION")
                addBrowseFolderListener(TextBrowseFolderListener(descriptor))
                textField.let { tf ->
                    if (tf is com.intellij.ui.components.JBTextField) {
                        tf.emptyText.text = AgentBridgeStorageSettings.getDefaultStorageRoot().toString()
                    }
                }
            }
            val cellRef = cell(field)
                .align(AlignX.FILL)
                .resizableColumn()
                .gap(RightGap.SMALL)
                .bindText(
                    { settings.customStorageRoot ?: "" },
                    { settings.customStorageRoot = it.trim().ifEmpty { null } }
                )
            button("Reset to Default") { cellRef.component.text = "" }
        }
        row {
            comment(
                "Default: <code>${AgentBridgeStorageSettings.getDefaultStorageRoot()}</code><br/>" +
                    "Per-project data lives under " +
                    "<code>&lt;root&gt;/projects/&lt;project-name&gt;-&lt;hash&gt;/</code>."
            )
        }
        separator()
        row {
            comment(
                "<b>Note:</b> if legacy data exists under " +
                    "<code>{project}/.agentbridge/tool-stats.db</code> or " +
                    "<code>{project}/.agent-work/memory/</code>, it is moved to the new " +
                    "location automatically on first use. Changing the storage root takes " +
                    "effect on the next IDE restart."
            )
        }
        separator()
        row {
            comment(
                "Configure individual stores below: <b>Tool Statistics</b>, " +
                    "<b>Memory</b>, and <b>Chat History</b>."
            )
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.storage"
    }
}

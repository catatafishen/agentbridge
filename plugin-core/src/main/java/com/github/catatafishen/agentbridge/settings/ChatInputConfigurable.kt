package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.CleanupSettings
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.ChatToolWindowContent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.dsl.builder.*

class ChatInputConfigurable(private val project: Project) :
    BoundConfigurable("UI/UX"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.ui-ux"

    private val s get() = ChatInputSettings.getInstance()
    private val mcp get() = McpServerSettings.getInstance(project)
    private val cleanup get() = CleanupSettings.getInstance(project)

    @Suppress("LongMethod", "kotlin:S3776")
    override fun createPanel() = panel {
        row {
            comment(
                "Appearance and interaction settings for the chat panel, input area, and editor integration."
            )
        }
        separator()
        row {
            checkBox("Show keyboard shortcut hints in chat input")
                .comment("Display shortcut hints centered inside the input area when it is empty.")
                .bindSelected({ s.isShowShortcutHints }, { s.isShowShortcutHints = it })
        }
        row {
            val link = LinkLabel<Unit>(
                "Customize keyboard shortcuts…", null
            ) { _, _ ->
                ApplicationManager.getApplication().invokeLater {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        { c -> c is SearchableConfigurable && "preferences.keymap" == c.id },
                        { c -> if (c is SearchableConfigurable) c.enableSearch("AgentBridge")?.run() }
                    )
                }
            }
            cell(link)
        }
        separator()
        row {
            checkBox("Enable soft wraps in chat input")
                .comment("Wrap long lines in the chat input instead of scrolling horizontally.")
                .bindSelected({ s.isSoftWrapsEnabled }, { s.isSoftWrapsEnabled = it })
        }
        separator()
        lateinit var smartPaste: Cell<JBCheckBox>
        row {
            smartPaste = checkBox("Enable smart paste")
                .comment("Intercept large clipboard pastes to create scratch files or inline file references.")
                .bindSelected({ s.isSmartPasteEnabled }, { s.isSmartPasteEnabled = it })
        }
        row("Min lines to trigger:") {
            spinner(1..100, 1)
                .comment("Clipboard content with more lines than this triggers Smart Paste.")
                .bindIntValue({ s.smartPasteMinLines }, { s.smartPasteMinLines = it })
                .enabledIf(smartPaste.selected)
        }
        row("Min characters to trigger:") {
            spinner(50..10_000, 50)
                .comment("Clipboard content with more characters than this triggers Smart Paste.")
                .bindIntValue({ s.smartPasteMinChars }, { s.smartPasteMinChars = it })
                .enabledIf(smartPaste.selected)
        }
        separator()
        row("File search trigger:") {
            comboBox(listOf("#", "@", ""))
                .comment("Character that opens the file search popup in the chat input.")
                .applyToComponent {
                    renderer = com.intellij.ui.SimpleListCellRenderer.create<String>("") { value ->
                        when (value) {
                            "#" -> "# (VS Code style)"
                            "@" -> "@ (AI Assistant style)"
                            else -> "Disabled"
                        }
                    }
                }
                .bindItem({ s.fileSearchTrigger }, { s.fileSearchTrigger = it ?: "#" })
        }
        separator()
        row {
            checkBox(
                "Follow Agent — open files and highlight regions as the agent reads or edits them"
            )
                .comment(
                    "Works independently of the connected agent — any external agent accessing " +
                        "the MCP server will trigger follow-mode when this is enabled."
                )
                .bindSelected(
                    { ActiveAgentManager.getFollowAgentFiles(project) },
                    { ActiveAgentManager.setFollowAgentFiles(project, it) }
                )
        }
        row {
            checkBox("Enable smooth scrolling in chat panel")
                .comment("⚠ May cause screen tearing on some systems")
                .bindSelected({ mcp.isSmoothScrollEnabled }, {
                    mcp.isSmoothScrollEnabled = it
                    ChatConsolePanel.getInstance(project)?.setSmoothScroll(it)
                })
        }
        row {
            checkBox("Show turn stats below messages (duration, tokens, lines changed)")
                .comment(
                    "Displays a summary footer below the last message of each agent turn. " +
                        "Disabling saves vertical space."
                )
                .bindSelected({ mcp.isShowTurnStats }, {
                    mcp.isShowTurnStats = it
                    ChatConsolePanel.getInstance(project)?.setShowTurnStats(it)
                })
        }
        separator()
        row("Scratch file retention (hours, 0 = forever):") {
            spinner(0..8760, 1)
                .bindIntValue(
                    { cleanup.scratchRetentionHours },
                    { cleanup.scratchRetentionHours = it }
                )
        }
        lateinit var autoCloseTabs: Cell<JBCheckBox>
        row {
            autoCloseTabs = checkBox("Auto-close agent tabs between turns")
                .bindSelected({ cleanup.isAutoCloseAgentTabs }, { cleanup.isAutoCloseAgentTabs = it })
        }
        row {
            checkBox("Also close running terminal tabs")
                .bindSelected(
                    { cleanup.isAutoCloseRunningTerminals },
                    { cleanup.isAutoCloseRunningTerminals = it }
                )
                .enabledIf(autoCloseTabs.selected)
        }
        separator()
        row("When the agent finishes before you act on a nudge:") {
            comboBox(ChatInputSettings.UnhandledNudgeMode.entries.toList())
                .comment(
                    "\"Restore into chat input\" prepends the unsent nudge to the input area instead of firing it."
                )
                .applyToComponent {
                    renderer =
                        com.intellij.ui.SimpleListCellRenderer.create<ChatInputSettings.UnhandledNudgeMode>("") { value ->
                            when (value) {
                                ChatInputSettings.UnhandledNudgeMode.AUTO_SEND -> "Auto-send as a new prompt"
                                ChatInputSettings.UnhandledNudgeMode.RESTORE_INTO_INPUT -> "Restore into chat input"
                            }
                        }
                }
                .bindItem(
                    { s.unhandledNudgeMode },
                    { s.unhandledNudgeMode = it ?: ChatInputSettings.UnhandledNudgeMode.AUTO_SEND }
                )
        }
        onApply {
            val chatContent = ChatToolWindowContent.getInstance(project)
            chatContent?.setSoftWrapsEnabled(s.isSoftWrapsEnabled)
            chatContent?.setShortcutHintsVisible()
        }
    }
}

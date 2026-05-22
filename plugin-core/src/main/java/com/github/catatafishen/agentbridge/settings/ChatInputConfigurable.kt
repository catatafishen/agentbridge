package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.CleanupSettings
import com.github.catatafishen.agentbridge.ui.ChatToolWindowContent
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.*

class ChatInputConfigurable(private val project: Project) :
    BoundConfigurable("UI/UX"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.ui-ux"

    private val s get() = ChatInputSettings.getInstance()
    private val mcp get() = McpServerSettings.getInstance(project)
    private val cleanup get() = CleanupSettings.getInstance(project)

    // Captured on panel creation; used to get DataContext for settings navigation.
    private var keymapLink: ActionLink? = null

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
            keymapLink = link("Customize keyboard shortcuts…") {
                val comp = keymapLink
                val navigated =
                    comp != null && PlatformApiCompat.navigateInOpenSettingsDialog(comp, "preferences.keymap")
                if (!navigated) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Keymap")
                }
            }.component
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
                    // UNCHECKED_CAST: ComboBoxWithWidePopup's E! platform type requires an explicit
                    // cast; the renderer is correct at runtime.
                    @Suppress("UNCHECKED_CAST")
                    renderer = object : com.intellij.ui.SimpleListCellRenderer<String>() {
                        override fun customize(
                            list: javax.swing.JList<out String>,
                            value: String?,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean
                        ) {
                            text = when (value) {
                                "#" -> "# (VS Code style)"
                                "@" -> "@ (AI Assistant style)"
                                else -> "Disabled"
                            }
                        }
                    } as javax.swing.ListCellRenderer<in String>
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
                .bindSelected({ mcp.isSmoothScrollEnabled }, { mcp.isSmoothScrollEnabled = it })
        }
        row {
            checkBox("Show turn stats below messages (duration, tokens, lines changed)")
                .comment(
                    "Displays a summary footer below the last message of each agent turn. " +
                        "Disabling saves vertical space."
                )
                .bindSelected({ mcp.isShowTurnStats }, { mcp.isShowTurnStats = it })
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
                    // UNCHECKED_CAST: ComboBoxWithWidePopup's E! platform type requires an explicit
                    // cast; the renderer is correct at runtime.
                    @Suppress("UNCHECKED_CAST")
                    renderer =
                        object : com.intellij.ui.SimpleListCellRenderer<ChatInputSettings.UnhandledNudgeMode>() {
                            override fun customize(
                                list: javax.swing.JList<out ChatInputSettings.UnhandledNudgeMode>,
                                value: ChatInputSettings.UnhandledNudgeMode?,
                                index: Int,
                                selected: Boolean,
                                hasFocus: Boolean
                            ) {
                                text = when (value) {
                                    ChatInputSettings.UnhandledNudgeMode.AUTO_SEND -> "Auto-send as a new prompt"
                                    ChatInputSettings.UnhandledNudgeMode.RESTORE_INTO_INPUT -> "Restore into chat input"
                                    null -> ""
                                }
                            }
                        } as javax.swing.ListCellRenderer<in ChatInputSettings.UnhandledNudgeMode>
                }
                .bindItem(
                    { s.unhandledNudgeMode },
                    { s.unhandledNudgeMode = it ?: ChatInputSettings.UnhandledNudgeMode.AUTO_SEND }
                )
        }
        separator()
        row("Reprimand nudges:") {
            comboBox(ChatInputSettings.ReprimandNudgeMode.entries.toList())
                .comment(
                    "Controls auto-sent nudges that correct the agent when it calls a built-in tool " +
                        "instead of the MCP equivalent. " +
                        "\"Send silently\" injects the correction without showing a bubble in the chat."
                )
                .applyToComponent {
                    // UNCHECKED_CAST: ComboBoxWithWidePopup's E! platform type requires an explicit
                    // cast; the renderer is correct at runtime.
                    @Suppress("UNCHECKED_CAST")
                    renderer =
                        object : com.intellij.ui.SimpleListCellRenderer<ChatInputSettings.ReprimandNudgeMode>() {
                            override fun customize(
                                list: javax.swing.JList<out ChatInputSettings.ReprimandNudgeMode>,
                                value: ChatInputSettings.ReprimandNudgeMode?,
                                index: Int,
                                selected: Boolean,
                                hasFocus: Boolean
                            ) {
                                text = when (value) {
                                    ChatInputSettings.ReprimandNudgeMode.ENABLED -> "Enabled"
                                    ChatInputSettings.ReprimandNudgeMode.SEND_SILENTLY -> "Send silently"
                                    ChatInputSettings.ReprimandNudgeMode.DISABLED -> "Disabled"
                                    null -> "Enabled"
                                }
                            }
                        } as javax.swing.ListCellRenderer<in ChatInputSettings.ReprimandNudgeMode>
                }
                .bindItem(
                    { s.reprimandNudgeMode },
                    { s.reprimandNudgeMode = it ?: ChatInputSettings.ReprimandNudgeMode.ENABLED }
                )
        }
        separator()
        row {
            checkBox("Auto-pause when typing")
                .comment(
                    "Automatically pauses the agent when you start typing in the chat input."
                )
                .bindSelected({ s.isPauseOnInputFocus }, { s.isPauseOnInputFocus = it })
        }
        separator()
        row {
            comment("Tool call timeout: how long to wait before asking what to do.")
        }
        lateinit var timeoutDialogEnabled: Cell<JBCheckBox>
        row {
            timeoutDialogEnabled = checkBox("Show timeout dialog for slow tool calls")
                .comment("When disabled, slow tool calls wait silently until they complete or are cancelled.")
                .bindSelected({ s.isToolTimeoutDialogEnabled }, { s.isToolTimeoutDialogEnabled = it })
        }
        row("Initial timeout (seconds):") {
            spinner(10..3600, 5)
                .comment(
                    "Seconds to wait for a tool call before showing the \"still running\" dialog. " +
                        "Default: 60. The dialog is not shown when you are reviewing diffs."
                )
                .bindIntValue({ s.toolTimeoutSeconds }, { s.toolTimeoutSeconds = it })
                .enabledIf(timeoutDialogEnabled.selected)
        }
        row("First wait extension (minutes):") {
            spinner(1..60, 1)
                .comment("First option in the \"still running\" dialog — wait this many extra minutes.")
                .bindIntValue({ s.toolTimeoutExtension1Minutes }, { s.toolTimeoutExtension1Minutes = it })
                .enabledIf(timeoutDialogEnabled.selected)
        }
        row("Second wait extension (minutes):") {
            spinner(1..60, 1)
                .comment("Second option in the \"still running\" dialog — wait this many extra minutes.")
                .bindIntValue({ s.toolTimeoutExtension2Minutes }, { s.toolTimeoutExtension2Minutes = it })
                .enabledIf(timeoutDialogEnabled.selected)
        }
        onApply {
            val chatContent = ChatToolWindowContent.getInstance(project)
            chatContent?.setSoftWrapsEnabled(s.isSoftWrapsEnabled)
            chatContent?.setShortcutHintsVisible()
        }
    }
}

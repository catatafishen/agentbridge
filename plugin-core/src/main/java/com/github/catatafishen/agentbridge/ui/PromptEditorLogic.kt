package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.client.SlashCommandInfo

/**
 * Pure decision logic extracted from [PromptEditorSetup]'s event handlers.
 * Stateless — all methods are deterministic functions of their inputs.
 * Designed for unit testing without IDE or UI dependencies.
 */
object PromptEditorLogic {

    @JvmStatic
    fun resolveEnterAction(
        promptText: String,
        hasAuthPendingError: Boolean,
        isSending: Boolean
    ): String {
        if (promptText.isBlank() || hasAuthPendingError) return "noop"
        return if (isSending) "nudge" else "send"
    }

    /**
     * Filters slash-command info objects whose name matches the given input prefix.
     * Input must start with "/" and contain no newlines to qualify.
     */
    @JvmStatic
    fun filterSlashCommands(input: String, commands: List<SlashCommandInfo>): List<SlashCommandInfo> {
        if (!input.startsWith("/") || input.contains("\n")) return emptyList()
        return commands.filter { it.name.startsWith(input, ignoreCase = true) }
    }

    /**
     * Returns true if the text exceeds the smart-paste thresholds (lines OR chars).
     * Used to decide whether to trigger smart-paste (attach as context) vs plain insert.
     */
    @JvmStatic
    fun shouldSmartPaste(text: String, minLines: Int, minChars: Int): Boolean {
        return text.lines().size > minLines || text.length > minChars
    }
}

package com.github.catatafishen.agentbridge.ui

/**
 * Pure decision logic extracted from [PromptEditorSetup]'s event handlers.
 * Stateless — all methods are deterministic functions of their inputs.
 * Designed for unit testing without IDE or UI dependencies.
 */
object PromptEditorLogic {

    /**
     * Determines which action the Enter key should trigger based on current state.
     * @return one of: "send", "nudge", or "noop"
     */
    @JvmStatic
    fun resolveEnterAction(
        promptText: String,
        hasAuthPendingError: Boolean,
        hasPendingAskUserRequest: Boolean,
        isSending: Boolean
    ): String {
        if (promptText.isBlank() || hasAuthPendingError) return "noop"
        return when {
            hasPendingAskUserRequest -> "send"
            isSending -> "nudge"
            else -> "send"
        }
    }

    /**
     * Filters slash-command names that match the given input prefix.
     * Input must start with "/" and contain no newlines to qualify.
     */
    @JvmStatic
    fun filterSlashCommands(input: String, commandNames: List<String>): List<String> {
        if (!input.startsWith("/") || input.contains("\n")) return emptyList()
        return commandNames.filter { it.startsWith(input, ignoreCase = true) }
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

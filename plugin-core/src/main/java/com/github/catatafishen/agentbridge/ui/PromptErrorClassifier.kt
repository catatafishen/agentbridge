package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.agent.AgentException

/**
 * Pure error classification and quick-reply detection for prompt errors.
 * Extracted from [PromptOrchestrator] to enable unit testing without UI dependencies.
 */
object PromptErrorClassifier {

    private val QUICK_REPLY_TAG_REGEX = Regex("\\[\\s*quick-reply:\\s*([^]]+)]")

    /**
     * Classification result for a prompt error — all decisions are captured as data,
     * so the UI layer can act on them without re-analyzing the exception.
     */
    data class Classification(
        val isCancelled: Boolean,
        val isAuthError: Boolean,
        val isRecoverable: Boolean,
        val isProcessCrashWithRecovery: Boolean,
        val shouldRestorePrompt: Boolean,
        val displayMessage: String,
    )

    /**
     * Classifies a prompt error into a set of boolean decisions and a display message.
     *
     * @param exception the thrown exception
     * @param turnHadContent whether the agent produced any content before the error
     * @param isAuthenticationError predicate to check if a message indicates an auth failure
     * @param isClientHealthy whether the agent client is still responsive
     */
    fun classify(
        exception: Exception,
        turnHadContent: Boolean,
        isAuthenticationError: (String) -> Boolean,
        isClientHealthy: Boolean,
    ): Classification {
        val isCancelled = exception is InterruptedException
            || exception.cause is InterruptedException

        var msg = if (isCancelled) "Request cancelled"
        else exception.message ?: "Unknown error"

        // Walk the cause chain looking for an authentication error
        var isAuthError = false
        var cause: Throwable? = exception
        while (cause != null) {
            val causeMsg = cause.message ?: ""
            if (isAuthenticationError(causeMsg)) {
                msg = causeMsg
                isAuthError = true
                break
            }
            cause = cause.cause
        }

        // For ACP errors, ensure the message is descriptive
        if (exception is AgentException && !msg.startsWith("(")) {
            msg = "ACP error: $msg"
        }

        val isRecoverable = isCancelled
            || (exception is AgentException && exception.isRecoverable)

        // Agent process crashed but already recovered — preserve session
        val isProcessCrashWithRecovery = !isCancelled
            && generateSequence(exception as Throwable?) { it.cause }.any {
            it.message?.contains("process exited unexpectedly", ignoreCase = true) == true
        }
            && isClientHealthy

        val shouldRestorePrompt = !turnHadContent && !isCancelled

        return Classification(
            isCancelled = isCancelled,
            isAuthError = isAuthError,
            isRecoverable = isRecoverable,
            isProcessCrashWithRecovery = isProcessCrashWithRecovery,
            shouldRestorePrompt = shouldRestorePrompt,
            displayMessage = msg,
        )
    }

    /**
     * Detects `[quick-reply: opt1 | opt2]` tags in response text and returns the parsed options.
     * Returns an empty list if no quick-reply tag is found.
     */
    fun detectQuickReplies(responseText: String): List<String> {
        val match = QUICK_REPLY_TAG_REGEX.findAll(responseText).lastOrNull() ?: return emptyList()
        return match.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Returns true if the exception cause chain contains a non-recoverable [AgentException],
     * indicating the agent CLI binary was not found.
     */
    fun isCLINotFoundError(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is AgentException && !cause.isRecoverable) return true
            cause = cause.cause
        }
        return false
    }
}

package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Runs the tool call behind an [ActionButton] when the user clicks it.
 *
 * The call goes through [PsiBridgeService.callTool], so the user's normal tool permissions,
 * write locks and tool-call tracking all apply — a click is not a permission bypass.
 * No LLM turn is started.
 */
object ButtonActionRunner {
    private val LOG = Logger.getInstance(ButtonActionRunner::class.java)
    private const val MAX_RESULT_CHARS = 2000

    /** Outcome of a click; [summary] is the text reported to the agent. */
    data class Outcome(val success: Boolean, val summary: String)

    /** Runs [action] on a pooled thread and delivers the outcome on the EDT. */
    fun run(project: Project, action: ActionButton, onDone: (Outcome) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = execute(project, action)
            ApplicationManager.getApplication().invokeLater({ onDone(outcome) }, project.disposed)
        }
    }

    internal fun execute(project: Project, action: ActionButton): Outcome {
        return try {
            val result = PsiBridgeService.getInstance(project).callTool(action.tool, action.arguments.deepCopy())
            val text = result.contentOrEmpty().trim().take(MAX_RESULT_CHARS)
            Outcome(!result.isError, describe(action, if (result.isError) "failed" else "OK", text))
        } catch (e: Exception) {
            LOG.warn("Action button '${action.label}' (${action.tool}) failed", e)
            Outcome(false, describe(action, "failed", e.message ?: e.javaClass.simpleName))
        }
    }

    private fun describe(action: ActionButton, status: String, detail: String): String =
        "User ran action \"${action.label}\" (${action.tool}): $status" + if (detail.isEmpty()) "" else "\n$detail"

    private const val MAX_NOTE_CHARS = 400

    /**
     * Records the outcome as a note for the agent's next prompt (used when no agent is waiting).
     * Includes the (truncated) detail so the agent can see why an action failed.
     */
    fun recordNote(project: Project, outcome: Outcome) {
        PendingButtons.getInstance(project).addNote(noteFor(outcome))
    }

    /** Flattens an outcome to a single bounded line for the agent's next prompt. */
    fun noteFor(outcome: Outcome): String {
        val flat = outcome.summary.lineSequence().joinToString(" ") { it.trim() }.trim()
        return if (flat.length > MAX_NOTE_CHARS) flat.take(MAX_NOTE_CHARS) + "…" else flat
    }
}

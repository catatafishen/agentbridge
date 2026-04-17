package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.intellij.openapi.Disposable
import javax.swing.JComponent

/**
 * Public API for the chat console panel.
 * AgenticCopilotToolWindowContent programs against this interface.
 */
interface ChatPanelApi : Disposable {

    /** The Swing component to embed in the tool window. */
    val component: JComponent

    /** Callback invoked when the user clicks a quick-reply button. */
    var onQuickReply: ((String) -> Unit)?

    // ── User messages ──────────────────────────────────────────────

    fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>? = null,
        bubbleHtml: String? = null
    ): String

    fun removePromptEntry(entryId: String)

    fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int)
    fun setCurrentModel(modelId: String)
    fun setCurrentProfile(profileId: String)
    fun setCurrentAgent(agentName: String, profileId: String, clientType: String = "")
    fun addContextFilesEntry(files: List<Pair<String, String>>)

    // ── Agent text (streaming) ─────────────────────────────────────

    fun startStreaming()
    fun appendText(text: String)
    fun appendThinkingText(text: String)
    fun collapseThinking()

    // ── Tool calls ─────────────────────────────────────────────────

    fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String? = null,
        kind: String? = null
    )

    fun updateToolCall(
        id: String,
        status: String,
        details: String? = null,
        description: String? = null,
        kind: String? = null,
        autoDenied: Boolean = false,
        denialReason: String? = null,
        arguments: String? = null,
        title: String? = null
    )

    // ── Sub-agents ─────────────────────────────────────────────────

    fun addSubAgentEntry(
        id: String, agentType: String, description: String, prompt: String?,
        initialResult: String? = null, initialStatus: String? = null, initialDescription: String? = null,
        autoDenied: Boolean = false, denialReason: String? = null
    )

    fun updateSubAgentResult(
        id: String, status: String, result: String?, description: String? = null,
        autoDenied: Boolean = false, denialReason: String? = null
    )

    // ── Sub-agent internal tool calls ──────────────────────────────

    fun addSubAgentToolCall(
        subAgentId: String,
        toolId: String,
        title: String,
        arguments: String? = null,
        kind: String? = null
    )

    fun updateSubAgentToolCall(
        toolId: String, status: String, details: String? = null, description: String? = null,
        autoDenied: Boolean = false, denialReason: String? = null
    )

    // ── Status / errors ────────────────────────────────────────────

    /** Callback invoked to display a transient status banner (error/info). */
    var onStatusMessage: ((type: String, message: String) -> Unit)?

    fun addErrorEntry(message: String)
    fun addInfoEntry(message: String)

    // ── Session management ─────────────────────────────────────────

    fun hasContent(): Boolean
    fun addSessionSeparator(timestamp: String, agent: String = "")
    fun showPlaceholder(text: String)
    fun clear()

    // ── Turn lifecycle ─────────────────────────────────────────────

    fun finishResponse(toolCallCount: Int = 0, modelId: String = "", multiplier: String = "1x")
    fun emitTurnStats(
        durationMs: Long, inputTokens: Int, outputTokens: Int, costUsd: Double,
        toolCallCount: Int, linesAdded: Int, linesRemoved: Int, model: String, multiplier: String,
        commitHashes: List<String> = emptyList()
    )

    fun showQuickReplies(options: List<String>)
    fun disableQuickReplies()
    fun cancelAllRunning()

    // ── Conversation export / persistence ──────────────────────────

    fun getConversationText(): String
    fun getCompressedSummary(maxChars: Int = 8000): String
    fun getConversationHtml(): String
    fun getLastResponseText(): String

    // ── Debug / introspection ──────────────────────────────────────

    /** Returns the full HTML of the JCEF page (live DOM), or null if unavailable. */
    fun getPageHtml(): String?

    // ── Permission requests ────────────────────────────────────────

    /**
     * Show a permission request bubble in the chat pane with Deny / Allow / Allow for Session buttons.
     * [reqId] is a unique ID for this request. [onRespond] is called with the user's choice.
     */
    fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (PermissionResponse) -> Unit
    )

    /**
     * Show an ask-user bubble with quick-reply options. The user can also type a free-form response.
     */
    fun showAskUserRequest(
        reqId: String,
        question: String,
        options: List<String>,
        onRespond: (String) -> Unit
    )

    fun hasPendingAskUserRequest(): Boolean
    fun consumePendingAskUserResponse(response: String): Boolean
    fun clearPendingAskUserRequest(reqId: String? = null)

    fun showNudgeBubble(id: String, text: String)
    fun resolveNudgeBubble(id: String)
    fun removeNudgeBubble(id: String)
    fun addNudgeEntry(id: String, text: String)

    fun showQueuedMessage(id: String, text: String)
    fun removeQueuedMessage(id: String)
    fun removeQueuedMessageByText(text: String)
}

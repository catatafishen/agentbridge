package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.NudgeSource
import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.intellij.openapi.Disposable
import javax.swing.JComponent

data class TurnStatsData(
    val durationMs: Long, val inputTokens: Int, val outputTokens: Int, val costUsd: Double,
    val toolCallCount: Int, val linesAdded: Int, val linesRemoved: Int,
    val model: String, val multiplier: String, val commitHashes: List<String> = emptyList(),
    val gitBranchAtStart: String? = null,
    val gitBranchAtEnd: String? = null
)

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

    fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) { /* no-op default */ }
    fun setCurrentModel(modelId: String)
    fun setCurrentProfile(profileId: String)
    fun setCurrentAgent(agentName: String, profileId: String, clientType: String = "")

    /**
     * Records context file references. Default is a no-op; implementations may
     * override to track files for export or history persistence.
     */
    fun addContextFilesEntry(files: List<Pair<String, String>>) { /* no-op default */ }

    /**
     * Displays image thumbnails below the most recently added user message bubble.
     * Called after [addPromptEntry] when the prompt includes image attachments.
     * Default is a no-op; implementations that can render images should override.
     */
    fun addImageThumbnails(images: List<ImageAttachment>) { /* no-op default */ }

    /** Resolved image attachment ready for thumbnail display. */
    data class ImageAttachment(val name: String, val base64Data: String, val mimeType: String)

    // ── Agent text (streaming) ─────────────────────────────────────

    fun startStreaming()
    fun appendText(text: String)
    fun appendThinkingText(text: String)
    fun collapseThinking()

    /**
     * Closes the current streaming text entry so the next [appendText] call creates a fresh
     * entry with a new ID.
     *
     * Must be called before appending content that must be stored as a distinct DB record
     * (e.g. a task_complete summary that follows tool calls whose text was already persisted).
     */
    fun closeCurrentTextEntry()

    // ── Tool calls ─────────────────────────────────────────────────

    fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String? = null,
        kind: String? = null,
        isMcpHandled: Boolean = false
    )

    data class ToolCallUpdate(
        val details: String? = null,
        val description: String? = null,
        val kind: String? = null,
        val autoDenied: Boolean = false,
        val denialReason: String? = null,
        val arguments: String? = null,
        val title: String? = null
    )

    fun updateToolCall(id: String, status: String, update: ToolCallUpdate = ToolCallUpdate())

    // ── Sub-agents ─────────────────────────────────────────────────

    data class SubAgentInitialState(
        val result: String? = null,
        val status: String? = null,
        val description: String? = null,
        val autoDenied: Boolean = false,
        val denialReason: String? = null
    )

    fun addSubAgentEntry(
        id: String, agentType: String, description: String, prompt: String?,
        initialState: SubAgentInitialState = SubAgentInitialState()
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
    fun emitTurnStats(stats: TurnStatsData)

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
     *
     * The bubble renders a live countdown (driven by [deadlineEpochMs]) and an
     * "I need more time" button. When the user clicks that button, [onExtend] is invoked;
     * the new deadline it returns is pushed back to the JS countdown.
     *
     * If a previous ask-user request is still open, its [onSuperseded] is invoked so the
     * tool that issued it can complete its waiter (typically with a "cancelled" terminal state).
     */
    fun showAskUserRequest(
        reqId: String,
        question: String,
        options: List<String>,
        deadlineEpochMs: Long,
        onRespond: (String) -> Unit,
        onExtend: () -> Long,
        onSuperseded: () -> Unit,
    )

    fun hasPendingAskUserRequest(): Boolean
    fun consumePendingAskUserResponse(response: String): Boolean
    fun clearPendingAskUserRequest(reqId: String? = null)

    fun showNudgeBubble(id: String, text: String, source: NudgeSource = NudgeSource.HUMAN)
    fun resolveNudgeBubble(id: String)
    fun removeNudgeBubble(id: String)
    fun addNudgeEntry(id: String, text: String, source: NudgeSource = NudgeSource.HUMAN)

    fun showQueuedMessage(id: String, text: String)
    fun removeQueuedMessage(id: String)
    fun removeQueuedMessageByText(text: String)
}

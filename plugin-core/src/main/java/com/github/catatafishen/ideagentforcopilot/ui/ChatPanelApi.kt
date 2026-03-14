package com.github.catatafishen.ideagentforcopilot.ui

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
    )

    fun setPromptStats(modelId: String, multiplier: String)
    fun setCurrentModel(modelId: String)
    fun setCurrentProfile(profileId: String)
    fun addContextFilesEntry(files: List<Pair<String, String>>)

    // ── Agent text (streaming) ─────────────────────────────────────

    fun startStreaming()
    fun appendText(text: String)
    fun appendThinkingText(text: String)
    fun collapseThinking()

    // ── Tool calls ─────────────────────────────────────────────────

    fun addToolCallEntry(id: String, title: String, arguments: String? = null, kind: String? = null)
    fun updateToolCall(id: String, status: String, details: String? = null)

    // ── Sub-agents ─────────────────────────────────────────────────

    fun addSubAgentEntry(
        id: String, agentType: String, description: String, prompt: String?,
        initialResult: String? = null, initialStatus: String? = null
    )

    fun updateSubAgentResult(id: String, status: String, result: String?)

    // ── Sub-agent internal tool calls ──────────────────────────────

    fun addSubAgentToolCall(
        subAgentId: String,
        toolId: String,
        title: String,
        arguments: String? = null,
        kind: String? = null
    )

    fun updateSubAgentToolCall(toolId: String, status: String, details: String? = null)

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
        onRespond: (com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse) -> Unit
    )
}

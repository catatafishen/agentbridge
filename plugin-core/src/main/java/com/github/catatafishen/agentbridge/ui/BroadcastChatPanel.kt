package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.*
import com.github.catatafishen.agentbridge.session.ConversationEntryStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import javax.swing.JComponent

/**
 * The single chat panel for a project, delegating all [ChatPanelApi] calls to [nativePanel].
 *
 * Acts as a **resilience boundary** between the backend (agent/streaming threads) and the
 * UI (Swing/NativeChatPanel). All write operations:
 * 1. Update [entryStore] synchronously (thread-safe, never lost)
 * 2. Dispatch to [nativePanel] via fire-and-forget [invokeLater] (never blocks the caller)
 *
 * This ensures that in Gateway/thin-client scenarios with poor connectivity, the agent
 * can continue working uninterrupted — UI rendering is best-effort while data is always
 * persisted. When the thin client reconnects, the UI can be rebuilt from the entry store.
 *
 * Use [getInstance] to obtain the panel for a given project.
 */
class BroadcastChatPanel(
    val project: Project,
    val nativePanel: NativeChatPanel,
) : ChatPanelApi, PermissionPromptProvider {

    companion object {
        private val instances = ConcurrentHashMap<Project, BroadcastChatPanel>()
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(BroadcastChatPanel::class.java)

        @JvmStatic
        fun getInstance(project: Project): BroadcastChatPanel? = instances[project]
    }

    @Volatile
    private var disposed = false

    init {
        instances[project] = this
        PermissionPromptProviderHolder.register(project, this)
    }

    override val component: JComponent = nativePanel.component

    /**
     * Dispatches a UI update to the EDT. Never blocks the calling thread.
     * Silently drops the update if the panel is disposed or if an exception occurs
     * during rendering (e.g., thin client disconnected).
     */
    private fun dispatchUi(action: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater({
            if (disposed) return@invokeLater
            try {
                action()
            } catch (e: Exception) {
                if (!disposed) {
                    LOG.debug("UI dispatch failed (thin client may be disconnected)", e)
                }
            }
        }, ModalityState.defaultModalityState(), project.disposed)
    }

    // ── Entry tracking (delegated to ConversationEntryStore) ───────────────────

    val entryStore = ConversationEntryStore()

    fun getEntries(): List<EntryData> = entryStore.getEntries()

    fun entriesSnapshot(): List<EntryData> = entryStore.entriesSnapshot()

    fun isEntryRendered(entryId: String): Boolean = entryStore.isEntryTracked(entryId)

    fun scrollToEntry(entryId: String) = dispatchUi { nativePanel.scrollToEntry(entryId) }

    fun addEntriesChangeListener(listener: Runnable) = entryStore.addChangeListener(listener)

    fun removeEntriesChangeListener(listener: Runnable) = entryStore.removeChangeListener(listener)

    // ── Callback vars ─────────────────────────────────────────────────────────

    override var onQuickReply: ((String) -> Unit)?
        get() = nativePanel.onQuickReply
        set(value) {
            nativePanel.onQuickReply = value
        }

    override var onStatusMessage: ((type: String, message: String) -> Unit)?
        get() = nativePanel.onStatusMessage
        set(value) {
            nativePanel.onStatusMessage = value
        }

    // ── Write methods — store data (any thread), then dispatch UI (fire-and-forget) ──

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
        val entryId = nativePanel.addPromptEntry(text, contextFiles, bubbleHtml)
        val ctxRefs = contextFiles?.map { (name, path, line) -> ContextFileRef(name, path, line) }
        entryStore.addPromptEntry(text, ctxRefs, entryId)
        return entryId
    }

    override fun removePromptEntry(entryId: String) {
        entryStore.removePromptEntry(entryId)
        dispatchUi { nativePanel.removePromptEntry(entryId) }
    }

    override fun startStreaming() {
        entryStore.startStreaming()
        dispatchUi { nativePanel.startStreaming() }
    }

    override fun appendText(text: String) {
        entryStore.appendText(text)
        dispatchUi { nativePanel.appendText(text) }
    }

    override fun appendThinkingText(text: String) {
        entryStore.appendThinkingText(text)
        dispatchUi { nativePanel.appendThinkingText(text) }
    }

    override fun collapseThinking() = dispatchUi { nativePanel.collapseThinking() }

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) =
        dispatchUi { nativePanel.setCodeChangeStats(linesAdded, linesRemoved) }

    override fun setCurrentModel(modelId: String) = dispatchUi { nativePanel.setCurrentModel(modelId) }

    override fun setCurrentProfile(profileId: String) = dispatchUi { nativePanel.setCurrentProfile(profileId) }

    override fun setCurrentAgent(agentName: String, profileId: String, clientType: String) {
        entryStore.setCurrentAgent(agentName)
        dispatchUi { nativePanel.setCurrentAgent(agentName, profileId, clientType) }
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) =
        dispatchUi { nativePanel.addContextFilesEntry(files) }

    override fun addImageThumbnails(images: List<ChatPanelApi.ImageAttachment>) =
        dispatchUi { nativePanel.addImageThumbnails(images) }

    override fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String?,
        kind: String?,
        isMcpHandled: Boolean
    ) {
        entryStore.addToolCallEntry(id, title, arguments, kind)
        dispatchUi { nativePanel.addToolCallEntry(id, title, arguments, kind, isMcpHandled) }
    }

    override fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) {
        entryStore.updateToolCall(id, status, update)
        dispatchUi { nativePanel.updateToolCall(id, status, update) }
    }

    override fun addSubAgentEntry(
        id: String,
        agentType: String,
        description: String,
        prompt: String?,
        initialState: ChatPanelApi.SubAgentInitialState
    ) {
        entryStore.addSubAgentEntry(id, agentType, description, prompt, initialState)
        dispatchUi { nativePanel.addSubAgentEntry(id, agentType, description, prompt, initialState) }
    }

    override fun updateSubAgentResult(
        id: String,
        status: String,
        result: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) {
        entryStore.updateSubAgentResult(id, status, result, description, autoDenied, denialReason)
        dispatchUi { nativePanel.updateSubAgentResult(id, status, result, description, autoDenied, denialReason) }
    }

    override fun addSubAgentToolCall(
        subAgentId: String, toolId: String, title: String,
        arguments: String?, kind: String?
    ) = dispatchUi { nativePanel.addSubAgentToolCall(subAgentId, toolId, title, arguments, kind) }

    override fun updateSubAgentToolCall(
        toolId: String, status: String, details: String?, description: String?,
        autoDenied: Boolean, denialReason: String?
    ) = dispatchUi {
        nativePanel.updateSubAgentToolCall(
            toolId,
            status,
            details,
            description,
            autoDenied,
            denialReason
        )
    }

    override fun addErrorEntry(message: String) = dispatchUi { nativePanel.addErrorEntry(message) }

    override fun addInfoEntry(message: String) = dispatchUi { nativePanel.addInfoEntry(message) }

    override fun addSessionSeparator(timestamp: String, agent: String) {
        entryStore.addSessionSeparator(timestamp, agent)
        dispatchUi { nativePanel.addSessionSeparator(timestamp, agent) }
    }

    override fun showPlaceholder(text: String) = dispatchUi { nativePanel.showPlaceholder(text) }

    override fun clear() {
        entryStore.clear()
        dispatchUi { nativePanel.clear() }
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        entryStore.finishResponse()
        dispatchUi { nativePanel.finishResponse(toolCallCount, modelId, multiplier) }
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        entryStore.emitTurnStats(stats)
        dispatchUi { nativePanel.emitTurnStats(stats) }
    }

    override fun showQuickReplies(options: List<String>) = dispatchUi { nativePanel.showQuickReplies(options) }

    override fun disableQuickReplies() = dispatchUi { nativePanel.disableQuickReplies() }

    override fun cancelAllRunning() = dispatchUi { nativePanel.cancelAllRunning() }

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) =
        dispatchUi { nativePanel.showNudgeBubble(id, text, source) }

    override fun resolveNudgeBubble(id: String) = dispatchUi { nativePanel.resolveNudgeBubble(id) }

    override fun removeNudgeBubble(id: String) = dispatchUi { nativePanel.removeNudgeBubble(id) }

    override fun addNudgeEntry(id: String, text: String, source: NudgeSource) {
        entryStore.addNudgeEntry(id, text, source)
        dispatchUi { nativePanel.addNudgeEntry(id, text, source) }
    }

    override fun showQueuedMessage(id: String, text: String) =
        dispatchUi { nativePanel.showQueuedMessage(id, text) }

    override fun removeQueuedMessage(id: String) = dispatchUi { nativePanel.removeQueuedMessage(id) }

    override fun removeQueuedMessageByText(text: String) =
        dispatchUi { nativePanel.removeQueuedMessageByText(text) }

    override fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (PermissionResponse) -> Unit
    ) {
        _pendingPermissionCallbacks[reqId] = onRespond
        dispatchUi {
            nativePanel.showPermissionRequest(reqId, toolDisplayName, description) { response ->
                _pendingPermissionCallbacks.remove(reqId)
                onRespond(response)
            }
        }
    }

    /** Handles a permission response that arrived from the web client (phone/browser PWA). */
    fun handleWebPermissionResponse(data: String) {
        val colonIdx = data.indexOf(':')
        if (colonIdx <= 0) return
        val reqId = data.substring(0, colonIdx)
        val response = when (data.substring(colonIdx + 1)) {
            "once" -> PermissionResponse.ALLOW_ONCE
            "session" -> PermissionResponse.ALLOW_SESSION
            "always" -> PermissionResponse.ALLOW_ALWAYS
            else -> PermissionResponse.DENY
        }
        _pendingPermissionCallbacks.remove(reqId)?.invoke(response)
    }

    private val _pendingPermissionCallbacks = ConcurrentHashMap<String, (PermissionResponse) -> Unit>()

    override fun showAskUserRequest(
        reqId: String,
        question: String,
        options: List<String>,
        deadlineEpochMs: Long,
        onRespond: (String) -> Unit,
        onExtend: () -> Long,
        onSuperseded: () -> Unit,
    ) = dispatchUi {
        nativePanel.showAskUserRequest(reqId, question, options, deadlineEpochMs, onRespond, onExtend, onSuperseded)
    }

    // ── PermissionPromptProvider (Java interface bridge) ────────────────────────

    override fun showPermissionPrompt(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: Consumer<PermissionResponse>
    ) = showPermissionRequest(reqId, toolDisplayName, description) { onRespond.accept(it) }

    // ── History management ─────────────────────────────────────────────────────

    var onLoadMoreRequested: (() -> Unit)?
        get() = nativePanel.onLoadMoreRequested
        set(value) {
            nativePanel.onLoadMoreRequested = value
        }

    fun appendEntries(entries: List<EntryData>, totalPromptCount: Int = -1) =
        dispatchUi { nativePanel.appendEntries(entries, totalPromptCount) }

    fun prependEntries(entries: List<EntryData>) = dispatchUi { nativePanel.prependEntries(entries) }

    fun showLoadMore(deferredCount: Int) = dispatchUi { nativePanel.showLoadMore(deferredCount) }

    fun hideLoadMore() = dispatchUi { nativePanel.hideLoadMore() }

    // ── Read-only ─────────────────────────────────────────────────────────────

    override fun hasContent(): Boolean = nativePanel.hasContent()

    override fun getConversationText(): String = nativePanel.getConversationText()

    override fun getCompressedSummary(maxChars: Int): String = nativePanel.getCompressedSummary(maxChars)

    override fun getConversationHtml(): String = nativePanel.getConversationHtml()

    override fun getLastResponseText(): String = nativePanel.getLastResponseText()

    /** Returns null — JCEF DOM export is no longer available. Use [getConversationText] instead. */
    override fun getPageHtml(): String? = null

    override fun hasPendingAskUserRequest(): Boolean = nativePanel.hasPendingAskUserRequest()

    override fun consumePendingAskUserResponse(response: String): Boolean =
        nativePanel.consumePendingAskUserResponse(response)

    override fun clearPendingAskUserRequest(reqId: String?) = nativePanel.clearPendingAskUserRequest(reqId)

    // ── Dispose ────────────────────────────────────────────────────────────────

    override fun dispose() {
        disposed = true
        instances.remove(project, this)
        PermissionPromptProviderHolder.unregister(project)
        nativePanel.dispose()
    }
}

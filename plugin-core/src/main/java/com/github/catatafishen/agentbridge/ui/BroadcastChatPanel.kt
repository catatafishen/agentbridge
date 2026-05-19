package com.github.catatafishen.agentbridge.ui
import com.github.catatafishen.agentbridge.bridge.FileRef
import com.github.catatafishen.agentbridge.bridge.ContextFileRef
import com.github.catatafishen.agentbridge.bridge.NudgeSource
import com.github.catatafishen.agentbridge.bridge.EntryData

import com.github.catatafishen.agentbridge.bridge.MessageFormatter
import com.github.catatafishen.agentbridge.bridge.PermissionPromptProvider
import com.github.catatafishen.agentbridge.bridge.PermissionPromptProviderHolder
import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.intellij.openapi.project.Project
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.function.Supplier
import javax.swing.JComponent

/**
 * The single chat panel for a project, delegating all [ChatPanelApi] calls to [nativePanel].
 *
 * Tracks [EntryData] entries as they arrive so that [getEntries], [entriesSnapshot],
 * [isEntryRendered], and [scrollToEntry] work for history persistence and PromptsPanel navigation.
 *
 * Use [getInstance] to obtain the panel for a given project.
 */
class BroadcastChatPanel(
    val project: Project,
    val nativePanel: NativeChatPanel,
) : ChatPanelApi, PermissionPromptProvider {

    companion object {
        private val instances = ConcurrentHashMap<Project, BroadcastChatPanel>()

        @JvmStatic
        fun getInstance(project: Project): BroadcastChatPanel? = instances[project]
    }

    init {
        instances[project] = this
        PermissionPromptProviderHolder.register(project, this)
    }

    override val component: JComponent = nativePanel.component

    // ── Entry tracking ─────────────────────────────────────────────────────────

    private val _allEntries = mutableListOf<EntryData>()
    private var _currentText: EntryData.Text? = null
    private var _currentThinking: EntryData.Thinking? = null
    private val _toolCallEntries = mutableMapOf<String, EntryData.ToolCall>()
    private val _subAgentEntries = mutableMapOf<String, EntryData.SubAgent>()
    private val _entriesChangeListeners = mutableListOf<Runnable>()

    private var _currentAgent = ""

    private fun timestamp() = MessageFormatter.timestamp()

    private fun fireEntriesChanged() = _entriesChangeListeners.forEach { it.run() }

    fun getEntries(): List<EntryData> = _allEntries.toList()

    fun entriesSnapshot(): List<EntryData> = ArrayList(_allEntries)

    fun isEntryRendered(entryId: String): Boolean = _allEntries.any { it.entryId == entryId }

    fun scrollToEntry(entryId: String) = nativePanel.scrollToEntry(entryId)

    fun addEntriesChangeListener(listener: Runnable) {
        _entriesChangeListeners.add(listener)
    }

    fun removeEntriesChangeListener(listener: Runnable) {
        _entriesChangeListeners.remove(listener)
    }

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

    // ── Write methods — delegate to nativePanel ────────────────────────────────

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
        val ctxRefs = contextFiles?.map { (name, path, line) -> ContextFileRef(name, path, line) }
        val entry = EntryData.Prompt(text, timestamp(), ctxRefs)
        _allEntries.add(entry)
        val result = nativePanel.addPromptEntry(text, contextFiles, bubbleHtml)
        fireEntriesChanged()
        return result
    }

    override fun removePromptEntry(entryId: String) {
        _allEntries.removeIf { it.entryId == entryId }
        nativePanel.removePromptEntry(entryId)
    }

    override fun startStreaming() {
        _currentText = null
        _currentThinking = null
        nativePanel.startStreaming()
    }

    override fun appendText(text: String) {
        val current = _currentText
        if (current == null) {
            _currentText = EntryData.Text(text, timestamp(), _currentAgent).also { _allEntries.add(it) }
        } else {
            current.raw += text
        }
        nativePanel.appendText(text)
    }

    override fun appendThinkingText(text: String) {
        val current = _currentThinking
        if (current == null) {
            _currentThinking = EntryData.Thinking(text, timestamp(), _currentAgent).also { _allEntries.add(it) }
        } else {
            current.raw += text
        }
        nativePanel.appendThinkingText(text)
    }

    override fun collapseThinking() = nativePanel.collapseThinking()

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) =
        nativePanel.setCodeChangeStats(linesAdded, linesRemoved)

    override fun setCurrentModel(modelId: String) = nativePanel.setCurrentModel(modelId)

    override fun setCurrentProfile(profileId: String) = nativePanel.setCurrentProfile(profileId)

    override fun setCurrentAgent(agentName: String, profileId: String, clientType: String) {
        _currentAgent = agentName
        nativePanel.setCurrentAgent(agentName, profileId, clientType)
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) =
        nativePanel.addContextFilesEntry(files)

    override fun addImageThumbnails(images: List<ChatPanelApi.ImageAttachment>) =
        nativePanel.addImageThumbnails(images)

    override fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String?,
        kind: String?,
        isMcpHandled: Boolean
    ) {
        val entry = EntryData.ToolCall(
            title, arguments, kind ?: "other",
            timestamp = timestamp(), agent = _currentAgent, entryId = id
        )
        _allEntries.add(entry)
        _toolCallEntries[id] = entry
        nativePanel.addToolCallEntry(id, title, arguments, kind, isMcpHandled)
    }

    override fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) {
        _toolCallEntries[id]?.let { entry ->
            entry.status = status
            update.details?.let { entry.result = it }
            update.description?.let { entry.description = it }
            update.kind?.let { entry.kind = it }
            entry.autoDenied = update.autoDenied
            update.denialReason?.let { entry.denialReason = it }
        }
        nativePanel.updateToolCall(id, status, update)
    }

    override fun addSubAgentEntry(
        id: String,
        agentType: String,
        description: String,
        prompt: String?,
        initialState: ChatPanelApi.SubAgentInitialState
    ) {
        val entry = EntryData.SubAgent(
            agentType, description, prompt,
            result = initialState.result,
            status = initialState.status,
            autoDenied = initialState.autoDenied,
            denialReason = initialState.denialReason,
            timestamp = timestamp(), agent = _currentAgent, entryId = id
        )
        _allEntries.add(entry)
        _subAgentEntries[id] = entry
        nativePanel.addSubAgentEntry(id, agentType, description, prompt, initialState)
    }

    override fun updateSubAgentResult(
        id: String,
        status: String,
        result: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) {
        _subAgentEntries[id]?.let { entry ->
            entry.status = status
            result?.let { entry.result = it }
            entry.autoDenied = autoDenied
            denialReason?.let { entry.denialReason = it }
        }
        nativePanel.updateSubAgentResult(id, status, result, description, autoDenied, denialReason)
    }

    override fun addSubAgentToolCall(
        subAgentId: String, toolId: String, title: String,
        arguments: String?, kind: String?
    ) = nativePanel.addSubAgentToolCall(subAgentId, toolId, title, arguments, kind)

    override fun updateSubAgentToolCall(
        toolId: String, status: String, details: String?, description: String?,
        autoDenied: Boolean, denialReason: String?
    ) = nativePanel.updateSubAgentToolCall(toolId, status, details, description, autoDenied, denialReason)

    override fun addErrorEntry(message: String) = nativePanel.addErrorEntry(message)

    override fun addInfoEntry(message: String) = nativePanel.addInfoEntry(message)

    override fun addSessionSeparator(timestamp: String, agent: String) =
        nativePanel.addSessionSeparator(timestamp, agent)

    override fun showPlaceholder(text: String) = nativePanel.showPlaceholder(text)

    override fun clear() {
        _allEntries.clear()
        _currentText = null
        _currentThinking = null
        _toolCallEntries.clear()
        _subAgentEntries.clear()
        nativePanel.clear()
        fireEntriesChanged()
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        _currentText = null
        _currentThinking = null
        nativePanel.finishResponse(toolCallCount, modelId, multiplier)
        fireEntriesChanged()
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        val entry = EntryData.TurnStats(
            turnId = UUID.randomUUID().toString(),
            durationMs = stats.durationMs,
            inputTokens = stats.inputTokens.toLong(),
            outputTokens = stats.outputTokens.toLong(),
            costUsd = stats.costUsd,
            toolCallCount = stats.toolCallCount,
            linesAdded = stats.linesAdded,
            linesRemoved = stats.linesRemoved,
            model = stats.model,
            multiplier = stats.multiplier,
            timestamp = timestamp(),
        )
        _allEntries.add(entry)
        nativePanel.emitTurnStats(stats)
        fireEntriesChanged()
    }

    override fun showQuickReplies(options: List<String>) = nativePanel.showQuickReplies(options)

    override fun disableQuickReplies() = nativePanel.disableQuickReplies()

    override fun cancelAllRunning() = nativePanel.cancelAllRunning()

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) =
        nativePanel.showNudgeBubble(id, text, source)

    override fun resolveNudgeBubble(id: String) = nativePanel.resolveNudgeBubble(id)

    override fun removeNudgeBubble(id: String) = nativePanel.removeNudgeBubble(id)

    override fun addNudgeEntry(id: String, text: String, source: NudgeSource) =
        nativePanel.addNudgeEntry(id, text, source)

    override fun showQueuedMessage(id: String, text: String) = nativePanel.showQueuedMessage(id, text)

    override fun removeQueuedMessage(id: String) = nativePanel.removeQueuedMessage(id)

    override fun removeQueuedMessageByText(text: String) = nativePanel.removeQueuedMessageByText(text)

    override fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (PermissionResponse) -> Unit
    ) {
        _pendingPermissionCallbacks[reqId] = onRespond
        nativePanel.showPermissionRequest(reqId, toolDisplayName, description) { response ->
            _pendingPermissionCallbacks.remove(reqId)
            onRespond(response)
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
    ) = nativePanel.showAskUserRequest(reqId, question, options, deadlineEpochMs, onRespond, onExtend, onSuperseded)

    // ── PermissionPromptProvider (Java interface bridge) ────────────────────────

    override fun showPermissionPrompt(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: Consumer<PermissionResponse>
    ) = showPermissionRequest(reqId, toolDisplayName, description) { onRespond.accept(it) }

    override fun showAskUserPrompt(
        reqId: String,
        question: String,
        options: MutableList<String>,
        deadlineEpochMs: Long,
        onRespond: Consumer<String>,
        onExtend: Supplier<Long>,
        onSuperseded: Runnable
    ) = showAskUserRequest(reqId, question, options, deadlineEpochMs,
        { onRespond.accept(it) }, { onExtend.get() }, { onSuperseded.run() })

    // ── History management ─────────────────────────────────────────────────────

    var onLoadMoreRequested: (() -> Unit)?
        get() = nativePanel.onLoadMoreRequested
        set(value) {
            nativePanel.onLoadMoreRequested = value
        }

    fun appendEntries(entries: List<EntryData>, totalPromptCount: Int = -1) =
        nativePanel.appendEntries(entries, totalPromptCount)

    fun prependEntries(entries: List<EntryData>) = nativePanel.prependEntries(entries)

    fun showLoadMore(deferredCount: Int) = nativePanel.showLoadMore(deferredCount)

    fun hideLoadMore() = nativePanel.hideLoadMore()

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
        instances.remove(project, this)
        PermissionPromptProviderHolder.unregister(project)
        nativePanel.dispose()
    }
}

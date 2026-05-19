package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.CardLayout
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Delegates all [ChatPanelApi] calls to both [jcefPanel] and [nativePanel]
 * simultaneously, so either can be shown at any time without losing history.
 *
 * Use [showJcef] / [showNative] to toggle the visible component. The
 * underlying [component] is a [CardLayout] container holding both sub-panels.
 *
 * Read-only export methods ([getConversationText], etc.) are delegated only to
 * [jcefPanel] since it has the full serialization/export infrastructure.
 *
 * [hasPendingAskUserRequest] / [consumePendingAskUserResponse] / [clearPendingAskUserRequest]
 * are also delegated only to [jcefPanel] since interactive state must not be split.
 *
 * All calls to [nativePanel] are dispatched through [onEdt] to ensure they run on the
 * Event Dispatch Thread, since [NativeChatPanel] is a Swing component. [jcefPanel] calls
 * are thread-safe and do not need EDT dispatch.
 */
class BroadcastChatPanel(
    private val project: Project,
    val jcefPanel: ChatConsolePanel,
    val nativePanel: NativeChatPanel,
) : ChatPanelApi {

    companion object {
        private const val CARD_JCEF = "jcef"
        private const val CARD_NATIVE = "native"

        private val instances = ConcurrentHashMap<Project, BroadcastChatPanel>()

        fun getInstance(project: Project): BroadcastChatPanel? = instances[project]
    }

    init {
        instances[project] = this
    }

    private val cardContainer = JPanel(CardLayout()).apply {
        add(jcefPanel.component, CARD_JCEF)
        add(nativePanel.component, CARD_NATIVE)
    }

    override val component: JComponent = cardContainer

    fun showJcef() = (cardContainer.layout as CardLayout).show(cardContainer, CARD_JCEF)
    fun showNative() = (cardContainer.layout as CardLayout).show(cardContainer, CARD_NATIVE)

    fun toggle(native: Boolean) {
        if (native) showNative() else showJcef()
    }

    /** Runs [block] on the EDT. If already on EDT, runs immediately; otherwise posts via invokeLater. */
    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    // ── Callback vars ──────────────────────────────────────────────────────────
    // Stored here and fanned out to both panels.

    override var onQuickReply: ((String) -> Unit)?
        get() = jcefPanel.onQuickReply
        set(value) {
            jcefPanel.onQuickReply = value
            nativePanel.onQuickReply = value
        }

    override var onStatusMessage: ((type: String, message: String) -> Unit)?
        get() = jcefPanel.onStatusMessage
        set(value) {
            jcefPanel.onStatusMessage = value
            nativePanel.onStatusMessage = value
        }

    // ── Write methods — broadcast to both ─────────────────────────────────────

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
        onEdt { nativePanel.addPromptEntry(text, contextFiles, bubbleHtml) }
        return jcefPanel.addPromptEntry(text, contextFiles, bubbleHtml)
    }

    override fun removePromptEntry(entryId: String) {
        onEdt { nativePanel.removePromptEntry(entryId) }
        jcefPanel.removePromptEntry(entryId)
    }

    override fun startStreaming() {
        onEdt { nativePanel.startStreaming() }
        jcefPanel.startStreaming()
    }

    override fun appendText(text: String) {
        onEdt { nativePanel.appendText(text) }
        jcefPanel.appendText(text)
    }

    override fun appendThinkingText(text: String) {
        onEdt { nativePanel.appendThinkingText(text) }
        jcefPanel.appendThinkingText(text)
    }

    override fun collapseThinking() {
        onEdt { nativePanel.collapseThinking() }
        jcefPanel.collapseThinking()
    }

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) {
        onEdt { nativePanel.setCodeChangeStats(linesAdded, linesRemoved) }
        jcefPanel.setCodeChangeStats(linesAdded, linesRemoved)
    }

    override fun setCurrentModel(modelId: String) {
        onEdt { nativePanel.setCurrentModel(modelId) }
        jcefPanel.setCurrentModel(modelId)
    }

    override fun setCurrentProfile(profileId: String) {
        onEdt { nativePanel.setCurrentProfile(profileId) }
        jcefPanel.setCurrentProfile(profileId)
    }

    override fun setCurrentAgent(agentName: String, profileId: String, clientType: String) {
        onEdt { nativePanel.setCurrentAgent(agentName, profileId, clientType) }
        jcefPanel.setCurrentAgent(agentName, profileId, clientType)
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) {
        onEdt { nativePanel.addContextFilesEntry(files) }
        jcefPanel.addContextFilesEntry(files)
    }

    override fun addImageThumbnails(images: List<ChatPanelApi.ImageAttachment>) {
        onEdt { nativePanel.addImageThumbnails(images) }
        jcefPanel.addImageThumbnails(images)
    }

    override fun addToolCallEntry(id: String, title: String, arguments: String?, kind: String?, isMcpHandled: Boolean) {
        onEdt { nativePanel.addToolCallEntry(id, title, arguments, kind, isMcpHandled) }
        jcefPanel.addToolCallEntry(id, title, arguments, kind, isMcpHandled)
    }

    override fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) {
        onEdt { nativePanel.updateToolCall(id, status, update) }
        jcefPanel.updateToolCall(id, status, update)
    }

    override fun addSubAgentEntry(
        id: String,
        agentType: String,
        description: String,
        prompt: String?,
        initialState: ChatPanelApi.SubAgentInitialState
    ) {
        onEdt { nativePanel.addSubAgentEntry(id, agentType, description, prompt, initialState) }
        jcefPanel.addSubAgentEntry(id, agentType, description, prompt, initialState)
    }

    override fun updateSubAgentResult(
        id: String,
        status: String,
        result: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) {
        onEdt { nativePanel.updateSubAgentResult(id, status, result, description, autoDenied, denialReason) }
        jcefPanel.updateSubAgentResult(id, status, result, description, autoDenied, denialReason)
    }

    override fun addSubAgentToolCall(
        subAgentId: String, toolId: String, title: String,
        arguments: String?, kind: String?
    ) {
        onEdt { nativePanel.addSubAgentToolCall(subAgentId, toolId, title, arguments, kind) }
        jcefPanel.addSubAgentToolCall(subAgentId, toolId, title, arguments, kind)
    }

    override fun updateSubAgentToolCall(
        toolId: String, status: String, details: String?, description: String?,
        autoDenied: Boolean, denialReason: String?
    ) {
        onEdt { nativePanel.updateSubAgentToolCall(toolId, status, details, description, autoDenied, denialReason) }
        jcefPanel.updateSubAgentToolCall(toolId, status, details, description, autoDenied, denialReason)
    }

    override fun addErrorEntry(message: String) {
        onEdt { nativePanel.addErrorEntry(message) }
        jcefPanel.addErrorEntry(message)
    }

    override fun addInfoEntry(message: String) {
        onEdt { nativePanel.addInfoEntry(message) }
        jcefPanel.addInfoEntry(message)
    }

    override fun addSessionSeparator(timestamp: String, agent: String) {
        onEdt { nativePanel.addSessionSeparator(timestamp, agent) }
        jcefPanel.addSessionSeparator(timestamp, agent)
    }

    override fun showPlaceholder(text: String) {
        onEdt { nativePanel.showPlaceholder(text) }
        jcefPanel.showPlaceholder(text)
    }

    override fun clear() {
        onEdt { nativePanel.clear() }
        jcefPanel.clear()
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        onEdt { nativePanel.finishResponse(toolCallCount, modelId, multiplier) }
        jcefPanel.finishResponse(toolCallCount, modelId, multiplier)
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        onEdt { nativePanel.emitTurnStats(stats) }
        jcefPanel.emitTurnStats(stats)
    }

    override fun showQuickReplies(options: List<String>) {
        onEdt { nativePanel.showQuickReplies(options) }
        jcefPanel.showQuickReplies(options)
    }

    override fun disableQuickReplies() {
        onEdt { nativePanel.disableQuickReplies() }
        jcefPanel.disableQuickReplies()
    }

    override fun cancelAllRunning() {
        onEdt { nativePanel.cancelAllRunning() }
        jcefPanel.cancelAllRunning()
    }

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) {
        onEdt { nativePanel.showNudgeBubble(id, text, source) }
        jcefPanel.showNudgeBubble(id, text, source)
    }

    override fun resolveNudgeBubble(id: String) {
        onEdt { nativePanel.resolveNudgeBubble(id) }
        jcefPanel.resolveNudgeBubble(id)
    }

    override fun removeNudgeBubble(id: String) {
        onEdt { nativePanel.removeNudgeBubble(id) }
        jcefPanel.removeNudgeBubble(id)
    }

    override fun addNudgeEntry(id: String, text: String, source: NudgeSource) {
        onEdt { nativePanel.addNudgeEntry(id, text, source) }
        jcefPanel.addNudgeEntry(id, text, source)
    }

    override fun showQueuedMessage(id: String, text: String) {
        onEdt { nativePanel.showQueuedMessage(id, text) }
        jcefPanel.showQueuedMessage(id, text)
    }

    override fun removeQueuedMessage(id: String) {
        onEdt { nativePanel.removeQueuedMessage(id) }
        jcefPanel.removeQueuedMessage(id)
    }

    override fun removeQueuedMessageByText(text: String) {
        onEdt { nativePanel.removeQueuedMessageByText(text) }
        jcefPanel.removeQueuedMessageByText(text)
    }

    override fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (PermissionResponse) -> Unit
    ) {
        onEdt { nativePanel.showPermissionRequest(reqId, toolDisplayName, description, onRespond) }
        jcefPanel.showPermissionRequest(reqId, toolDisplayName, description, onRespond)
    }

    override fun showAskUserRequest(
        reqId: String,
        question: String,
        options: List<String>,
        deadlineEpochMs: Long,
        onRespond: (String) -> Unit,
        onExtend: () -> Long,
        onSuperseded: () -> Unit,
    ) {
        onEdt {
            nativePanel.showAskUserRequest(
                reqId, question, options, deadlineEpochMs, onRespond, onExtend, onSuperseded
            )
        }
        jcefPanel.showAskUserRequest(reqId, question, options, deadlineEpochMs, onRespond, onExtend, onSuperseded)
    }

    // ── History / persistence — broadcast to both ───────────────────────────

    var onLoadMoreRequested: (() -> Unit)?
        get() = jcefPanel.onLoadMoreRequested
        set(value) {
            jcefPanel.onLoadMoreRequested = value
            nativePanel.onLoadMoreRequested = value
        }

    fun appendEntries(entries: List<EntryData>, totalPromptCount: Int = -1) {
        jcefPanel.appendEntries(entries, totalPromptCount)
        onEdt { nativePanel.appendEntries(entries, totalPromptCount) }
    }

    fun prependEntries(entries: List<EntryData>) {
        jcefPanel.prependEntries(entries)
        onEdt { nativePanel.prependEntries(entries) }
    }

    fun showLoadMore(deferredCount: Int) {
        jcefPanel.showLoadMore(deferredCount)
        onEdt { nativePanel.showLoadMore(deferredCount) }
    }

    fun hideLoadMore() {
        jcefPanel.hideLoadMore()
        onEdt { nativePanel.hideLoadMore() }
    }

    fun setDomMessageLimit(limit: Int) {
        jcefPanel.setDomMessageLimit(limit)
    }

    // ── Read-only / interactive state — JCEF only ──────────────────────────────

    override fun hasContent(): Boolean = jcefPanel.hasContent()

    override fun getConversationText(): String = jcefPanel.getConversationText()
    override fun getCompressedSummary(maxChars: Int): String = jcefPanel.getCompressedSummary(maxChars)
    override fun getConversationHtml(): String = jcefPanel.getConversationHtml()
    override fun getLastResponseText(): String = jcefPanel.getLastResponseText()
    override fun getPageHtml(): String? = jcefPanel.getPageHtml()

    override fun hasPendingAskUserRequest(): Boolean = jcefPanel.hasPendingAskUserRequest()
    override fun consumePendingAskUserResponse(response: String): Boolean =
        jcefPanel.consumePendingAskUserResponse(response)

    override fun clearPendingAskUserRequest(reqId: String?) = jcefPanel.clearPendingAskUserRequest(reqId)

    // ── Dispose ────────────────────────────────────────────────────────────────

    override fun dispose() {
        instances.remove(project, this)
        Disposer.dispose(jcefPanel)
        Disposer.dispose(nativePanel)
    }
}

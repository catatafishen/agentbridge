package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.intellij.openapi.util.Disposer
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel

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
 */
class BroadcastChatPanel(
    val jcefPanel: ChatConsolePanel,
    val nativePanel: NativeChatPanel,
) : ChatPanelApi {

    companion object {
        private const val CARD_JCEF = "jcef"
        private const val CARD_NATIVE = "native"
    }

    private val cardContainer = JPanel(CardLayout()).apply {
        add(jcefPanel.component, CARD_JCEF)
        add(nativePanel.component, CARD_NATIVE)
    }

    override val component: JComponent = cardContainer

    fun showJcef() = (cardContainer.layout as CardLayout).show(cardContainer, CARD_JCEF)
    fun showNative() = (cardContainer.layout as CardLayout).show(cardContainer, CARD_NATIVE)
    fun isShowingNative(): Boolean {
        // CardLayout has no public getter, so track it ourselves
        return _showingNative
    }

    private var _showingNative = false

    fun toggle(native: Boolean) {
        _showingNative = native
        if (native) showNative() else showJcef()
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
        nativePanel.addPromptEntry(text, contextFiles, bubbleHtml)
        return jcefPanel.addPromptEntry(text, contextFiles, bubbleHtml)
    }

    override fun removePromptEntry(entryId: String) {
        nativePanel.removePromptEntry(entryId)
        jcefPanel.removePromptEntry(entryId)
    }

    override fun startStreaming() {
        nativePanel.startStreaming()
        jcefPanel.startStreaming()
    }

    override fun appendText(text: String) {
        nativePanel.appendText(text)
        jcefPanel.appendText(text)
    }

    override fun appendThinkingText(text: String) {
        nativePanel.appendThinkingText(text)
        jcefPanel.appendThinkingText(text)
    }

    override fun collapseThinking() {
        nativePanel.collapseThinking()
        jcefPanel.collapseThinking()
    }

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) {
        nativePanel.setCodeChangeStats(linesAdded, linesRemoved)
        jcefPanel.setCodeChangeStats(linesAdded, linesRemoved)
    }

    override fun setCurrentModel(modelId: String) {
        nativePanel.setCurrentModel(modelId)
        jcefPanel.setCurrentModel(modelId)
    }

    override fun setCurrentProfile(profileId: String) {
        nativePanel.setCurrentProfile(profileId)
        jcefPanel.setCurrentProfile(profileId)
    }

    override fun setCurrentAgent(agentName: String, profileId: String, clientType: String) {
        nativePanel.setCurrentAgent(agentName, profileId, clientType)
        jcefPanel.setCurrentAgent(agentName, profileId, clientType)
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) {
        nativePanel.addContextFilesEntry(files)
        jcefPanel.addContextFilesEntry(files)
    }

    override fun addToolCallEntry(id: String, title: String, arguments: String?, kind: String?, isMcpHandled: Boolean) {
        nativePanel.addToolCallEntry(id, title, arguments, kind, isMcpHandled)
        jcefPanel.addToolCallEntry(id, title, arguments, kind, isMcpHandled)
    }

    override fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) {
        nativePanel.updateToolCall(id, status, update)
        jcefPanel.updateToolCall(id, status, update)
    }

    override fun addSubAgentEntry(
        id: String,
        agentType: String,
        description: String,
        prompt: String?,
        initialState: ChatPanelApi.SubAgentInitialState
    ) {
        nativePanel.addSubAgentEntry(id, agentType, description, prompt, initialState)
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
        nativePanel.updateSubAgentResult(id, status, result, description, autoDenied, denialReason)
        jcefPanel.updateSubAgentResult(id, status, result, description, autoDenied, denialReason)
    }

    override fun addSubAgentToolCall(
        subAgentId: String, toolId: String, title: String,
        arguments: String?, kind: String?
    ) {
        nativePanel.addSubAgentToolCall(subAgentId, toolId, title, arguments, kind)
        jcefPanel.addSubAgentToolCall(subAgentId, toolId, title, arguments, kind)
    }

    override fun updateSubAgentToolCall(
        toolId: String, status: String, details: String?, description: String?,
        autoDenied: Boolean, denialReason: String?
    ) {
        nativePanel.updateSubAgentToolCall(toolId, status, details, description, autoDenied, denialReason)
        jcefPanel.updateSubAgentToolCall(toolId, status, details, description, autoDenied, denialReason)
    }

    override fun addErrorEntry(message: String) {
        nativePanel.addErrorEntry(message)
        jcefPanel.addErrorEntry(message)
    }

    override fun addInfoEntry(message: String) {
        nativePanel.addInfoEntry(message)
        jcefPanel.addInfoEntry(message)
    }

    override fun addSessionSeparator(timestamp: String, agent: String) {
        nativePanel.addSessionSeparator(timestamp, agent)
        jcefPanel.addSessionSeparator(timestamp, agent)
    }

    override fun showPlaceholder(text: String) {
        nativePanel.showPlaceholder(text)
        jcefPanel.showPlaceholder(text)
    }

    override fun clear() {
        nativePanel.clear()
        jcefPanel.clear()
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        nativePanel.finishResponse(toolCallCount, modelId, multiplier)
        jcefPanel.finishResponse(toolCallCount, modelId, multiplier)
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        nativePanel.emitTurnStats(stats)
        jcefPanel.emitTurnStats(stats)
    }

    override fun showQuickReplies(options: List<String>) {
        nativePanel.showQuickReplies(options)
        jcefPanel.showQuickReplies(options)
    }

    override fun disableQuickReplies() {
        nativePanel.disableQuickReplies()
        jcefPanel.disableQuickReplies()
    }

    override fun cancelAllRunning() {
        nativePanel.cancelAllRunning()
        jcefPanel.cancelAllRunning()
    }

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) {
        nativePanel.showNudgeBubble(id, text, source)
        jcefPanel.showNudgeBubble(id, text, source)
    }

    override fun resolveNudgeBubble(id: String) {
        nativePanel.resolveNudgeBubble(id)
        jcefPanel.resolveNudgeBubble(id)
    }

    override fun removeNudgeBubble(id: String) {
        nativePanel.removeNudgeBubble(id)
        jcefPanel.removeNudgeBubble(id)
    }

    override fun addNudgeEntry(id: String, text: String, source: NudgeSource) {
        nativePanel.addNudgeEntry(id, text, source)
        jcefPanel.addNudgeEntry(id, text, source)
    }

    override fun showQueuedMessage(id: String, text: String) {
        nativePanel.showQueuedMessage(id, text)
        jcefPanel.showQueuedMessage(id, text)
    }

    override fun removeQueuedMessage(id: String) {
        nativePanel.removeQueuedMessage(id)
        jcefPanel.removeQueuedMessage(id)
    }

    override fun removeQueuedMessageByText(text: String) {
        nativePanel.removeQueuedMessageByText(text)
        jcefPanel.removeQueuedMessageByText(text)
    }

    override fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (PermissionResponse) -> Unit
    ) {
        nativePanel.showPermissionRequest(reqId, toolDisplayName, description, onRespond)
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
        nativePanel.showAskUserRequest(reqId, question, options, deadlineEpochMs, onRespond, onExtend, onSuperseded)
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
        nativePanel.appendEntries(entries, totalPromptCount)
    }

    fun prependEntries(entries: List<EntryData>) {
        jcefPanel.prependEntries(entries)
        nativePanel.prependEntries(entries)
    }

    fun showLoadMore(deferredCount: Int) {
        jcefPanel.showLoadMore(deferredCount)
        nativePanel.showLoadMore(deferredCount)
    }

    fun hideLoadMore() {
        jcefPanel.hideLoadMore()
        nativePanel.hideLoadMore()
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
        Disposer.dispose(jcefPanel)
        Disposer.dispose(nativePanel)
    }
}

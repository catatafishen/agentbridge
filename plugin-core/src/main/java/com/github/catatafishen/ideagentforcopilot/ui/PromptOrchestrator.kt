package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest
import com.github.catatafishen.ideagentforcopilot.acp.model.ResourceReference
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate
import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient
import com.github.catatafishen.ideagentforcopilot.agent.AgentException
import com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse
import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Callbacks the orchestrator invokes back into the UI layer for side-effects it cannot own.
 * Implemented by ChatToolWindowContent.
 */
data class PromptOrchestratorCallbacks(
    val onSendingStateChanged: (Boolean) -> Unit,
    val saveConversation: () -> Unit,
    val saveConversationThrottled: () -> Unit,
    val notifyIfUnfocused: (toolCallCount: Int) -> Unit,
    val saveTurnStatistics: (prompt: String, toolCallCount: Int, modelId: String) -> Unit,
    val updateSessionInfo: () -> Unit,
    val requestFocusAfterTurn: () -> Unit,
    val onTimerIncrementToolCalls: () -> Unit,
    val onTimerRecordUsage: (inputTokens: Int, outputTokens: Int, costUsd: Double) -> Unit,
    /** Called for plan-tree and file-tracking side-effects (remains in ChatToolWindowContent). */
    val onClientUpdate: (SessionUpdate) -> Unit,
)

/** Stored banner message to re-display at the start of the next prompt turn. */
internal data class PendingBanner(val message: String, val level: SessionUpdate.BannerLevel)

/**
 * Owns prompt dispatch, streaming update handling, and error recovery.
 * Extracted from ChatToolWindowContent to separate protocol logic from UI wiring.
 */
class PromptOrchestrator(
    private val project: Project,
    private val agentManager: ActiveAgentManager,
    private val billing: BillingManager,
    private val contextManager: PromptContextManager,
    private val authService: AuthLoginService,
    private val consolePanel: () -> ChatPanelApi,
    private val copilotBanner: () -> AuthSetupBanner?,
    private val statusBanner: () -> StatusBanner?,
    private val callbacks: PromptOrchestratorCallbacks,
) {
    private val log = Logger.getInstance(PromptOrchestrator::class.java)

    internal var currentSessionId: String? = null
    internal var conversationSummaryInjected: Boolean = false
    private var currentPromptThread: Thread? = null

    private var turnToolCallCount = 0
    private var turnInputTokens = 0
    private var turnOutputTokens = 0
    private var turnCostUsd = 0.0
    private var turnModelId = ""
    private var activeSubAgentId: String? = null
    private val toolCallTitles = mutableMapOf<String, String>()
    private var pendingBanner: PendingBanner? = null

    /** Executes a prompt on the calling thread (must be called from a background thread). */
    fun execute(prompt: String, contextItems: List<ContextItemData>, selectedModelId: String) {
        currentPromptThread = Thread.currentThread()
        try {
            executePrompt(prompt, contextItems, selectedModelId)
        } finally {
            currentPromptThread = null
            callbacks.onSendingStateChanged(false)
        }
    }

    /** Cancels the running prompt: interrupts the thread and cancels the remote session. */
    fun stop() {
        val sessionId = currentSessionId
        if (sessionId != null) {
            try {
                agentManager.client.cancelSession(sessionId)
            } catch (_: Exception) {
                // Best-effort cancellation
            }
        }
        currentPromptThread?.interrupt()
        consolePanel().cancelAllRunning()
        consolePanel().addErrorEntry("Stopped by user")
    }

    private fun executePrompt(prompt: String, contextItems: List<ContextItemData>, selectedModelId: String) {
        try {
            if (isBlockedByAuth()) return

            val pending = pendingBanner
            if (pending != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (pending.level == SessionUpdate.BannerLevel.ERROR) statusBanner()?.showError(pending.message)
                    else statusBanner()?.showWarning(pending.message)
                }
            }

            val client = agentManager.client
            val sessionId = ensureSessionCreated(client)
            wirePermissionListener(client)

            val modelId = prepareModelAndTurnState(selectedModelId)
            val references = contextManager.buildContextReferences(contextItems.ifEmpty { null })
            val effectivePrompt = buildEffectivePrompt(prompt)
            addContextEntries(references, contextItems)

            dispatchPromptWithRetry(client, sessionId, effectivePrompt, modelId, references)
            handlePromptCompletion(prompt)
        } catch (e: Exception) {
            handlePromptError(e)
        }
    }

    private fun isBlockedByAuth(): Boolean {
        if (authService.pendingAuthError == null) return false
        ApplicationManager.getApplication().invokeLater {
            consolePanel().addErrorEntry("Not signed in. Use the Sign In button in the banner above.")
            copilotBanner()?.triggerCheck()
        }
        return true
    }

    private fun ensureSessionCreated(client: AbstractAgentClient): String {
        if (currentSessionId == null) {
            currentSessionId = client.createSession(project.basePath)
            callbacks.updateSessionInfo()
            val savedModel = agentManager.settings.selectedModel
            if (!savedModel.isNullOrEmpty()) {
                try {
                    client.setModel(currentSessionId!!, savedModel)
                } catch (ex: Exception) {
                    log.warn("Failed to set model $savedModel on new session", ex)
                }
            }
            for (option in client.listSessionOptions()) {
                val savedValue = agentManager.settings.getSessionOptionValue(option.key)
                if (savedValue.isNotEmpty()) {
                    try {
                        client.setSessionOption(currentSessionId!!, option.key, savedValue)
                    } catch (ex: Exception) {
                        log.warn("Failed to restore session option ${option.key}=$savedValue", ex)
                    }
                }
            }
        }
        return currentSessionId!!
    }

    private fun wirePermissionListener(client: AbstractAgentClient) {
        client.setPermissionRequestListener { prompt: AbstractAgentClient.PermissionPrompt ->
            ApplicationManager.getApplication().invokeLater {
                consolePanel().showPermissionRequest(
                    prompt.toolCallId(), prompt.toolName(), prompt.arguments() ?: ""
                ) { response ->
                    when (response) {
                        PermissionResponse.ALLOW_ONCE, PermissionResponse.ALLOW_SESSION ->
                            prompt.allow(response.name.lowercase())

                        PermissionResponse.DENY -> prompt.deny("Denied by user")
                    }
                }
                notifyPermissionRequestIfUnfocused(prompt.toolName())
            }
        }
    }

    private fun notifyPermissionRequestIfUnfocused(toolDisplayName: String) {
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project) ?: return
        if (frame.isFocused) return
        val title = agentManager.activeProfile.displayName
        val content = "Permission request: $toolDisplayName"
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("IDE Agent for Copilot")
            ?.createNotification(title, content, com.intellij.notification.NotificationType.INFORMATION)
            ?.notify(project)
    }

    private fun prepareModelAndTurnState(selectedModelId: String): String {
        turnToolCallCount = 0
        turnInputTokens = 0
        turnOutputTokens = 0
        turnCostUsd = 0.0
        activeSubAgentId = null
        turnModelId = selectedModelId
        ApplicationManager.getApplication().invokeLater {
            consolePanel().setCurrentProfile(agentManager.activeProfileId)
            consolePanel().setCurrentModel(selectedModelId)
            // Show the multiplier chip for clients that support it (e.g. Copilot), only when known.
            // For non-multiplier clients the chip is token/cost-based and shown after completion.
            if (agentManager.client.supportsMultiplier()) {
                val multiplier = getModelMultiplier(selectedModelId)
                if (multiplier != null) {
                    consolePanel().setPromptStats(selectedModelId, multiplier)
                }
            }
        }
        return selectedModelId
    }

    private fun addContextEntries(references: List<ResourceReference>, contextItems: List<ContextItemData>) {
        if (references.isNotEmpty() && contextItems.isNotEmpty()) {
            val contextFiles = contextItems.map { Pair(it.name, it.path) }
            consolePanel().addContextFilesEntry(contextFiles)
        }
    }

    private fun buildEffectivePrompt(prompt: String): String {
        var effective = prompt
        if (!conversationSummaryInjected) {
            conversationSummaryInjected = true
            val summary = consolePanel().getCompressedSummary()
            if (summary.isNotEmpty()) {
                effective = "$summary\n\n$effective"
            }
        }
        return effective
    }

    private fun dispatchPromptWithRetry(
        client: AbstractAgentClient,
        initialSessionId: String,
        effectivePrompt: String,
        modelId: String,
        references: List<ResourceReference>,
    ) {
        val promptBlocks = buildPromptBlocks(effectivePrompt, references)
        val onUpdate = java.util.function.Consumer<SessionUpdate> { update ->
            handlePromptStreamingUpdate(update)
        }
        val sendCall: (String) -> Unit = { sid ->
            val request =
                PromptRequest(sid, promptBlocks, modelId.takeIf { it.isNotEmpty() }, client.getEffectiveModeSlug())
            client.sendPrompt(request, onUpdate)
        }
        sendWithSessionRetry(client, initialSessionId, sendCall)
    }

    private fun buildPromptBlocks(prompt: String, references: List<ResourceReference>): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>(ContentBlock.Text(prompt))
        for (ref in references) {
            blocks.add(
                ContentBlock.Resource(
                    ContentBlock.ResourceLink(
                        ref.uri(), null, ref.mimeType(), ref.text(), null
                    )
                )
            )
        }
        return blocks
    }

    /**
     * Attempts to call [sendCall] with [initialSessionId]. If it fails with a "not found" session
     * error, invalidates the current session, creates a fresh one, and retries once.
     */
    private fun sendWithSessionRetry(
        client: AbstractAgentClient,
        initialSessionId: String,
        sendCall: (String) -> Unit,
    ) {
        try {
            sendCall(initialSessionId)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("not found", ignoreCase = true)) {
                log.info("Session expired ('not found'), creating new session and retrying")
                currentSessionId = null
                val newSessionId = ensureSessionCreated(client)
                sendCall(newSessionId)
            } else {
                throw e
            }
        }
    }

    private fun handlePromptCompletion(prompt: String) {
        PsiBridgeService.getInstance(project).flushPendingAutoFormat()
        PsiBridgeService.getInstance(project).clearFileAccessTracking()
        pendingBanner = null

        val client = agentManager.client
        if (client.supportsMultiplier()) {
            val multiplier = getModelMultiplier(turnModelId)
            consolePanel().finishResponse(turnToolCallCount, turnModelId, multiplier ?: "")
            billing.recordTurnCompleted(multiplier)
            callbacks.onTimerRecordUsage(0, 0, 0.0)
        } else {
            consolePanel().finishResponse(turnToolCallCount, turnModelId, "")
            val usageChip = BillingManager.formatUsageChip(turnInputTokens, turnOutputTokens, turnCostUsd)
            if (usageChip.isNotEmpty() && usageChip != "1x") {
                ApplicationManager.getApplication().invokeLater {
                    consolePanel().setPromptStats(turnModelId, usageChip)
                }
            }
            callbacks.onTimerRecordUsage(turnInputTokens, turnOutputTokens, turnCostUsd)
        }

        callbacks.notifyIfUnfocused(turnToolCallCount)
        callbacks.saveTurnStatistics(prompt, turnToolCallCount, turnModelId)
        callbacks.saveConversation()

        val lastResponse = consolePanel().getLastResponseText()
        val quickReplies = detectQuickReplies(lastResponse)
        if (quickReplies.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater { consolePanel().showQuickReplies(quickReplies) }
        }

        ApplicationManager.getApplication().invokeLater {
            consolePanel().component.revalidate()
            consolePanel().component.repaint()
            if (ActiveAgentManager.getFollowAgentFiles(project)) {
                callbacks.requestFocusAfterTurn()
            }
        }
    }

    private fun handlePromptStreamingUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                val text = update.text()
                ApplicationManager.getApplication().invokeLater {
                    if (currentPromptThread != null) consolePanel().appendText(text)
                }
            }

            is SessionUpdate.ToolCall -> {
                handleStreamingToolCall(update)
                handleClientUpdate(update)
            }

            is SessionUpdate.ToolCallUpdate -> {
                handleStreamingToolCallUpdate(update)
                handleClientUpdate(update)
            }

            is SessionUpdate.AgentThoughtChunk -> consolePanel().appendThinkingText(update.text())
            is SessionUpdate.TurnUsage -> {
                turnInputTokens = update.inputTokens()
                turnOutputTokens = update.outputTokens()
                turnCostUsd = update.costUsd()
            }

            is SessionUpdate.Banner -> handleStreamingBanner(update)
            is SessionUpdate.Plan -> handleClientUpdate(update)
            is SessionUpdate.AvailableCommandsChanged,
            is SessionUpdate.AvailableModesChanged -> { /* handled by AcpClient internally */
            }
        }
    }

    private fun handleClientUpdate(update: SessionUpdate) {
        callbacks.onClientUpdate(update)
    }

    private fun handleStreamingBanner(banner: SessionUpdate.Banner) {
        val msg = banner.message()
        if (banner.clearOn() == SessionUpdate.ClearOn.NEXT_SUCCESS) {
            pendingBanner = PendingBanner(msg, banner.level())
        }
        ApplicationManager.getApplication().invokeLater {
            if (banner.level() == SessionUpdate.BannerLevel.ERROR) statusBanner()?.showError(msg)
            else statusBanner()?.showWarning(msg)
        }
    }

    private fun handleStreamingToolCall(toolCall: SessionUpdate.ToolCall) {
        val title = toolCall.title()
        val toolCallId = toolCall.toolCallId()
        val rawKind = toolCall.kind()?.value() ?: "other"
        val kind = if (rawKind == "other") {
            val toolName = title.removePrefix("agentbridge-").removePrefix("@agentbridge/")
            val def = com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.getInstance(project).findById(toolName)
            when {
                def == null -> "other"
                def.isReadOnly() -> "read"
                def.isDestructive() -> "execute"
                else -> "edit"
            }
        } else rawKind
        val arguments = toolCall.arguments()
        if (toolCallId.isEmpty()) return
        if (toolCall.isSubAgent()) {
            val agentType = toolCall.agentType()!!
            turnToolCallCount++
            callbacks.onTimerIncrementToolCalls()
            toolCallTitles[toolCallId] = "task"
            activeSubAgentId = toolCallId
            agentManager.client.setSubAgentActive(true)
            agentManager.settings.setActiveAgentLabel(agentType)
            val description =
                toolCall.subAgentDescription()?.takeIf { it.isNotBlank() } ?: title.ifBlank { "Sub-agent task" }
            consolePanel().addSubAgentEntry(toolCallId, agentType, description, toolCall.subAgentPrompt())
        } else if (activeSubAgentId != null) {
            turnToolCallCount++
            callbacks.onTimerIncrementToolCalls()
            toolCallTitles[toolCallId] = "subagent_internal"
            consolePanel().addSubAgentToolCall(activeSubAgentId!!, toolCallId, title, arguments, kind)
        } else {
            turnToolCallCount++
            callbacks.onTimerIncrementToolCalls()
            toolCallTitles[toolCallId] = title
            consolePanel().addToolCallEntry(toolCallId, title, arguments, kind)
        }

        // Automatic file navigation for "follow agent" feature.
        // We trigger it here when the tool call starts so the UI responds immediately.
        if (ActiveAgentManager.getFollowAgentFiles(project) && toolCall.filePaths().isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                FileNavigator(project).handleFileLink(toolCall.filePaths()[0])
            }
        }
    }

    private fun handleStreamingToolCallUpdate(update: SessionUpdate.ToolCallUpdate) {
        val status = update.status()
        val toolCallId = update.toolCallId()
        val result = update.result()
        val description = update.description()
        val callType = toolCallTitles[toolCallId]
        val isSubAgent = callType == "task"
        val isInternal = callType == "subagent_internal"
        if (status == SessionUpdate.ToolCallStatus.COMPLETED) {
            if (isSubAgent) {
                activeSubAgentId = null
                agentManager.client.setSubAgentActive(false)
                agentManager.settings.setActiveAgentLabel(null)
                consolePanel().updateSubAgentResult(toolCallId, "completed", result, description)
            } else if (isInternal) {
                consolePanel().updateSubAgentToolCall(toolCallId, "completed", result, description)
            } else {
                consolePanel().updateToolCall(toolCallId, "completed", result, description)
            }
        } else if (status == SessionUpdate.ToolCallStatus.FAILED) {
            val error = update.error() ?: result ?: "Unknown error"
            if (isSubAgent) {
                activeSubAgentId = null
                agentManager.client.setSubAgentActive(false)
                agentManager.settings.setActiveAgentLabel(null)
                consolePanel().updateSubAgentResult(toolCallId, "failed", error, description)
            } else if (isInternal) {
                consolePanel().updateSubAgentToolCall(toolCallId, "failed", error, description)
            } else {
                consolePanel().updateToolCall(toolCallId, "failed", error, description)
            }
        } else if (status == SessionUpdate.ToolCallStatus.IN_PROGRESS) {
            // Keep the "running" state in UI without marking it as success or failure yet.
            // This prevents tool chips from showing red (failed) prematurely.
            if (isSubAgent) {
                consolePanel().updateSubAgentResult(toolCallId, "running", null, description)
            } else if (isInternal) {
                consolePanel().updateSubAgentToolCall(toolCallId, "running", null, description)
            } else {
                consolePanel().updateToolCall(toolCallId, "running", null, description)
            }
        }
        if (status == SessionUpdate.ToolCallStatus.COMPLETED || status == SessionUpdate.ToolCallStatus.FAILED) {
            callbacks.saveConversationThrottled()
        }
    }

    private fun handlePromptError(e: Exception) {
        val isCancelled = e is InterruptedException || e.cause is InterruptedException
        var msg = if (isCancelled) "Request cancelled" else e.message ?: "Unknown error"

        // For ACP errors, ensure the message is descriptive
        if (e is AgentException && msg.startsWith("(") && msg.contains(")")) {
            // Keep the enhanced message format: (code) Message: Data
        } else if (e is AgentException) {
            msg = "ACP error: $msg"
        }

        consolePanel().cancelAllRunning()
        consolePanel().finishResponse(turnToolCallCount, turnModelId, "")
        callbacks.saveConversation()

        if (authService.isAuthenticationError(msg)) {
            authService.markAuthError(msg)
            copilotBanner()?.triggerCheck()
            consolePanel().addErrorEntry("Error: $msg")
            e.printStackTrace()
            return
        }

        val isRecoverable = isCancelled || (e is AgentException && e.isRecoverable)
        if (!isRecoverable) {
            currentSessionId = null
            callbacks.updateSessionInfo()
        }

        consolePanel().addErrorEntry("Error: $msg")
        if (!isCancelled) {
            statusBanner()?.showError(msg, "Reconnect") { reconnectAfterError() }
        }

        e.printStackTrace()
    }

    private fun reconnectAfterError() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                agentManager.restart()
                ApplicationManager.getApplication().invokeLater {
                    statusBanner()?.showInfo("Reconnected — ready for a new message.")
                }
            } catch (ex: Exception) {
                log.warn("Reconnect failed", ex)
                ApplicationManager.getApplication().invokeLater {
                    statusBanner()?.showError("Reconnect failed: ${ex.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun getModelMultiplier(modelId: String): String? =
        try {
            agentManager.client.getModelMultiplier(modelId)
        } catch (_: Exception) {
            null
        }

    private fun detectQuickReplies(responseText: String): List<String> {
        val match = QUICK_REPLY_TAG_REGEX.findAll(responseText).lastOrNull() ?: return emptyList()
        return match.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }
}

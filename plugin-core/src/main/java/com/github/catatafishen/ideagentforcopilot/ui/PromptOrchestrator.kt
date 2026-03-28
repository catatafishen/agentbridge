package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest
import com.github.catatafishen.ideagentforcopilot.acp.model.ResourceReference
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate
import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient
import com.github.catatafishen.ideagentforcopilot.agent.AgentException
import com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse
import com.github.catatafishen.ideagentforcopilot.psi.CodeChangeTracker
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
    val onTimerRecordUsage: (inputTokens: Int, outputTokens: Int, costUsd: Double?) -> Unit,
    val onTimerSetCodeChangeStats: (added: Int, removed: Int) -> Unit,
    /** Called for plan-tree and file-tracking side-effects (remains in ChatToolWindowContent). */
    val onClientUpdate: (SessionUpdate) -> Unit,
    /** Trigger a new prompt execution (used for queued messages). */
    val sendPromptDirectly: (String) -> Unit,
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

    /**
     * True while a stop has been requested for the current turn. Set before cancelling/interrupting,
     * cleared at the start of the next execute() call. Volatile so the background thread sees it
     * immediately even if the future resolved before Thread.interrupt() fired.
     */
    @Volatile
    private var stopped = false

    private var turnToolCallCount = 0
    private var turnInputTokens = 0
    private var turnOutputTokens = 0
    private var turnCostUsd: Double? = null
    private var turnModelId = ""
    private var activeSubAgentId: String? = null
    private val toolCallTitles = mutableMapOf<String, String>()
    private val toolCallArgs = mutableMapOf<String, String>() // arguments from tool_call_update
    private var pendingBanner: PendingBanner? = null
    private var codeChangeListener: Runnable? = null

    /** Executes a prompt on the calling thread (must be called from a background thread). */
    fun execute(prompt: String, contextItems: List<ContextItemData>, selectedModelId: String) {
        stopped = false
        // Clear any stale interrupt flag left by a previous stop() call so it doesn't fire
        // immediately on the first blocking operation in the new turn.
        Thread.interrupted()
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
        // Set the flag FIRST so the background thread sees it even if the remote session
        // completes the turn before Thread.interrupt() fires.
        stopped = true
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
            // If stop() was called, the remote turn may have ended cleanly (via turn/interrupt
            // response) without throwing. Treat it as a cancellation so handlePromptCompletion
            // is not invoked and the stale thread interrupt doesn't leak into the next turn.
            if (stopped) throw InterruptedException("Stopped by user")
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
                        PermissionResponse.ALLOW_ONCE, PermissionResponse.ALLOW_SESSION, PermissionResponse.ALLOW_ALWAYS ->
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
            .getNotificationGroup("AgentBridge Notifications")
            ?.createNotification(title, content, com.intellij.notification.NotificationType.INFORMATION)
            ?.notify(project)
    }

    private fun prepareModelAndTurnState(selectedModelId: String): String {
        turnToolCallCount = 0
        turnInputTokens = 0
        turnOutputTokens = 0
        turnCostUsd = null
        activeSubAgentId = null
        turnModelId = selectedModelId
        CodeChangeTracker.clear()

        // Register real-time listener so code-change chips update as each tool runs.
        codeChangeListener?.let { CodeChangeTracker.removeListener(it) }
        val listener = Runnable {
            ApplicationManager.getApplication().invokeLater {
                val changes = CodeChangeTracker.get()
                if (changes[0] > 0 || changes[1] > 0) {
                    consolePanel().setCodeChangeStats(changes[0], changes[1])
                    callbacks.onTimerSetCodeChangeStats(changes[0], changes[1])
                }
            }
        }
        codeChangeListener = listener
        CodeChangeTracker.addListener(listener)
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
        if (!conversationSummaryInjected && ActiveAgentManager.getInjectConversationHistory(project)) {
            conversationSummaryInjected = true
            val summary = consolePanel().getCompressedSummary()
            if (summary.isNotEmpty()) {
                // If prompt starts with /, put summary after to preserve slash command detection
                effective = if (prompt.trimStart().startsWith("/")) {
                    "$effective\n\n$summary"
                } else {
                    "$summary\n\n$effective"
                }
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
        // Check if the active agent supports resource references
        val profile = agentManager.activeProfile
        if (!profile.isSendResourceReferences && references.isNotEmpty()) {
            // Append context content directly to the prompt text for agents that don't support resources
            val promptWithContext = buildString {
                append(prompt)
                append("\n\n")
                for ((index, ref) in references.withIndex()) {
                    if (index > 0) append("\n\n")
                    append("--- Context: ${ref.uri()} ---\n")
                    append(ref.text())
                }
            }
            return listOf(ContentBlock.Text(promptWithContext))
        }

        // Standard path: send resources as separate content blocks
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
        // Unregister the real-time code-change listener before finalising the turn.
        codeChangeListener?.let { CodeChangeTracker.removeListener(it) }
        codeChangeListener = null

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

        val codeChanges = CodeChangeTracker.getAndClear()
        if (codeChanges[0] > 0 || codeChanges[1] > 0) {
            ApplicationManager.getApplication().invokeLater {
                consolePanel().setCodeChangeStats(codeChanges[0], codeChanges[1])
            }
        }

        callbacks.notifyIfUnfocused(turnToolCallCount)
        callbacks.saveTurnStatistics(prompt, turnToolCallCount, turnModelId)
        callbacks.saveConversation()

        val lastResponse = consolePanel().getLastResponseText()
        val quickReplies = detectQuickReplies(lastResponse)
        if (quickReplies.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater { consolePanel().showQuickReplies(quickReplies) }
        }

        val nextMsg = PsiBridgeService.getInstance(project).nextQueuedMessage
        if (nextMsg != null) {
            ApplicationManager.getApplication().invokeLater {
                consolePanel().removeQueuedMessageByText(nextMsg)
                callbacks.sendPromptDirectly(nextMsg)
            }
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
                    if (!stopped) consolePanel().appendText(text)
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

            is SessionUpdate.AgentThoughtChunk -> if (!stopped) consolePanel().appendThinkingText(update.text())
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

            is SessionUpdate.UserMessageChunk -> { /* replayed user messages during session/load — no-op during streaming */
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
        val kind = toolCall.kind()?.value() ?: "other"
        val arguments = toolCall.arguments()
        if (toolCallId.isEmpty()) return
        if (toolCall.isSubAgent()) {
            val agentType = toolCall.agentType() ?: return
            turnToolCallCount++
            callbacks.onTimerIncrementToolCalls()
            toolCallTitles[toolCallId] = "task"
            activeSubAgentId = toolCallId
            agentManager.client.setSubAgentActive(true)
            agentManager.settings.setActiveAgentLabel(agentType)
            consolePanel().setCurrentAgent(
                agentType,
                agentManager.activeProfile.id,
                agentManager.activeProfile.clientCssClass
            )
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
        val autoDenied = update.autoDenied()
        val denialReason = update.denialReason()
        val arguments = update.arguments() // raw arguments from tool_call_update

        // Store arguments for potential re-correlation
        if (arguments != null) {
            toolCallArgs[toolCallId] = arguments
        }

        val callType = toolCallTitles[toolCallId]
        val isSubAgent = callType == "task"
        val isInternal = callType == "subagent_internal"

        val uiStatus = when (status) {
            SessionUpdate.ToolCallStatus.COMPLETED -> "completed"
            SessionUpdate.ToolCallStatus.FAILED -> update.error() ?: result ?: "Unknown error"
            else -> "running"
        }

        updateToolCallUi(
            toolCallId,
            uiStatus,
            result,
            description,
            null,
            isSubAgent,
            isInternal,
            autoDenied,
            denialReason,
            arguments,
            callType
        )

        if (status == SessionUpdate.ToolCallStatus.COMPLETED || status == SessionUpdate.ToolCallStatus.FAILED) {
            callbacks.saveConversationThrottled()
        }
    }

    private fun updateToolCallUi(
        toolCallId: String,
        uiStatus: String,
        result: String?,
        description: String?,
        kind: String?,
        isSubAgent: Boolean,
        isInternal: Boolean,
        autoDenied: Boolean = false,
        denialReason: String? = null,
        arguments: String? = null,
        title: String? = null
    ) {
        if (isSubAgent) {
            if (uiStatus == "running") {
                // Sub-agent is still in progress — chip is already in running state from addSubAgentEntry;
                // calling updateSubAgentResult here would prematurely complete the chip in the JS layer.
                return
            }
            activeSubAgentId = null
            agentManager.client.setSubAgentActive(false)
            agentManager.settings.setActiveAgentLabel(null)
            consolePanel().setCurrentAgent(
                agentManager.activeProfile.displayName,
                agentManager.activeProfile.id,
                agentManager.activeProfile.clientCssClass
            )
            consolePanel().updateSubAgentResult(toolCallId, uiStatus, result, description, autoDenied, denialReason)
        } else if (isInternal) {
            consolePanel().updateSubAgentToolCall(toolCallId, uiStatus, result, description, autoDenied, denialReason)
        } else {
            consolePanel().updateToolCall(
                toolCallId,
                uiStatus,
                result,
                description,
                kind,
                autoDenied,
                denialReason,
                arguments,
                title
            )
        }
    }

    private fun handlePromptError(e: Exception) {
        val isCancelled = e is InterruptedException || e.cause is InterruptedException
        var msg = if (isCancelled) "Request cancelled" else e.message ?: "Unknown error"

        // Check if the root cause is an authentication error
        var cause: Throwable? = e
        while (cause != null) {
            val causeMsg = cause.message ?: ""
            if (authService.isAuthenticationError(causeMsg)) {
                log.info("Detected authentication error in cause chain: $causeMsg")
                msg = causeMsg
                break
            }
            cause = cause.cause
        }

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
            log.info("Authentication error detected: $msg")
            authService.markAuthError(msg)
            val banner = copilotBanner()
            log.info("Banner instance: $banner")
            banner?.triggerCheck()
            consolePanel().addErrorEntry("Error: $msg")
            e.printStackTrace()
            return
        }

        val isRecoverable = isCancelled || (e is AgentException && e.isRecoverable)
        if (!isRecoverable) {
            currentSessionId = null
            callbacks.updateSessionInfo()
        }

        // stop() already added "Stopped by user" — don't add a redundant error entry.
        if (!stopped) {
            consolePanel().addErrorEntry("Error: $msg")
        }
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

package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.acp.model.ContentBlock
import com.github.catatafishen.agentbridge.acp.model.PromptRequest
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient
import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.github.catatafishen.agentbridge.psi.CodeChangeTracker
import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.AgentScratchTracker
import com.github.catatafishen.agentbridge.services.AgentTabTracker
import com.github.catatafishen.agentbridge.services.ToolCallStatisticsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Callbacks the orchestrator invokes back into the UI layer for side-effects it cannot own.
 * Implemented by ChatToolWindowContent.
 */
data class PromptOrchestratorCallbacks(
    val onSendingStateChanged: (Boolean) -> Unit,
    val appendNewEntries: () -> Unit,
    val appendNewEntriesThrottled: () -> Unit,
    val notifyIfUnfocused: (toolCallCount: Int) -> Unit,
    val saveTurnStatistics: (prompt: String, toolCallCount: Int, modelId: String) -> Unit,
    val updateSessionInfo: () -> Unit,
    val requestFocusAfterTurn: () -> Unit,
    val onTimerIncrementToolCalls: () -> Unit,
    val onTimerRecordUsage: (inputTokens: Int, outputTokens: Int, costUsd: Double?) -> Unit,
    /**
     * Reports the multiplier of the just-completed turn so the side panel's "Last turn"
     * section can display the correct premium-request weight. Always called once at turn
     * completion; the multiplier may be null/empty when unknown (handled as 1.0 downstream).
     */
    val onTimerSetLastTurnMultiplier: (multiplier: String?) -> Unit,
    val onTimerSetCodeChangeStats: (added: Int, removed: Int) -> Unit,
    /** Called for plan-tree and file-tracking side-effects (remains in ChatToolWindowContent). */
    val onClientUpdate: (SessionUpdate) -> Unit,
    /** Trigger a new prompt execution (used for queued messages). */
    val sendPromptDirectly: (String) -> Unit,
    /** Restore the user's prompt text to the input box on send failure. */
    val restorePromptText: (rawText: String) -> Unit,
    /** Called after turn completion to mine entries into semantic memory (async, non-blocking). */
    val onTurnMineEntries: (sessionId: String, agentName: String) -> Unit,
    /**
     * Called when a queued message is auto-dequeued by the orchestrator at the end
     * of a turn so the UI layer can drop it from its recall stack.
     */
    val onQueuedMessageConsumed: (text: String) -> Unit,
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

    /** Copilot built-in tool whose summary should render as agent text, not a tool chip. */
    private val taskCompleteTool = "task_complete"

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

    private var turnStartedAt: Long = 0
    private var turnToolCallCount = 0
    private var turnInputTokens = 0
    private var turnOutputTokens = 0
    private var turnCostUsd: Double? = null
    private var turnModelId = ""
    private var turnStartHeadHash: String? = null

    /**
     * Stack of currently active sub-agent call IDs, ordered by start time (oldest first).
     *
     * The ACP protocol does not include a parentToolCallId in sub-agent internal tool calls —
     * attribution is based purely on temporal ordering (internal calls arrive between the
     * sub-agent's start and complete events). A stack handles the serial case perfectly
     * (one entry) and gives best-effort attribution for parallel sub-agents (attributes to
     * the most recently started one, since we have no protocol data to do better).
     */
    private val activeSubAgentStack = ArrayDeque<String>()

    /** The most recently started sub-agent call ID still in-flight, or null if none. */
    private val activeSubAgentId: String? get() = activeSubAgentStack.lastOrNull()
    private val toolCallTitles = mutableMapOf<String, String>()
    private val toolCallArgs = mutableMapOf<String, String>() // arguments from tool_call_update
    private var pendingBanner: PendingBanner? = null
    private var turnHadContent = false
    private var codeChangeListener: Runnable? = null

    private var pendingRawText = ""
    private var pendingPromptEntryId = ""

    /** Executes a prompt on the calling thread (must be called from a background thread). */
    fun execute(
        prompt: String, contextItems: List<ContextItemData>, selectedModelId: String,
        rawText: String, promptEntryId: String
    ) {
        pendingRawText = rawText
        pendingPromptEntryId = promptEntryId
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
            // Clean up agent resources from previous turns before starting a new one
            AgentTabTracker.getInstance(project).closeTrackedTabs()
            AgentScratchTracker.getInstance(project).cleanupExpired()

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
            val attachments = contextManager.buildPromptAttachments(contextItems.ifEmpty { null })
            val effectivePrompt = buildEffectivePrompt(prompt)
            addContextEntries(attachments, contextItems)

            dispatchPromptWithRetry(client, sessionId, effectivePrompt, modelId, attachments)
            // If stop() was called, the remote turn may have ended cleanly (via turn/interrupt
            // response) without throwing. Treat it as a cancellation so handlePromptCompletion
            // is not invoked and the stale thread interrupt doesn't leak into the next turn.
            if (stopped) throw InterruptedException("Stopped by user")
            // If the agent returned end_turn but produced no content, the session state is
            // likely corrupted (e.g. OpenCode's compaction state is broken). Handle it
            // explicitly — NOT via handlePromptError, which shows a misleading "Reconnect"
            // banner. We reset the session and tell the user clearly what happened.
            if (!turnHadContent) {
                handleSessionCorrupted()
                return
            }
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
        turnStartedAt = System.currentTimeMillis()
        turnInputTokens = 0
        turnOutputTokens = 0
        turnCostUsd = null
        turnHadContent = false
        activeSubAgentStack.clear()
        turnModelId = selectedModelId
        CodeChangeTracker.clear()
        turnStartHeadHash = captureGitHead()

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
        }
        return selectedModelId
    }

    private fun addContextEntries(attachments: List<PromptAttachment>, contextItems: List<ContextItemData>) {
        if (attachments.isNotEmpty() && contextItems.isNotEmpty()) {
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
        attachments: List<PromptAttachment>,
    ) {
        val promptBlocks = buildPromptBlocks(effectivePrompt, attachments)
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

    private fun buildPromptBlocks(prompt: String, attachments: List<PromptAttachment>): List<ContentBlock> {
        // Check if the active agent supports resource references
        val profile = agentManager.activeProfile
        if (!profile.isSendResourceReferences && attachments.isNotEmpty()) {
            return buildPromptBlocksAsTextFallback(prompt, attachments)
        }

        // Standard path: send each attachment as the appropriate ACP content block.
        val blocks = mutableListOf<ContentBlock>(ContentBlock.Text(prompt))
        for (attachment in attachments) {
            when (attachment) {
                is PromptAttachment.TextRef -> blocks.add(
                    ContentBlock.Resource(
                        ContentBlock.ResourceLink(
                            attachment.uri, null, attachment.mimeType, attachment.text, null
                        )
                    )
                )

                is PromptAttachment.ImageRef -> blocks.add(
                    ContentBlock.Image(attachment.base64Data, attachment.mimeType)
                )

                is PromptAttachment.BinaryRef -> blocks.add(
                    ContentBlock.Resource(
                        ContentBlock.ResourceLink(
                            attachment.uri, attachment.displayName, attachment.mimeType, null, null
                        )
                    )
                )
            }
        }
        return blocks
    }

    /**
     * Fallback for agents that do not accept ACP `Resource` content blocks (see
     * [com.github.catatafishen.agentbridge.services.AgentProfile.isSendResourceReferences]).
     * Inlines text attachments into the prompt; drops image/binary attachments with a
     * console note since base64 image blocks are not supported by these agents either.
     */
    private fun buildPromptBlocksAsTextFallback(
        prompt: String,
        attachments: List<PromptAttachment>,
    ): List<ContentBlock> {
        val textRefs = attachments.filterIsInstance<PromptAttachment.TextRef>()
        val skipped = attachments.size - textRefs.size
        if (skipped > 0) {
            val agentName = agentManager.activeProfile.displayName
            ApplicationManager.getApplication().invokeLater {
                consolePanel().addErrorEntry(
                    "\u26a0 $agentName does not support image or binary attachments — " +
                        "$skipped attachment(s) were not sent."
                )
            }
        }
        val promptWithContext = buildString {
            append(prompt)
            if (textRefs.isNotEmpty()) append("\n\n")
            for ((index, ref) in textRefs.withIndex()) {
                if (index > 0) append("\n\n")
                append("--- Context: ${ref.uri} ---\n")
                append(ref.text)
            }
        }
        return listOf(ContentBlock.Text(promptWithContext))
    }

    /**
     * Attempts to call [sendCall] with [initialSessionId]. If it fails with a "not found" session
     * error, invalidates the current session, creates a fresh one, and retries once.
     *
     * **Important:** when the retry fires, the resumed session context is lost — the agent
     * starts fresh. A warning is shown in the chat so the user knows why the agent lost context.
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
                val agentName = agentManager.activeProfile.displayName
                log.warn(
                    "$agentName: session '$initialSessionId' not found — " +
                        "falling back to fresh session. Previous context will be lost. " +
                        "Original error: $msg",
                    e
                )
                currentSessionId = null
                val newSessionId = ensureSessionCreated(client)

                ApplicationManager.getApplication().invokeLater {
                    consolePanel().addErrorEntry(
                        "⚠ Session resume failed — $agentName could not find the previous session. " +
                            "Started a fresh session; earlier conversation context was not restored."
                    )
                }

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
            callbacks.onTimerSetLastTurnMultiplier(multiplier)
            callbacks.onTimerRecordUsage(0, 0, 0.0)
        } else {
            consolePanel().finishResponse(turnToolCallCount, turnModelId, "")
            callbacks.onTimerSetLastTurnMultiplier(null)
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
        callbacks.appendNewEntries()
        val lastResponse = consolePanel().getLastResponseText()
        val quickReplies = detectQuickReplies(lastResponse)
        if (quickReplies.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater { consolePanel().showQuickReplies(quickReplies) }
        }

        val turnDuration = System.currentTimeMillis() - turnStartedAt
        val turnMultiplier = if (client.supportsMultiplier()) getModelMultiplier(turnModelId) ?: "" else ""
        val commitHashes = collectTurnCommits()
        consolePanel().emitTurnStats(
            TurnStatsData(
                turnDuration, turnInputTokens, turnOutputTokens, turnCostUsd ?: 0.0,
                turnToolCallCount, codeChanges[0], codeChanges[1], turnModelId, turnMultiplier,
                commitHashes
            )
        )

        recordTurnStatsToSqlite(
            TurnStatsParams(
                turnDuration, turnInputTokens, turnOutputTokens,
                turnToolCallCount, codeChanges[0], codeChanges[1], turnMultiplier, commitHashes
            )
        )

        val nextMsg = PsiBridgeService.getInstance(project).nextQueuedMessage
        if (nextMsg != null) {
            callbacks.onQueuedMessageConsumed(nextMsg)
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

        // Trigger semantic memory mining (async, non-blocking)
        val sessionId = currentSessionId
        if (sessionId != null) {
            callbacks.onTurnMineEntries(sessionId, agentManager.activeProfile.displayName)
        }
    }

    /**
     * Called when the agent returns {@code end_turn} with no content at all — no text,
     * no tool calls, no thoughts. This indicates a corrupted or unusable session state
     * (e.g. OpenCode's session compaction state is broken).
     *
     * Resets the session and tells the user explicitly what happened. Does NOT go through
     * [handlePromptError], which would show a misleading "Reconnect" banner implying a
     * connection failure.
     */
    private fun handleSessionCorrupted() {
        val agentName = agentManager.activeProfile.displayName
        log.warn("$agentName: empty turn — session state corrupted, resetting session")

        codeChangeListener?.let { CodeChangeTracker.removeListener(it) }
        codeChangeListener = null
        pendingBanner = null

        // Drop the ACP client's cached session ID too, so the next createSession()
        // goes through the full load/new flow instead of hitting the early-return
        // "reuse" path with the still-corrupted session.
        agentManager.client.dropCurrentSession()
        currentSessionId = null
        callbacks.updateSessionInfo()

        consolePanel().cancelAllRunning()
        consolePanel().finishResponse(turnToolCallCount, turnModelId, "")
        callbacks.appendNewEntries()

        consolePanel().addErrorEntry(
            "Session not resumed — $agentName returned an empty response. " +
                "Your session has been reset. Please resend your message to continue."
        )
        ApplicationManager.getApplication().invokeLater {
            statusBanner()?.showWarning("Session was reset — please resend your last message.")
        }
    }

    private fun handlePromptStreamingUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                turnHadContent = true
                val text = update.text()
                ApplicationManager.getApplication().invokeLater {
                    if (!stopped) consolePanel().appendText(text)
                }
            }

            is SessionUpdate.ToolCall -> {
                turnHadContent = true
                handleStreamingToolCall(update)
                handleClientUpdate(update)
            }

            is SessionUpdate.ToolCallUpdate -> {
                turnHadContent = true
                handleStreamingToolCallUpdate(update)
                handleClientUpdate(update)
            }

            is SessionUpdate.AgentThoughtChunk -> {
                turnHadContent = true
                if (!stopped) consolePanel().appendThinkingText(update.text())
            }

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

        // task_complete is a Copilot built-in tool whose summary should render as agent
        // text, not as a tool chip. Record the ID so handleStreamingToolCallUpdate can
        // emit the summary text and suppress the chip update.
        if (title == taskCompleteTool) {
            log.info("task_complete detected (id=$toolCallId) — suppressing chip creation")
            toolCallTitles[toolCallId] = taskCompleteTool
            return
        }

        if (toolCall.isSubAgent) {
            val agentType = toolCall.agentType() ?: return
            turnToolCallCount++
            callbacks.onTimerIncrementToolCalls()
            toolCallTitles[toolCallId] = "task"
            activeSubAgentStack.addLast(toolCallId)
            agentManager.client.setSubAgentActive(true)
            PsiBridgeService.getInstance(project).setNudgesHeld(true)
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
        val result = update.result() ?: update.error()
        val description = update.description()
        val autoDenied = update.autoDenied()
        val denialReason = update.denialReason()
        val arguments = update.arguments() // raw arguments from tool_call_update
        val kind = update.kind()?.value() // tool kind from tool_call_update (may be null)

        // Store arguments for potential re-correlation
        if (arguments != null) {
            toolCallArgs[toolCallId] = arguments
        }

        val callType = toolCallTitles[toolCallId]

        // task_complete: render the summary as agent text instead of updating a chip.
        // The summary is the agent's concluding message — displaying it inline provides
        // a natural reading experience instead of hiding it behind a tool chip.
        if (callType == taskCompleteTool) {
            val summary = result ?: description ?: ""
            if (summary.isNotBlank()) {
                ApplicationManager.getApplication().invokeLater {
                    if (!stopped) consolePanel().appendText(summary)
                }
            }
            toolCallTitles.remove(toolCallId)
            return
        }

        val isSubAgent = callType == "task"
        val isInternal = callType == "subagent_internal"

        val uiStatus = when (status) {
            SessionUpdate.ToolCallStatus.COMPLETED -> MessageFormatter.ChipStatus.COMPLETE
            SessionUpdate.ToolCallStatus.FAILED -> MessageFormatter.ChipStatus.FAILED
            else -> MessageFormatter.ChipStatus.RUNNING
        }

        updateToolCallUi(
            toolCallId, uiStatus,
            ToolCallUiUpdate(
                result = result, description = description,
                isSubAgent = isSubAgent, isInternal = isInternal,
                autoDenied = autoDenied, denialReason = denialReason,
                arguments = arguments, title = callType, kind = kind
            )
        )

        if (status == SessionUpdate.ToolCallStatus.COMPLETED || status == SessionUpdate.ToolCallStatus.FAILED) {
            callbacks.appendNewEntriesThrottled()
        }
    }

    private data class ToolCallUiUpdate(
        val result: String?, val description: String?,
        val isSubAgent: Boolean, val isInternal: Boolean,
        val autoDenied: Boolean = false, val denialReason: String? = null,
        val arguments: String? = null, val title: String? = null, val kind: String? = null
    )

    private fun updateToolCallUi(toolCallId: String, uiStatus: String, update: ToolCallUiUpdate) {
        if (update.isSubAgent) {
            if (uiStatus == "running") {
                // Sub-agent is still in progress — chip is already in running state from addSubAgentEntry;
                // calling updateSubAgentResult here would prematurely complete the chip in the JS layer.
                return
            }
            activeSubAgentStack.remove(toolCallId)
            if (activeSubAgentStack.isEmpty()) {
                agentManager.client.setSubAgentActive(false)
                PsiBridgeService.getInstance(project).setNudgesHeld(false)
                agentManager.settings.setActiveAgentLabel(null)
                consolePanel().setCurrentAgent(
                    agentManager.activeProfile.displayName,
                    agentManager.activeProfile.id,
                    agentManager.activeProfile.clientCssClass
                )
            }
            consolePanel().updateSubAgentResult(
                toolCallId,
                uiStatus,
                update.result,
                update.description,
                update.autoDenied,
                update.denialReason
            )
        } else if (update.isInternal) {
            consolePanel().updateSubAgentToolCall(
                toolCallId,
                uiStatus,
                update.result,
                update.description,
                update.autoDenied,
                update.denialReason
            )
        } else {
            consolePanel().updateToolCall(
                toolCallId, uiStatus,
                ChatPanelApi.ToolCallUpdate(
                    details = update.result,
                    description = update.description,
                    autoDenied = update.autoDenied,
                    denialReason = update.denialReason,
                    arguments = update.arguments,
                    title = update.title,
                    kind = update.kind
                )
            )
        }
    }

    private fun handlePromptError(e: Exception) {
        val c = PromptErrorClassifier.classify(
            exception = e,
            turnHadContent = turnHadContent,
            isAuthenticationError = { authService.isAuthenticationError(it) },
            isClientHealthy = agentManager.isClientHealthy,
        )

        if (c.shouldRestorePrompt) {
            consolePanel().removePromptEntry(pendingPromptEntryId)
        }

        consolePanel().cancelAllRunning()
        consolePanel().finishResponse(turnToolCallCount, turnModelId, "")
        callbacks.appendNewEntries()

        if (c.shouldRestorePrompt) {
            callbacks.restorePromptText(pendingRawText)
        }

        if (c.isAuthError) {
            log.info("Authentication error detected: ${c.displayMessage}")
            authService.markAuthError(c.displayMessage)
            copilotBanner()?.triggerCheck()
            consolePanel().addErrorEntry("Error: ${c.displayMessage}")
            e.printStackTrace()
            return
        }

        if (!c.isRecoverable) {
            if (c.isProcessCrashWithRecovery) {
                log.info("Agent process crashed but recovered — preserving session ...")
            } else {
                agentManager.client.dropCurrentSession()
                currentSessionId = null
            }
            callbacks.updateSessionInfo()
        }

        if (!stopped) {
            consolePanel().addErrorEntry("Error: ${c.displayMessage}")
        }
        if (!c.isCancelled) {
            val bannerMsg = if (c.shouldRestorePrompt)
                "${c.displayMessage} — your message has been restored to the input box"
            else c.displayMessage
            statusBanner()?.showError(bannerMsg, "Reconnect") { reconnectAfterError() }
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

    private data class TurnStatsParams(
        val durationMs: Long, val inputTokens: Int, val outputTokens: Int,
        val toolCallCount: Int, val linesAdded: Int, val linesRemoved: Int,
        val multiplier: String, val commitHashes: List<String>
    )

    private fun recordTurnStatsToSqlite(params: TurnStatsParams) {
        val sessionId = currentSessionId ?: return
        val agentId = agentManager.activeProfileId
        val premiumRequests = parsePremiumMultiplier(params.multiplier)
        val now = java.time.Instant.now()
        val date = java.time.LocalDate.now().toString()
        val timestamp = now.toString()
        val commitHashesStr = params.commitHashes.joinToString(",").ifEmpty { null }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val service = ToolCallStatisticsService.getInstance(project)
                service.recordTurnStats(
                    ToolCallStatisticsService.TurnStatsRecord(
                        sessionId, agentId, date,
                        params.inputTokens.toLong(), params.outputTokens.toLong(), params.toolCallCount,
                        params.durationMs, params.linesAdded, params.linesRemoved, premiumRequests, timestamp,
                        commitHashesStr
                    )
                )
            } catch (e: Exception) {
                log.warn("Failed to record turn stats to SQLite", e)
            }
        }
    }

    /**
     * Captures the current HEAD commit hash. Returns null if git is unavailable or the
     * working directory is not a git repository.
     */
    private fun captureGitHead(): String? {
        return try {
            val pb = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(java.io.File(project.basePath ?: return null))
            pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (proc.exitValue() == 0 && output.matches(Regex("[0-9a-f]{40}"))) output else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Collects commit hashes made since [turnStartHeadHash]. Returns an empty list if git
     * is unavailable, the repository hasn't changed, or the start hash was not captured.
     */
    private fun collectTurnCommits(): List<String> {
        val startHash = turnStartHeadHash ?: return emptyList()
        return try {
            val pb = ProcessBuilder("git", "log", "--format=%H", "$startHash..HEAD")
                .directory(java.io.File(project.basePath ?: return emptyList()))
            pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (proc.exitValue() != 0) return emptyList()
            output.lines().filter { it.matches(Regex("[0-9a-f]{40}")) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePremiumMultiplier(multiplier: String): Double {
        if (multiplier.isEmpty()) return 1.0
        val cleaned = if (multiplier.endsWith("x")) multiplier.dropLast(1) else multiplier
        return cleaned.toDoubleOrNull() ?: 1.0
    }

    private fun getModelMultiplier(modelId: String): String? =
        try {
            agentManager.client.getModelMultiplier(modelId)
        } catch (_: Exception) {
            null
        }

    private fun detectQuickReplies(responseText: String): List<String> =
        PromptErrorClassifier.detectQuickReplies(responseText)
}

package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.intellij.icons.AllIcons
import com.intellij.ide.setToolTipText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*

/**
 * Native Swing implementation of [ChatPanelApi] with styled chat bubbles,
 * tool chips with animated ring indicators, and collapsible thinking sections.
 *
 * Agent text bubbles use [NativeMarkdownPane] to render markdown as HTML via
 * [MarkdownRenderer], supporting tables, code blocks, headings, lists, bold,
 * inline code, blockquotes, and clickable file/git links.
 *
 * Visually matches the JCEF panel layout: user bubbles are right-aligned with
 * a blue tint, agent bubbles are left-aligned with a green tint, tool calls
 * appear as compact colored chips in a horizontal strip, and thinking text
 * is displayed in a collapsible gray block.
 */
class NativeChatPanel(private val project: Project) : ChatPanelApi {

    override var onQuickReply: ((String) -> Unit)? = null
    override var onStatusMessage: ((type: String, message: String) -> Unit)? = null
    var onLoadMoreRequested: (() -> Unit)? = null
    var onAutoScrollDisabled: (() -> Unit)? = null
    var onAutoScrollEnabled: (() -> Unit)? = null

    /** Invoked when the user clicks the ↓ button on a pending nudge bubble. */
    var onCancelNudge: ((id: String) -> Unit)? = null

    private val fileNavigator = FileNavigator(project)
    private val toolRegistry = ToolRegistry.getInstance(project)

    /** Stored tool call data for popup display when chips are clicked. */
    private data class ToolCallData(
        val title: String,
        val kind: String,
        val arguments: String? = null,
        var status: String = "running",
        var result: String? = null,
        var description: String? = null,
        var autoDenied: Boolean = false,
        var denialReason: String? = null,
    )

    private val toolCallData = mutableMapOf<String, ToolCallData>()

    /** Buttons of the last-shown quick-reply strip; disabled on reply or new turn. */
    private var currentQuickReplyButtons: List<JButton> = emptyList()

    private val contentPanel = JBPanel<JBPanel<*>>(null).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 10)
        background = UIUtil.getPanelBackground()
    }

    private val scrollPane = JBScrollPane(contentPanel).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        background = UIUtil.getPanelBackground()
        viewport.background = UIUtil.getPanelBackground()
    }

    override val component: JComponent = scrollPane

    private var currentTurn: TurnContext? = null
    private val allChips = mutableMapOf<String, ToolChipComponent>()
    private val allMarkdownPanes = mutableListOf<NativeMarkdownPane>()
    private val spinTimer = Timer(50) {
        allChips.values.filter { it.isSpinning() }.forEach { it.advanceSpin() }
    }.apply { isRepeats = true }

    private var autoScrollEnabled = true

    /** Guards the AdjustmentListener against reacting to programmatic scroll operations. */
    private var suppressScrollListener = false

    /**
     * Tracks the last observed scrollbar value so the AdjustmentListener can tell the difference
     * between the user scrolling up (value decreases) and the scrollbar max growing because new
     * content was appended (value stays the same). Only a value decrease should disable auto-scroll.
     */
    private var lastScrollValue = 0

    private val schemeDisposable = Disposer.newDisposable("NativeChatPanel")

    fun setAutoScroll(enabled: Boolean) {
        autoScrollEnabled = enabled
        if (enabled) scrollToBottom()
    }

    private var placeholderLabel: JBLabel? = null
    private var workingIndicator: JComponent? = null
    private var workingStartMs = 0L
    private val workingTimer = Timer(1000) { updateWorkingLabel() }.apply { isRepeats = true }

    /**
     * Set to true when a tool call or sub-agent reaches a terminal state.
     * The next [appendThinkingText] or [appendText] call checks this flag via
     * [maybeStartNewSegment] to finalize the current turn and start a fresh one,
     * matching the JCEF panel's segment splitting behaviour.
     */
    private var toolJustCompleted = false

    /** HH:mm of the last timestamp label shown. Used to suppress duplicate timestamps within the same minute. */
    private var lastShownTimestampMinute = ""

    /**
     * When set, overrides `MessageFormatter.timestamp()` for timestamp label creation and deduplication.
     * Used during history replay so entries show their historical timestamps instead of "now".
     */
    private var overrideTimestamp: String? = null

    /** True while [replayEntries] is running; suppresses [showWorkingIndicator] during replay. */
    private var isReplaying = false

    init {
        scrollPane.verticalScrollBar.addAdjustmentListener { e ->
            if (!e.valueIsAdjusting && !suppressScrollListener) {
                val bar = scrollPane.verticalScrollBar
                val currentValue = bar.value
                val atBottom = currentValue + bar.visibleAmount >= bar.maximum - 4
                if (atBottom && !autoScrollEnabled && currentValue >= lastScrollValue) {
                    // Re-enable only when the user scrolled back down to the bottom
                    // (value increased or stayed the same). Guard against false positives
                    // where JEditorPane.setText() briefly shrinks the document during
                    // content replacement, causing maximum to drop and atBottom to become
                    // true even though the user hasn't moved the scrollbar.
                    autoScrollEnabled = true
                    onAutoScrollEnabled?.invoke()
                } else if (!atBottom && autoScrollEnabled && currentValue < lastScrollValue) {
                    // Only disable auto-scroll when the user actively scrolled up (value decreased).
                    // Ignore events where the max grew due to new content being appended — those
                    // leave the value unchanged and should not disturb the auto-scroll state.
                    autoScrollEnabled = false
                    onAutoScrollDisabled?.invoke()
                }
                lastScrollValue = currentValue
            }
        }
        contentPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (autoScrollEnabled) scrollToBottom()
            }
        })
        PlatformApiCompat.subscribeEditorColorSchemeChanges(schemeDisposable) {
            SwingUtilities.invokeLater { updateAllChatFonts() }
        }
    }

    private class TurnContext(
        val container: JPanel,
        val chipStrip: ChipStripPanel,
        var thinkingChip: ThinkingChipComponent? = null,
        var thinkingWrapper: JPanel? = null,
        var thinkingPane: NativeMarkdownPane? = null,
        var thinkingExpanded: Boolean = true,
        var markdownPane: NativeMarkdownPane? = null,
    )

    private fun ensureTurn(): TurnContext {
        currentTurn?.let { return it }

        val chipStrip = ChipStripPanel().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }

        val container = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                // 2px top creates a small visual gap from the preceding element;
                // no bottom padding so chips sit close to the content below them.
                border = JBUI.Borders.emptyTop(2)
            }

            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), Int.MAX_VALUE)
        }
        val currentMinute = MessageFormatter.formatTimestamp(overrideTimestamp ?: MessageFormatter.timestamp())
        if (currentMinute != lastShownTimestampMinute) {
            lastShownTimestampMinute = currentMinute
            container.add(createTimestampLabel(rightAligned = false).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        container.add(chipStrip)

        val turn = TurnContext(container, chipStrip)
        currentTurn = turn
        addRow(container, JBUI.scale(2))
        return turn
    }

    private fun finalizeTurn() {
        currentTurn?.markdownPane?.renderNow()
        currentTurn?.thinkingPane?.renderNow()
        currentTurn = null
    }

    /**
     * Mirrors the JCEF panel's segment splitting: when a tool just completed and
     * new content (thinking or text) arrives, finalize the current turn so the new
     * content appears in a fresh visual section below the completed tool chips.
     */
    private fun maybeStartNewSegment() {
        if (!toolJustCompleted) return
        toolJustCompleted = false
        finalizeTurn()
    }

    private fun addRow(comp: JComponent, spacing: Int = JBUI.scale(4)) {
        val shouldScroll = autoScrollEnabled
        placeholderLabel?.let { contentPanel.remove(it); placeholderLabel = null }
        comp.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(comp)
        contentPanel.add(Box.createVerticalStrut(spacing))
        moveWorkingToBottom()
        contentPanel.revalidate()
        if (shouldScroll) scrollToBottom()
    }

    /** Creates a small right- or left-aligned timestamp label (HH:mm) with a full-date tooltip. */
    private fun createTimestampLabel(rightAligned: Boolean = false): JBLabel {
        val iso = overrideTimestamp ?: MessageFormatter.timestamp()
        val ts = MessageFormatter.formatTimestamp(iso)
        val tooltip = MessageFormatter.formatTimestamp(iso, MessageFormatter.TimestampStyle.TOOLTIP)
        return JBLabel(ts).apply {
            foreground = UIUtil.getContextHelpForeground()
            applyChatFont(-1)
            horizontalAlignment = if (rightAligned) SwingConstants.RIGHT else SwingConstants.LEFT
            alignmentX = if (rightAligned) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 2, 1, 2)
            setToolTipText(HtmlChunk.text(tooltip))
        }
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            // Do NOT call scrollPane.validate() here. A synchronous validate() walks every
            // NativeMarkdownPane in the chat history and triggers a full Swing HTML layout
            // (BasicTextUI$RootView.setSize + BoxView.updateLayoutArray) which is O(n²) in
            // the document size — causing 30+ second EDT freezes for long responses.
            //
            // The layout pass we need has already been queued by contentPanel.revalidate()
            // (called in addRow() before scrollToBottom()) and runs on the EDT before our
            // invokeLater fires. The componentResized listener on contentPanel also fires
            // after every layout, providing a second correct-position scroll for deferred
            // renders. bar.maximum is therefore always up-to-date when we read it here.
            suppressScrollListener = true
            try {
                scrollPane.verticalScrollBar.value = Int.MAX_VALUE
            } finally {
                suppressScrollListener = false
            }
        }
    }

    private fun showWorkingIndicator() {
        if (isReplaying) return
        hideWorkingIndicator()
        workingStartMs = System.currentTimeMillis()
        val (row, bubble) = createBubble(NativeChatColors.AGENT_BUBBLE_BG)
        bubble.add(JBLabel("Working…").apply {
            foreground = UIUtil.getContextHelpForeground()
            applyChatFont()
            putClientProperty("workingLabel", true)
        }, BorderLayout.CENTER)
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0, 2, 0)
        }
        container.add(row)
        workingIndicator = container
        addRow(container, JBUI.scale(2))
        workingTimer.start()
    }

    /**
     * Ensures [workingIndicator] is always the last row in [contentPanel].
     * Called from [addRow] so that any new content added above leaves Working… anchored at the bottom.
     */
    private fun moveWorkingToBottom() {
        val indicator = workingIndicator ?: return
        val idx = contentPanel.components.indexOf(indicator)
        if (idx < 0) return
        val lastIdx = contentPanel.componentCount - 1
        val strutAfter = idx + 1 <= lastIdx && contentPanel.getComponent(idx + 1) is Box.Filler
        val isLast = if (strutAfter) idx == lastIdx - 1 else idx == lastIdx
        if (isLast) return
        if (strutAfter) contentPanel.remove(idx + 1)
        contentPanel.remove(indicator)
        contentPanel.add(indicator)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(2)))
    }

    private fun hideWorkingIndicator() {
        workingTimer.stop()
        workingIndicator?.let {
            // Find index and remove trailing strut BEFORE removing the indicator itself
            val idx = contentPanel.components.indexOf(it)
            if (idx >= 0 && idx + 1 < contentPanel.componentCount) {
                val next = contentPanel.getComponent(idx + 1)
                if (next is Box.Filler) contentPanel.remove(next)
            }
            contentPanel.remove(it)
            contentPanel.revalidate()
            contentPanel.repaint()
        }
        workingIndicator = null
    }

    private fun updateWorkingLabel() {
        val indicator = workingIndicator ?: return
        val elapsed = (System.currentTimeMillis() - workingStartMs) / 1000
        fun findLabel(parent: Container): JBLabel? {
            for (comp in parent.components) {
                if (comp is JBLabel && comp.getClientProperty("workingLabel") == true) return comp
                if (comp is Container) findLabel(comp)?.let { return it }
            }
            return null
        }
        findLabel(indicator)?.text = "Working… ${elapsed}s"
        if (autoScrollEnabled) scrollToBottom()
    }

    /** Creates a markdown pane pre-filled with [text] and registers it for disposal. */
    private fun createMarkdownPane(text: String): NativeMarkdownPane =
        NativeMarkdownPane(fileNavigator).also { pane ->
            pane.setCompleteMarkdown(text)
            allMarkdownPanes += pane
        }

    /** Creates a streaming bubble: an aligned row ready to add to a container, plus its pane. */
    private fun createMarkdownBubble(bg: Color): Pair<JPanel, NativeMarkdownPane> {
        val (row, bubble) = createBubble(bg)
        val pane = NativeMarkdownPane(fileNavigator).also { allMarkdownPanes += it }
        bubble.add(pane, BorderLayout.CENTER)
        return row to pane
    }

    /** Creates a complete message row: timestamp label + aligned bubble. */
    private fun createMessageRow(
        content: JComponent,
        bg: Color,
        rightAligned: Boolean = false,
        onBubbleRow: ((BubbleRow) -> Unit)? = null,
    ): Pair<JPanel, RoundedPanel> {
        val bubbleRow = createBubble(bg, rightAligned)
        onBubbleRow?.invoke(bubbleRow)
        bubbleRow.bubble.add(content, BorderLayout.CENTER)
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val currentMinute = MessageFormatter.formatTimestamp(overrideTimestamp ?: MessageFormatter.timestamp())
        if (currentMinute != lastShownTimestampMinute) {
            lastShownTimestampMinute = currentMinute
            row.add(createTimestampLabel(rightAligned).apply {
                alignmentX = if (rightAligned) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
            })
        }
        row.add(bubbleRow.row)
        return row to bubbleRow.bubble
    }

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
        // Sending a prompt is an explicit "show me the response" signal.
        // Re-enable auto-scroll so the working indicator and streaming reply are visible,
        // regardless of where the user was scrolled before sending.
        // Guarded against replay, where addPromptEntry is called to restore history.
        if (!isReplaying) {
            autoScrollEnabled = true
            onAutoScrollEnabled?.invoke()
        }
        return addPromptEntryAt(text, contextFiles) { row ->
            addRow(row, spacing = JBUI.scale(10))
        }
    }

    override fun removePromptEntry(entryId: String) { /* Native panel doesn't track entry IDs for removal */
    }

    override fun startStreaming() {
        finalizeTurn()
    }

    override fun appendText(text: String) {
        collapseThinking()
        maybeStartNewSegment()
        workingStartMs = System.currentTimeMillis()
        val turn = ensureTurn()
        if (turn.markdownPane == null) {
            val (row, pane) = createMarkdownBubble(NativeChatColors.AGENT_BUBBLE_BG)
            turn.markdownPane = pane
            turn.container.add(row)
        }
        turn.markdownPane!!.appendMarkdown(text)
        if (autoScrollEnabled) scrollToBottom()
    }

    override fun appendThinkingText(text: String) {
        maybeStartNewSegment()
        workingStartMs = System.currentTimeMillis()
        val turn = ensureTurn()
        if (turn.thinkingChip == null) {
            val (contentWrapper, pane) = createMarkdownBubble(NativeChatColors.THINK_BG)

            turn.thinkingPane = pane
            turn.thinkingWrapper = contentWrapper
            turn.thinkingExpanded = true

            // Pad the wrapper itself so spacing collapses with it when hidden.
            // Standalone struts would remain visible even when contentWrapper.isVisible = false.
            contentWrapper.border = JBUI.Borders.empty(JBUI.scale(4), 0, JBUI.scale(4), 0)
            val chipStripIdx = turn.container.components.indexOf(turn.chipStrip)
            turn.container.add(contentWrapper, chipStripIdx + 1)

            val chip = ThinkingChipComponent(active = true) {
                turn.thinkingExpanded = !turn.thinkingExpanded
                contentWrapper.isVisible = turn.thinkingExpanded
                if (turn.thinkingExpanded) contentWrapper.revalidate()
                turn.container.revalidate()
                turn.container.repaint()
            }
            turn.thinkingChip = chip
            turn.chipStrip.addThinkingChip(chip)
        }
        turn.thinkingPane!!.appendMarkdown(text)
        if (autoScrollEnabled) scrollToBottom()
    }

    override fun collapseThinking() {
        val turn = currentTurn ?: return
        val chip = turn.thinkingChip ?: return
        chip.setActive(false)
        chip.collapseWhenReady {
            turn.thinkingWrapper?.isVisible = false
            turn.thinkingExpanded = false
            turn.container.revalidate()
        }
    }

    override fun addToolCallEntry(
        id: String, title: String, arguments: String?, kind: String?, isMcpHandled: Boolean
    ) {
        // If text was streaming, finalize it before adding the tool chip —
        // so the chip appears below the text, not interleaved with it.
        if (currentTurn?.markdownPane != null) {
            finalizeTurn()
        }
        val turn = ensureTurn()
        val resolvedKind = kind ?: "other"
        val displayTitle = resolveToolDisplayName(title)
        toolCallData[id] = ToolCallData(displayTitle, resolvedKind, arguments)
        val chip = ToolChipComponent(displayTitle, kind, "running") { showToolPopup(id) }
        allChips[id] = chip
        turn.chipStrip.addToolChip(chip)
        if (!spinTimer.isRunning) spinTimer.start()
        if (autoScrollEnabled) scrollToBottom()
    }

    /**
     * Resolves a tool title to a human-readable display name.
     * If the title looks like a raw tool ID (e.g. "agentbridge-read_file"),
     * we look it up in the [ToolRegistry] for a friendlier name.
     */
    private fun resolveToolDisplayName(title: String): String {
        if (title.contains(' ') || title.contains('(')) return title
        val def = toolRegistry.findById(title)
        return def?.displayName() ?: title
    }

    override fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) {
        val chip = allChips[id] ?: return
        chip.updateStatus(status)
        toolCallData[id]?.let { data ->
            data.status = status
            update.details?.let { data.result = it }
            update.description?.let { data.description = it }
            update.kind?.let { data.status = status }
            if (update.autoDenied) data.autoDenied = true
            update.denialReason?.let { data.denialReason = it }
        }
        if (status == "complete" || status == "completed" || status == "failed" || status == "denied") {
            toolJustCompleted = true
        }
        if (allChips.values.none { it.isSpinning() }) spinTimer.stop()
    }

    private fun showToolPopup(toolId: String) {
        val data = toolCallData[toolId] ?: return
        val baseName = data.title.substringBefore('(').trim()
        val toolDef = toolRegistry?.findById(baseName)
        val mcpDescription = if (toolDef != null && !toolDef.isBuiltIn) toolDef.description() else null
        val failed = data.status == "failed"

        val resultPanel = if (!data.result.isNullOrBlank()) {
            com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers.codePanel(data.result!!)
        } else {
            JBLabel("No result available").apply { foreground = UIUtil.getContextHelpForeground() }
        }
        val paramsPanel = if (!data.arguments.isNullOrBlank()) {
            com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers.jsonEditor(
                ToolCallArgParser.prettyJson(data.arguments), project
            )
        } else null

        ApplicationManager.getApplication().invokeLater {
            ToolCallPopup.show(
                ToolCallPopup.Request(
                    project = project,
                    title = data.title,
                    kind = data.kind,
                    paramsPanel = paramsPanel,
                    resultPanel = resultPanel,
                    toolDescription = mcpDescription,
                    autoDenied = data.autoDenied,
                    denialReason = data.denialReason,
                    failed = failed
                )
            )
        }
    }

    override fun addSubAgentEntry(
        id: String, agentType: String, description: String,
        prompt: String?, initialState: ChatPanelApi.SubAgentInitialState
    ) {
        val label = SUB_AGENT_INFO[agentType]?.displayName ?: agentType
        addToolCallEntry(id, "[$label] $description", null, "think", false)
    }

    override fun updateSubAgentResult(
        id: String, status: String, result: String?,
        description: String?, autoDenied: Boolean, denialReason: String?
    ) = updateToolCall(id, status, ChatPanelApi.ToolCallUpdate())

    override fun addSubAgentToolCall(
        subAgentId: String, toolId: String, title: String, arguments: String?, kind: String?
    ) = addToolCallEntry(toolId, title, arguments, kind, false)

    override fun updateSubAgentToolCall(
        toolId: String, status: String, details: String?,
        description: String?, autoDenied: Boolean, denialReason: String?
    ) = updateToolCall(toolId, status, ChatPanelApi.ToolCallUpdate())

    override fun addErrorEntry(message: String) {
        finalizeTurn()
        val (row, _) = createMessageRow(createMarkdownPane("✗ $message"), NativeChatColors.ERROR_BG)
        addRow(row)
    }

    override fun addInfoEntry(message: String) {
        val (row, _) = createMessageRow(createMarkdownPane("ℹ $message"), NativeChatColors.AGENT_BUBBLE_BG)
        addRow(row)
    }

    override fun hasContent(): Boolean = contentPanel.componentCount > 0

    override fun addSessionSeparator(timestamp: String, agent: String) {
        finalizeTurn()
        lastShownTimestampMinute = ""
        addRow(createSessionSeparatorRow(timestamp, agent))
    }

    override fun showPlaceholder(text: String) {
        if (contentPanel.componentCount > 0) return
        val label = JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            horizontalAlignment = SwingConstants.CENTER
            alignmentX = Component.CENTER_ALIGNMENT
        }
        placeholderLabel = label
        contentPanel.add(label)
        contentPanel.revalidate()
    }

    override fun clear() {
        allMarkdownPanes.forEach { it.dispose() }
        allMarkdownPanes.clear()
        hideWorkingIndicator()
        contentPanel.removeAll()
        contentPanel.revalidate()
        contentPanel.repaint()
        currentTurn = null
        allChips.clear()
        toolCallData.clear()
        nudgeBubbles.clear()
        queuedMessages.clear()
        loadMoreButton = null
        currentModelLabel = null
        if (spinTimer.isRunning) spinTimer.stop()
        placeholderLabel = null
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        hideWorkingIndicator()
        collapseThinking()
        finalizeTurn()
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        addRow(createTurnStatsRow(stats))
    }

    override fun showQuickReplies(options: List<String>) {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val buttons = mutableListOf<JButton>()
        options.forEach { raw ->
            val opt = parseQuickReplyOption(raw)
            val btn = JButton(opt.label).apply {
                applyChatFont()
                foreground = when {
                    opt.dismiss -> UIUtil.getContextHelpForeground()
                    opt.color == QuickReplyColor.DANGER -> JBColor.RED
                    opt.color == QuickReplyColor.PRIMARY -> USER_COLOR
                    opt.color == QuickReplyColor.WARNING -> JBColor.ORANGE
                    else -> null
                }
                addActionListener {
                    currentQuickReplyButtons.forEach { it.isEnabled = false }
                    if (!opt.dismiss) onQuickReply?.invoke(opt.label)
                }
            }
            buttons.add(btn)
            panel.add(btn)
        }
        currentQuickReplyButtons = buttons
        addRow(panel)
    }

    override fun disableQuickReplies() {
        currentQuickReplyButtons.forEach { it.isEnabled = false }
        currentQuickReplyButtons = emptyList()
    }

    override fun cancelAllRunning() {
        hideWorkingIndicator()
        finalizeTurn()
    }

    override fun getConversationText(): String = ""
    override fun getCompressedSummary(maxChars: Int): String = ""
    override fun getConversationHtml(): String = ""
    override fun getLastResponseText(): String = ""
    override fun getPageHtml(): String? = null

    override fun showPermissionRequest(
        reqId: String, toolDisplayName: String, description: String,
        onRespond: (PermissionResponse) -> Unit
    ) {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(JBLabel("<html><b>Allow:</b> $toolDisplayName — $description</html>"), BorderLayout.NORTH)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        listOf(
            "Deny" to PermissionResponse.DENY,
            "Allow Once" to PermissionResponse.ALLOW_ONCE,
            "Allow Session" to PermissionResponse.ALLOW_SESSION,
            "Allow Always" to PermissionResponse.ALLOW_ALWAYS
        ).forEach { (text, resp) ->
            buttons.add(JButton(text).apply { addActionListener { onRespond(resp) } })
        }
        panel.add(buttons, BorderLayout.SOUTH)
        addRow(panel)
    }

    override fun showAskUserRequest(
        reqId: String, question: String, options: List<String>,
        deadlineEpochMs: Long, onRespond: (String) -> Unit,
        onExtend: () -> Long, onSuperseded: () -> Unit,
    ) {
        hideWorkingIndicator()
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(JBLabel("<html>$question</html>"), BorderLayout.NORTH)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        options.forEach { opt ->
            buttons.add(JButton(opt).apply { addActionListener { onRespond(opt) } })
        }

        val countdownLabel = JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
            applyChatFont(-1)
        }

        val extendButton = JButton("I need more time").apply {
            applyChatFont(-1)
            addActionListener {
                val newDeadline = onExtend()
                putClientProperty("deadline", newDeadline)
            }
        }
        extendButton.putClientProperty("deadline", deadlineEpochMs)

        val countdownTimer = Timer(1000) {
            val dl = (extendButton.getClientProperty("deadline") as? Long) ?: deadlineEpochMs
            val remaining = (dl - System.currentTimeMillis()) / 1000
            if (remaining <= 0) {
                countdownLabel.text = "⏱ Time expired"
                onSuperseded()
            } else {
                countdownLabel.text = "⏱ ${remaining}s remaining"
            }
        }.apply { isRepeats = true }

        val bottomRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        bottomRow.add(buttons)
        bottomRow.add(extendButton)
        bottomRow.add(countdownLabel)

        panel.add(bottomRow, BorderLayout.SOUTH)
        addRow(panel)
        countdownTimer.start()
    }

    override fun hasPendingAskUserRequest(): Boolean = false
    override fun consumePendingAskUserResponse(response: String): Boolean = false
    override fun clearPendingAskUserRequest(reqId: String?) { /* Ask-user state is managed by JCEF panel only */
    }

    private val nudgeBubbles = mutableMapOf<String, JComponent>()

    /** Creates a right-aligned nudge row with a ↓ hover button that restores the text to the input. */
    private fun createNudgeRow(id: String, text: String): JPanel {
        val (row, _) = createMessageRow(
            createMarkdownPane(text), NativeChatColors.USER_BUBBLE_BG, rightAligned = true
        ) { bubbleRow ->
            bubbleRow.addHoverButton(AllIcons.Actions.MoveDown, "Restore to input") {
                onCancelNudge?.invoke(id)
            }
        }
        return row
    }

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) {
        removeNudgeBubble(id)
        val row = createNudgeRow(id, text)
        nudgeBubbles[id] = row
        addRow(row)
    }

    override fun resolveNudgeBubble(id: String) {
        removeNudgeBubble(id)
    }

    override fun removeNudgeBubble(id: String) {
        nudgeBubbles.remove(id)?.let {
            contentPanel.remove(it)
            contentPanel.revalidate()
            contentPanel.repaint()
        }
    }

    override fun addNudgeEntry(id: String, text: String, source: NudgeSource) {
        addRow(createNudgeRow(id, text))
    }

    private val queuedMessages = mutableMapOf<String, JComponent>()

    override fun showQueuedMessage(id: String, text: String) {
        removeQueuedMessage(id)
        val (row, _) = createMessageRow(createMarkdownPane(text), NativeChatColors.USER_BUBBLE_BG, rightAligned = true)
        row.putClientProperty("queuedText", text)
        queuedMessages[id] = row
        addRow(row)
    }

    override fun removeQueuedMessage(id: String) {
        queuedMessages.remove(id)?.let {
            contentPanel.remove(it)
            contentPanel.revalidate()
            contentPanel.repaint()
        }
    }

    override fun removeQueuedMessageByText(text: String) {
        val entry = queuedMessages.entries.firstOrNull { (_, row) ->
            row.getClientProperty("queuedText") == text
        } ?: return
        removeQueuedMessage(entry.key)
    }

    private var currentModelLabel: JBLabel? = null

    override fun setCurrentModel(modelId: String) {
        updateMetaLabel(modelId.substringAfterLast('/').substringAfterLast(':'))
    }

    override fun setCurrentProfile(profileId: String) {
        // Profile is displayed in ProcessingTimerPanel — not duplicated here
    }

    override fun setCurrentAgent(
        agentName: String,
        profileId: String,
        clientType: String
    ) {
        val display = buildString {
            append(agentName)
            if (clientType.isNotEmpty()) append(" · $clientType")
        }
        updateMetaLabel(display)
    }

    private fun updateMetaLabel(text: String) {
        if (text.isEmpty()) return
        val label = currentModelLabel
        if (label != null) {
            label.text = "🤖 $text"
        } else {
            val newLabel = JBLabel("🤖 $text").apply {
                foreground = UIUtil.getContextHelpForeground()
                applyChatFont(-1, Font.ITALIC)
                border = JBUI.Borders.empty(1, 0, 2, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            currentModelLabel = newLabel
            addRow(newLabel)
        }
    }

    override fun addImageThumbnails(images: List<ChatPanelApi.ImageAttachment>) {
        ApplicationManager.getApplication().invokeLater {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            for (img in images) {
                val label = try {
                    val bytes = java.util.Base64.getDecoder().decode(img.base64Data)
                    val icon = scaledThumbnail(bytes, JBUI.scale(160))
                    JLabel(icon).apply { setToolTipText(HtmlChunk.text(img.name)) }
                } catch (_: Exception) {
                    JBLabel("\uD83D\uDDBC ${img.name}").apply { applyChatFont(-1) }
                }
                panel.add(label)
            }
            addRow(panel)
        }
    }

    private fun scaledThumbnail(bytes: ByteArray, maxSize: Int): ImageIcon {
        val original = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            ?: return ImageIcon()
        val scale = maxSize.toDouble() / maxOf(original.width, original.height).coerceAtLeast(1)
        if (scale >= 1.0) return ImageIcon(original)
        val w = (original.width * scale).toInt()
        val h = (original.height * scale).toInt()
        val scaled = original.getScaledInstance(w, h, Image.SCALE_SMOOTH)
        return ImageIcon(scaled)
    }

    override fun dispose() {
        allMarkdownPanes.forEach { it.dispose() }
        if (spinTimer.isRunning) spinTimer.stop()
        workingTimer.stop()
        Disposer.dispose(schemeDisposable)
    }

    /**
     * Walks [contentPanel]'s component tree and updates the font on every [JComponent]
     * that was tagged with [CHAT_FONT_DELTA] via [applyChatFont]. Called when the IDE
     * editor font size changes (Alt+Shift+. / Alt+Shift+,).
     */
    private fun updateAllChatFonts() {
        updateChatFonts(contentPanel)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun updateChatFonts(container: Container) {
        for (child in container.components) {
            if (child is JComponent) {
                val delta = child.getClientProperty(CHAT_FONT_DELTA) as? Int
                if (delta != null) {
                    val style = (child.getClientProperty(CHAT_FONT_STYLE) as? Int) ?: Font.PLAIN
                    child.font = chatFont(delta, style)
                }
            }
            if (child is Container) updateChatFonts(child)
        }
    }

    // ── History restore ────────────────────────────────────────────

    /** Replays a batch of [EntryData] items into the Swing panel for conversation restoration. */
    fun appendEntries(entries: List<EntryData>, @Suppress("UNUSED_PARAMETER") totalPromptCount: Int = -1) {
        if (entries.isEmpty()) return
        replayEntries(entries)
    }

    /** Prepends older entries at the top of the panel (for "Load More"). */
    fun prependEntries(entries: List<EntryData>) {
        if (entries.isEmpty()) return
        val insertionPoint = loadMoreButton?.let { contentPanel.getComponentZOrder(it) + 1 } ?: 0
        replayEntries(entries, insertionIndex = insertionPoint)
    }

    private var loadMoreButton: JComponent? = null

    fun showLoadMore(deferredCount: Int) {
        hideLoadMore()
        val btn = JButton("▲ Load $deferredCount more messages").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            applyChatFont(-1)
            addActionListener { onLoadMoreRequested?.invoke() }
        }
        contentPanel.add(btn, 0)
        loadMoreButton = btn
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    fun hideLoadMore() {
        loadMoreButton?.let { contentPanel.remove(it) }
        loadMoreButton = null
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    /**
     * Replays [EntryData] items into the panel using the existing ChatPanelApi methods.
     * When [insertionIndex] >= 0, components are inserted at that position (for prepend);
     * otherwise they are appended at the end.
     */
    private fun replayEntries(entries: List<EntryData>, insertionIndex: Int = -1) {
        val savedAutoScroll = autoScrollEnabled
        if (insertionIndex >= 0) autoScrollEnabled = false
        isReplaying = true

        val prevAddRow = if (insertionIndex >= 0) {
            var nextIdx = insertionIndex
            { comp: JComponent -> insertRowAt(comp, nextIdx); nextIdx += 2 /* comp + strut */ }
        } else null

        for (entry in entries) {
            overrideTimestamp = entry.timestamp.ifEmpty { null }
            when (entry) {
                is EntryData.Prompt -> {
                    finalizeTurn()
                    val ctxTriples = entry.contextFiles?.map { Triple(it.name, it.path, it.line) }
                    if (prevAddRow != null) {
                        addPromptEntryAt(entry.text, ctxTriples, prevAddRow)
                    } else {
                        addPromptEntry(entry.text, ctxTriples)
                    }
                }

                is EntryData.Text -> {
                    startStreaming()
                    appendText(entry.raw)
                }

                is EntryData.Thinking -> {
                    appendThinkingText(entry.raw)
                    collapseThinking()
                }

                is EntryData.ToolCall -> {
                    val status = entry.status ?: "complete"
                    addToolCallEntry(entry.entryId, entry.title, entry.arguments, entry.kind, false)
                    updateToolCall(entry.entryId, status, ChatPanelApi.ToolCallUpdate())
                }

                is EntryData.SubAgent -> {
                    val status = entry.status ?: "complete"
                    addSubAgentEntry(entry.entryId, entry.agentType, entry.description, entry.prompt)
                    updateSubAgentResult(entry.entryId, status, entry.result, entry.description)
                }

                is EntryData.TurnStats -> {
                    emitTurnStats(
                        TurnStatsData(
                            durationMs = entry.durationMs,
                            inputTokens = entry.inputTokens.toInt(),
                            outputTokens = entry.outputTokens.toInt(),
                            costUsd = entry.costUsd,
                            toolCallCount = entry.toolCallCount,
                            linesAdded = entry.linesAdded,
                            linesRemoved = entry.linesRemoved,
                            model = entry.model,
                            multiplier = entry.multiplier
                        )
                    )
                }

                is EntryData.SessionSeparator -> addSessionSeparator(entry.timestamp, entry.agent)
                is EntryData.ContextFiles -> addContextFilesEntry(entry.files.map { it.name to it.path })
                is EntryData.Status -> addInfoEntry("${entry.icon} ${entry.message}")
                is EntryData.Nudge -> if (entry.sent) addNudgeEntry(entry.id, entry.text, entry.source)
            }
        }
        overrideTimestamp = null
        finalizeTurn()
        isReplaying = false

        if (insertionIndex >= 0) autoScrollEnabled = savedAutoScroll
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun insertRowAt(comp: JComponent, index: Int) {
        comp.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(comp, index)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(4)), index + 1)
    }

    private fun addPromptEntryAt(
        text: String,
        @Suppress("UNUSED_PARAMETER") contextFiles: List<Triple<String, String, Int>>?,
        addFn: (JComponent) -> Unit
    ): String {
        finalizeTurn()
        val pane = createMarkdownPane(text)
        val (row, _) = createMessageRow(pane, NativeChatColors.USER_BUBBLE_BG, rightAligned = true)
        addFn(row)
        showWorkingIndicator()
        return java.util.UUID.randomUUID().toString()
    }
}

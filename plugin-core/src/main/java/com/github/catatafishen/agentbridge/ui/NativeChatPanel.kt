package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultStyledDocument

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
    private var placeholderLabel: JBLabel? = null
    private var workingIndicator: JComponent? = null
    private var workingStartMs = 0L
    private val workingTimer = Timer(1000) { updateWorkingLabel() }.apply { isRepeats = true }

    init {
        scrollPane.verticalScrollBar.addAdjustmentListener { e ->
            if (!e.valueIsAdjusting) {
                val bar = scrollPane.verticalScrollBar
                autoScrollEnabled = bar.value + bar.visibleAmount >= bar.maximum - 4
            }
        }
    }

    private class TurnContext(
        val container: JPanel,
        val chipStrip: JPanel,
        var thinkingChip: ThinkingChipComponent? = null,
        var thinkingContent: JPanel? = null,
        var thinkingDoc: DefaultStyledDocument? = null,
        var thinkingExpanded: Boolean = true,
        var textBubble: JPanel? = null,
        var markdownPane: NativeMarkdownPane? = null,
    )

    private fun ensureTurn(): TurnContext {
        currentTurn?.let { return it }

        val chipStrip = WrappingFlowPanel(JBUI.scale(4), JBUI.scale(2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
            border = JBUI.Borders.empty(4, 0)
        }

        val container = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 0)
            }

            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }
        container.add(createTimestampLabel(rightAligned = false).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })
        container.add(chipStrip)

        val turn = TurnContext(container, chipStrip)
        currentTurn = turn
        addRow(container)
        return turn
    }

    private fun finalizeTurn() {
        currentTurn?.markdownPane?.renderNow()
        currentTurn = null
    }

    private fun addRow(comp: JComponent, spacing: Int = JBUI.scale(4)) {
        placeholderLabel?.let { contentPanel.remove(it); placeholderLabel = null }
        comp.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(comp)
        contentPanel.add(Box.createVerticalStrut(spacing))
        contentPanel.revalidate()
        scrollToBottom()
    }

    /** Creates a small right- or left-aligned timestamp label (HH:mm). */
    private fun createTimestampLabel(rightAligned: Boolean = false): JBLabel {
        val ts = MessageFormatter.formatTimestamp(MessageFormatter.timestamp())
        return JBLabel(ts).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size * 0.92f)
            horizontalAlignment = if (rightAligned) SwingConstants.RIGHT else SwingConstants.LEFT
            alignmentX = if (rightAligned) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 2, 1, 2)
        }
    }

    private fun scrollToBottom() {
        if (!autoScrollEnabled) return
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }

    private fun showWorkingIndicator() {
        hideWorkingIndicator()
        workingStartMs = System.currentTimeMillis()
        val bubble = createBubble(NativeChatColors.AGENT_BUBBLE_BG).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        bubble.add(JBLabel("Working…").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
            putClientProperty("workingLabel", true)
        }, BorderLayout.CENTER)
        val row = alignBubble(bubble, rightAligned = false)
        workingIndicator = row
        addRow(row)
        workingTimer.start()
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
        scrollToBottom()
    }

    private fun newTextPane(fg: Color? = null): Pair<DefaultStyledDocument, JTextPane> {
        val doc = DefaultStyledDocument()
        val pane = JTextPane(doc).apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            foreground = fg ?: UIUtil.getLabelForeground()
            font = UIUtil.getLabelFont()
        }
        val attrs = javax.swing.text.SimpleAttributeSet()
        javax.swing.text.StyleConstants.setLineSpacing(attrs, LINE_SPACING_FACTOR)
        doc.setParagraphAttributes(0, doc.length, attrs, false)
        return doc to pane
    }

    private fun appendToDoc(doc: DefaultStyledDocument, text: String) {
        try {
            val atStart = doc.length == 0
            doc.insertString(doc.length, text, null)
            if (atStart) {
                val attrs = javax.swing.text.SimpleAttributeSet()
                javax.swing.text.StyleConstants.setLineSpacing(attrs, LINE_SPACING_FACTOR)
                doc.setParagraphAttributes(0, doc.length, attrs, false)
            }
        } catch (_: BadLocationException) { /* empty */
        }
    }

    /**
     * Creates a rounded message bubble with consistent max-width.
     * All message types (user, agent, nudge, error, info) share this factory.
     * No border — alignment and background color are the only differentiators.
     */
    private fun createBubble(bg: Color): RoundedPanel = object : RoundedPanel(bg) {
        override fun getMaximumSize(): Dimension {
            val pw = parent?.width ?: JBUI.scale(600)
            return Dimension(
                (pw * MAX_BUBBLE_WIDTH_FRACTION).toInt().coerceAtLeast(JBUI.scale(200)),
                Int.MAX_VALUE
            )
        }
    }.apply {
        border = JBUI.Borders.empty(
            JBUI.scale(BUBBLE_V_PAD),
            JBUI.scale(BUBBLE_H_PAD),
            JBUI.scale(BUBBLE_V_PAD),
            JBUI.scale(BUBBLE_H_PAD)
        )
    }

    /** Wraps a bubble in a horizontal row with alignment (left or right). */
    private fun alignBubble(bubble: JComponent, rightAligned: Boolean): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        if (rightAligned) {
            add(Box.createHorizontalGlue())
            add(bubble)
        } else {
            add(bubble)
            add(Box.createHorizontalGlue())
        }
        alignmentX = if (rightAligned) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
    }

    /** Creates a complete message row: timestamp label + aligned bubble. */
    private fun createMessageRow(
        content: JComponent,
        bg: Color,
        rightAligned: Boolean = false,
    ): Pair<JPanel, RoundedPanel> {
        val bubble = createBubble(bg)
        bubble.add(content, BorderLayout.CENTER)
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        row.add(createTimestampLabel(rightAligned).apply {
            alignmentX = if (rightAligned) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
        })
        row.add(alignBubble(bubble, rightAligned))
        return row to bubble
    }

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
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
        hideWorkingIndicator()
        val turn = ensureTurn()
        if (turn.markdownPane == null) {
            val bubble = createBubble(NativeChatColors.AGENT_BUBBLE_BG).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val pane = NativeMarkdownPane(fileNavigator)
            allMarkdownPanes += pane
            bubble.add(pane, BorderLayout.CENTER)
            turn.textBubble = bubble
            turn.markdownPane = pane
            turn.container.add(bubble)
        }
        turn.markdownPane!!.appendMarkdown(text)
        scrollToBottom()
    }

    override fun appendThinkingText(text: String) {
        hideWorkingIndicator()
        val turn = ensureTurn()
        if (turn.thinkingChip == null) {
            val thinkBubble = RoundedPanel(NativeChatColors.THINK_BG, NativeChatColors.THINK_BORDER).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(
                    JBUI.scale(BUBBLE_V_PAD), JBUI.scale(BUBBLE_H_PAD),
                    JBUI.scale(BUBBLE_V_PAD), JBUI.scale(BUBBLE_H_PAD)
                )
            }
            val (doc, pane) = newTextPane(fg = NativeChatColors.THINK)
            pane.font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
            thinkBubble.add(pane, BorderLayout.CENTER)

            val contentWrapper = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(thinkBubble, BorderLayout.CENTER)
            }
            turn.thinkingDoc = doc
            turn.thinkingContent = contentWrapper
            turn.thinkingExpanded = true

            val chipStripIdx = turn.container.components.indexOf(turn.chipStrip)
            turn.container.add(contentWrapper, chipStripIdx + 1)

            val chip = ThinkingChipComponent(active = true) {
                turn.thinkingExpanded = !turn.thinkingExpanded
                contentWrapper.isVisible = turn.thinkingExpanded
                turn.container.revalidate()
                turn.container.repaint()
            }
            turn.thinkingChip = chip
            turn.chipStrip.add(chip, 0)
            turn.chipStrip.isVisible = true
        }
        appendToDoc(turn.thinkingDoc!!, text)
        scrollToBottom()
    }

    override fun collapseThinking() {
        val turn = currentTurn ?: return
        turn.thinkingChip?.setActive(false)
        turn.thinkingContent?.isVisible = false
        turn.thinkingExpanded = false
        turn.container.revalidate()
    }

    override fun addToolCallEntry(
        id: String, title: String, arguments: String?, kind: String?, isMcpHandled: Boolean
    ) {
        val turn = ensureTurn()
        val resolvedKind = kind ?: "other"
        toolCallData[id] = ToolCallData(title, resolvedKind, arguments)
        val chip = ToolChipComponent(title, kind, "running") { showToolPopup(id) }
        allChips[id] = chip
        turn.chipStrip.add(chip)
        turn.chipStrip.isVisible = true
        turn.chipStrip.revalidate()
        if (!spinTimer.isRunning) spinTimer.start()
        scrollToBottom()
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

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
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
        val label = JBLabel("✗ $message").apply {
            foreground = NativeChatColors.ERROR
            font = UIUtil.getLabelFont()
        }
        val (row, _) = createMessageRow(label, NativeChatColors.ERROR_BG)
        addRow(row)
    }

    override fun addInfoEntry(message: String) {
        val label = JBLabel("ℹ $message").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont()
        }
        val (row, _) = createMessageRow(label, NativeChatColors.AGENT_BUBBLE_BG)
        addRow(row)
    }

    override fun hasContent(): Boolean = contentPanel.componentCount > 0

    override fun addSessionSeparator(timestamp: String, agent: String) {
        finalizeTurn()
        val text = buildString {
            if (agent.isNotEmpty()) append("$agent · ")
            if (timestamp.length >= 10) append(timestamp.substring(0, 10)) else append(timestamp)
        }
        val panel = JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.CENTER)
        panel.add(JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
            horizontalAlignment = SwingConstants.CENTER
        }, BorderLayout.SOUTH)
        addRow(panel)
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
        codeStatsLabel = null
        currentModelLabel = null
        if (spinTimer.isRunning) spinTimer.stop()
        placeholderLabel = null
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        hideWorkingIndicator()
        finalizeTurn()
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        val parts = buildList {
            add("${stats.durationMs / 1000}s")
            add("${stats.inputTokens}↑ ${stats.outputTokens}↓")
            if (stats.costUsd > 0) add("${"%.4f".format(stats.costUsd)}$")
            if (stats.linesAdded > 0 || stats.linesRemoved > 0) {
                add("+${stats.linesAdded} −${stats.linesRemoved}")
            }
            if (stats.model.isNotEmpty()) add(stats.model.substringAfterLast('/').substringAfterLast(':'))
        }
        addRow(JBLabel(parts.joinToString(" · ")).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
            border = JBUI.Borders.empty(1, 0, 5, 0)
        })
    }

    override fun showQuickReplies(options: List<String>) {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        options.forEach { opt ->
            panel.add(JButton(opt).apply {
                addActionListener { onQuickReply?.invoke(opt) }
                font = UIUtil.getLabelFont()
            })
        }
        addRow(panel)
    }

    override fun disableQuickReplies() { /* Buttons are statically rendered; no disable mechanism needed */
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
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size - 1f)
        }

        val extendButton = JButton("I need more time").apply {
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size - 1f)
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

    /** Creates a right-aligned nudge row using the same bubble as user messages. */
    private fun createNudgeRow(text: String, source: NudgeSource): JPanel {
        val prefix = if (source.isReprimand) "⚡ $text" else "💬 $text"
        val content = JBLabel("<html>$prefix</html>").apply {
            foreground = UIUtil.getLabelForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, UIUtil.getLabelFont().size - 1f)
        }
        val (row, _) = createMessageRow(content, NativeChatColors.USER_BUBBLE_BG, rightAligned = true)
        return row
    }

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) {
        removeNudgeBubble(id)
        val row = createNudgeRow(text, source)
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
        addRow(createNudgeRow(text, source))
    }

    private val queuedMessages = mutableMapOf<String, JComponent>()

    override fun showQueuedMessage(id: String, text: String) {
        removeQueuedMessage(id)
        val content = JBLabel("⏳ $text").apply {
            foreground = UIUtil.getLabelForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, UIUtil.getLabelFont().size - 1f)
            putClientProperty("queuedText", text)
        }
        val (row, _) = createMessageRow(content, NativeChatColors.USER_BUBBLE_BG, rightAligned = true)
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
            findQueuedLabel(row) == text
        } ?: return
        removeQueuedMessage(entry.key)
    }

    private fun findQueuedLabel(parent: Container): String? {
        for (comp in parent.components) {
            if (comp is JBLabel && comp.getClientProperty("queuedText") != null)
                return comp.getClientProperty("queuedText") as? String
            if (comp is Container) findQueuedLabel(comp)?.let { return it }
        }
        return null
    }

    private var codeStatsLabel: JBLabel? = null

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) {
        if (linesAdded == 0 && linesRemoved == 0) return
        val text = "📊 +$linesAdded / −$linesRemoved lines"
        val label = codeStatsLabel
        if (label != null) {
            label.text = text
        } else {
            val newLabel = JBLabel(text).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.empty(1, 0, 2, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            codeStatsLabel = newLabel
            addRow(newLabel)
        }
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
                font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.empty(1, 0, 2, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            currentModelLabel = newLabel
            addRow(newLabel)
        }
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) {
        if (files.isEmpty()) return
        val chipPanel = WrappingFlowPanel(JBUI.scale(4), JBUI.scale(2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        for ((name, _) in files) {
            chipPanel.add(JBLabel("📄 $name").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.empty(1, 4)
            })
        }
        addRow(chipPanel)
    }

    override fun dispose() {
        allMarkdownPanes.forEach { it.dispose() }
        if (spinTimer.isRunning) spinTimer.stop()
        workingTimer.stop()
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
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size - 1f)
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

        val prevAddRow = if (insertionIndex >= 0) {
            var nextIdx = insertionIndex
            { comp: JComponent -> insertRowAt(comp, nextIdx); nextIdx += 2 /* comp + strut */ }
        } else null

        for (entry in entries) {
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
        finalizeTurn()

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
        contextFiles: List<Triple<String, String, Int>>?,
        addFn: (JComponent) -> Unit
    ): String {
        finalizeTurn()
        val (doc, pane) = newTextPane()
        appendToDoc(doc, text)

        val contentPanel = if (!contextFiles.isNullOrEmpty()) {
            val fileList = contextFiles.joinToString(", ") { (name, _, _) -> name }
            val wrapper = JPanel(BorderLayout()).apply { isOpaque = false }
            wrapper.add(pane, BorderLayout.CENTER)
            wrapper.add(JBLabel("📎 $fileList").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.emptyTop(JBUI.scale(2))
            }, BorderLayout.SOUTH)
            wrapper
        } else {
            pane
        }

        val (row, _) = createMessageRow(contentPanel, NativeChatColors.USER_BUBBLE_BG, rightAligned = true)
        addFn(row)
        showWorkingIndicator()
        return java.util.UUID.randomUUID().toString()
    }

    /**
     * A JPanel that paints a rounded rectangle background behind its children.
     * Optionally draws a 1px rounded border when [borderColor] is non-null.
     * Used directly for non-message elements (thinking panel); message bubbles
     * go through [createBubble] which never passes a border.
     */
    private open class RoundedPanel(
        private val bgColor: Color,
        private val borderColor: Color? = null,
        private val radius: Int = JBUI.scale(10),
    ) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bgColor
            g2.fillRoundRect(0, 0, width, height, radius, radius)
            borderColor?.let {
                g2.color = it
                g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
            }
            g2.dispose()
        }
    }

    /**
     * A compact tool chip with a spinning ring indicator and kind-based coloring.
     * Matches the JCEF `<tool-chip>` component.
     */
    private class ToolChipComponent(
        title: String, kind: String?, status: String,
        private val onClick: (() -> Unit)? = null
    ) : JPanel() {

        private var currentStatus = status
        private var spinAngle = 0
        private val ringSize = JBUI.scale(8)
        private val kindCol: Color = NativeChatColors.kindColor(kind)
        private val bgCol: Color = NativeChatColors.kindBg(kind)
        private val borderCol: Color = NativeChatColors.kindBorder(kind)
        private val hoverBgCol: Color = NativeChatColors.kindBgHover(kind)
        private val hoverBorderCol: Color = NativeChatColors.kindBorderHover(kind)
        private var hovered = false

        init {
            layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
            isOpaque = false
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8))

            if (onClick != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            add(RingIndicator())
            add(JLabel(truncateLabel(title)).apply {
                foreground = kindCol
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
                putClientProperty("html.disable", true)
            })

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onClick?.invoke()
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }

        fun isSpinning(): Boolean = currentStatus.lowercase() in listOf("running", "pending")

        fun updateStatus(status: String) {
            currentStatus = status
            repaint()
        }

        fun advanceSpin() {
            spinAngle = (spinAngle + 15) % 360
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(6)
            g2.color = if (hovered) hoverBgCol else bgCol
            g2.fillRoundRect(0, 0, width, height, r, r)
            g2.color = if (hovered) hoverBorderCol else borderCol
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.dispose()
        }

        private inner class RingIndicator : JComponent() {
            init {
                preferredSize = Dimension(ringSize, ringSize)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val s = ringSize - 2
                when (currentStatus.lowercase()) {
                    "running", "pending" -> {
                        g2.color = kindCol
                        g2.stroke = BasicStroke(1.5f)
                        g2.drawArc(1, 1, s, s, spinAngle, 270)
                    }

                    "complete", "completed", "success", "done" -> {
                        g2.color = kindCol
                        g2.fillOval(1, 1, s, s)
                    }

                    else -> {
                        g2.color = NativeChatColors.ERROR
                        g2.stroke = BasicStroke(1.5f)
                        g2.drawArc(1, 1, s, s, 0, 270)
                    }
                }
                g2.dispose()
            }
        }

        companion object {
            private fun truncateLabel(text: String, max: Int = 50): String =
                if (text.length > max) text.take(max - 1) + "…" else text
        }
    }

    /**
     * A clickable thinking chip with 💭 emoji. Toggles the associated thinking
     * content panel between visible and hidden.
     */
    private class ThinkingChipComponent(
        private var active: Boolean,
        private val onToggle: () -> Unit,
    ) : JPanel() {

        private val emojiLabel: JLabel
        private val textLabel: JLabel

        init {
            layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
            isOpaque = false
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            emojiLabel = JLabel("💭").apply { font = UIUtil.getLabelFont() }
            textLabel = JLabel("Thought").apply {
                foreground = NativeChatColors.THINK
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
            }
            add(emojiLabel)
            add(textLabel)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onToggle()
            })
        }

        fun setActive(isActive: Boolean) {
            active = isActive
            textLabel.text = if (isActive) "Thinking…" else "Thought"
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(6)
            g2.color = NativeChatColors.THINK_BG
            g2.fillRoundRect(0, 0, width, height, r, r)
            g2.color = NativeChatColors.THINK_BORDER
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.dispose()
        }
    }

    /**
     * A flow-based panel that computes preferred height for the actual number
     * of wrapped rows, so the parent BoxLayout allocates correct vertical space.
     */
    private class WrappingFlowPanel(
        hgap: Int, vgap: Int
    ) : JPanel(FlowLayout(FlowLayout.LEFT, hgap, vgap)) {

        init {
            isOpaque = false
        }

        override fun getPreferredSize(): Dimension {
            val maxWidth = parent?.width?.takeIf { it > 0 } ?: return super.getPreferredSize()
            val fl = layout as FlowLayout
            val insets = this.insets
            var x = insets.left
            var y = insets.top
            var rowHeight = 0

            for (comp in components) {
                if (!comp.isVisible) continue
                val d = comp.preferredSize
                if (x + d.width > maxWidth - insets.right && x > insets.left) {
                    y += rowHeight + fl.vgap
                    x = insets.left
                    rowHeight = 0
                }
                x += d.width + fl.hgap
                rowHeight = maxOf(rowHeight, d.height)
            }
            y += rowHeight + insets.bottom
            return Dimension(maxWidth, y)
        }

        override fun getMaximumSize(): Dimension =
            Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
    }

    companion object {
        /** Maximum bubble width as a fraction of the container width (matching JCEF's 94%). */
        private const val MAX_BUBBLE_WIDTH_FRACTION = 0.94

        /** Consistent padding for all message bubbles (vertical / horizontal). */
        private const val BUBBLE_V_PAD = 8
        private const val BUBBLE_H_PAD = 14

        /** Line-spacing factor for JTextPane content (0.3 ≈ 130% effective line height). */
        private const val LINE_SPACING_FACTOR = 0.3f
    }
}

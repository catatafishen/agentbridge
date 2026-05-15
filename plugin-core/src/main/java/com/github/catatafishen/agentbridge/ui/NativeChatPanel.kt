package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
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
 * Visually matches the JCEF panel layout: user bubbles are right-aligned with
 * a blue tint, agent bubbles are left-aligned with a green tint, tool calls
 * appear as compact colored chips in a horizontal strip, and thinking text
 * is displayed in a collapsible gray block.
 *
 * Markdown is not rendered — text is displayed as-is.
 */
class NativeChatPanel(@Suppress("UNUSED_PARAMETER") project: Project) : ChatPanelApi {

    override var onQuickReply: ((String) -> Unit)? = null
    override var onStatusMessage: ((type: String, message: String) -> Unit)? = null

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
    private val spinTimer = Timer(50) {
        allChips.values.filter { it.isSpinning() }.forEach { it.advanceSpin() }
    }.apply { isRepeats = true }

    private var autoScrollEnabled = true
    private var placeholderLabel: JBLabel? = null

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
        var textDoc: DefaultStyledDocument? = null,
    )

    private fun ensureTurn(): TurnContext {
        currentTurn?.let { return it }

        val chipStrip = WrappingFlowPanel(JBUI.scale(4), JBUI.scale(2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
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
        container.add(chipStrip)

        val turn = TurnContext(container, chipStrip)
        currentTurn = turn
        addRow(container)
        return turn
    }

    private fun finalizeTurn() {
        currentTurn = null
    }

    private fun addRow(comp: JComponent) {
        placeholderLabel?.let { contentPanel.remove(it); placeholderLabel = null }
        comp.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(comp)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(3)))
        contentPanel.revalidate()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (!autoScrollEnabled) return
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
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
        return doc to pane
    }

    private fun appendToDoc(doc: DefaultStyledDocument, text: String) {
        try {
            doc.insertString(doc.length, text, null)
        } catch (_: BadLocationException) { /* empty */
        }
    }

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
        finalizeTurn()

        val bubble = object : RoundedPanel(NativeChatColors.USER_BUBBLE_BG) {
            override fun getMaximumSize(): Dimension {
                val pw = parent?.width ?: JBUI.scale(600)
                return Dimension((pw * 0.82).toInt().coerceAtLeast(JBUI.scale(200)), Int.MAX_VALUE)
            }
        }.apply {
            border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(14), JBUI.scale(6), JBUI.scale(14))
        }

        val (doc, pane) = newTextPane()
        appendToDoc(doc, text)
        bubble.add(pane, BorderLayout.CENTER)

        if (!contextFiles.isNullOrEmpty()) {
            val fileList = contextFiles.joinToString(", ") { (name, _, _) -> name }
            bubble.add(JBLabel("📎 $fileList").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.emptyTop(JBUI.scale(2))
            }, BorderLayout.SOUTH)
        }

        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(Box.createHorizontalGlue())
            add(bubble)
        }
        addRow(row)
        return java.util.UUID.randomUUID().toString()
    }

    override fun removePromptEntry(entryId: String) {}

    override fun startStreaming() { /* turn created lazily */
    }

    override fun appendText(text: String) {
        val turn = ensureTurn()
        if (turn.textBubble == null) {
            val bubble = RoundedPanel(NativeChatColors.AGENT_BUBBLE_BG).apply {
                border = JBUI.Borders.empty(JBUI.scale(8), JBUI.scale(14), JBUI.scale(8), JBUI.scale(14))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val (doc, pane) = newTextPane()
            bubble.add(pane, BorderLayout.CENTER)
            turn.textBubble = bubble
            turn.textDoc = doc
            turn.container.add(bubble)
        }
        appendToDoc(turn.textDoc!!, text)
        scrollToBottom()
    }

    override fun appendThinkingText(text: String) {
        val turn = ensureTurn()
        if (turn.thinkingChip == null) {
            val thinkBubble =
                RoundedPanel(NativeChatColors.THINK_BG, borderColor = NativeChatColors.THINK_BORDER).apply {
                    border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12), JBUI.scale(6), JBUI.scale(12))
                    alignmentX = Component.LEFT_ALIGNMENT
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

            val insertIdx = turn.container.components.indexOf(turn.textBubble).let {
                if (it >= 0) it else turn.container.componentCount
            }
            turn.container.add(contentWrapper, insertIdx)

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
        val chip = ToolChipComponent(title, kind, "running")
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
        if (allChips.values.none { it.isSpinning() }) spinTimer.stop()
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
        val bubble = RoundedPanel(NativeChatColors.ERROR_BG).apply {
            border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12))
        }
        bubble.add(JBLabel("✗ $message").apply {
            foreground = NativeChatColors.ERROR
            font = UIUtil.getLabelFont()
        }, BorderLayout.CENTER)
        addRow(bubble)
    }

    override fun addInfoEntry(message: String) {
        addRow(JBLabel("ℹ $message").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont()
            border = JBUI.Borders.empty(2, 0)
        })
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
        contentPanel.removeAll()
        contentPanel.revalidate()
        contentPanel.repaint()
        currentTurn = null
        allChips.clear()
        if (spinTimer.isRunning) spinTimer.stop()
        placeholderLabel = null
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        finalizeTurn()
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        val parts = buildList {
            add("${stats.durationMs / 1000}s")
            add("${stats.inputTokens}↑ ${stats.outputTokens}↓")
            if (stats.costUsd > 0) add("${"%.4f".format(stats.costUsd)}$")
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

    override fun disableQuickReplies() {}
    override fun cancelAllRunning() {
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
        panel.add(buttons, BorderLayout.SOUTH)
        addRow(panel)
    }

    override fun hasPendingAskUserRequest(): Boolean = false
    override fun consumePendingAskUserResponse(response: String): Boolean = false
    override fun clearPendingAskUserRequest(reqId: String?) {}

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) {}
    override fun resolveNudgeBubble(id: String) {}
    override fun removeNudgeBubble(id: String) {}
    override fun addNudgeEntry(id: String, text: String, source: NudgeSource) {}
    override fun showQueuedMessage(id: String, text: String) {}
    override fun removeQueuedMessage(id: String) {}
    override fun removeQueuedMessageByText(text: String) {}

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) {}
    override fun setCurrentModel(modelId: String) {}
    override fun setCurrentProfile(profileId: String) {}
    override fun setCurrentAgent(agentName: String, profileId: String, clientType: String) {}
    override fun addContextFilesEntry(files: List<Pair<String, String>>) {}

    override fun dispose() {
        if (spinTimer.isRunning) spinTimer.stop()
    }

    /**
     * A JPanel that paints a rounded rectangle background behind its children.
     * Optionally draws a 1px rounded border when [borderColor] is non-null.
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
        title: String, kind: String?, status: String
    ) : JPanel() {

        private var currentStatus = status
        private var spinAngle = 0
        private val ringSize = JBUI.scale(8)
        private val kindCol: Color = NativeChatColors.kindColor(kind)
        private val bgCol: Color = NativeChatColors.kindBg(kind)
        private val borderCol: Color = NativeChatColors.kindBorder(kind)

        init {
            layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
            isOpaque = false
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8))

            add(RingIndicator())
            add(JLabel(truncateLabel(title)).apply {
                foreground = kindCol
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
                putClientProperty("html.disable", true)
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
            g2.color = bgCol
            g2.fillRoundRect(0, 0, width, height, r, r)
            g2.color = borderCol
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
}

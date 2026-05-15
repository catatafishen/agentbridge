package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.AdjustmentEvent
import javax.swing.*
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet

/**
 * Native Swing/JetBrains implementation of [ChatPanelApi].
 *
 * Renders messages, thinking blocks, tool chips, sub-agent chips, session
 * separators, and turn stats without JCEF. Markdown is intentionally not
 * rendered in this first version — plain text only.
 */
class NativeChatPanel(@Suppress("UNUSED_PARAMETER") project: Project) : ChatPanelApi {

    override var onQuickReply: ((String) -> Unit)? = null
    override var onStatusMessage: ((type: String, message: String) -> Unit)? = null

    // ── Layout ────────────────────────────────────────────────────────────────

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

    // ── Streaming state ───────────────────────────────────────────────────────

    private var currentTextDoc: DefaultStyledDocument? = null
    private var currentTextPane: JTextPane? = null
    private var currentThinkingDoc: DefaultStyledDocument? = null
    private var currentThinkingPane: JTextPane? = null
    private var currentThinkingPanel: JPanel? = null

    // ── Entry tracking ────────────────────────────────────────────────────────

    /** Maps tool-call / sub-agent ID to its status label for subsequent updates. */
    private val chipStatusLabels = mutableMapOf<String, JLabel>()

    private var autoScrollEnabled = true
    private var placeholderLabel: JBLabel? = null

    init {
        // Track whether the user has scrolled away from the bottom.
        scrollPane.verticalScrollBar.addAdjustmentListener { e: AdjustmentEvent ->
            if (!e.valueIsAdjusting) {
                val bar = scrollPane.verticalScrollBar
                autoScrollEnabled = bar.value + bar.visibleAmount >= bar.maximum - 4
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun addRow(component: JComponent) {
        if (placeholderLabel != null) {
            contentPanel.remove(placeholderLabel!!)
            placeholderLabel = null
        }
        component.alignmentX = Component.LEFT_ALIGNMENT
        component.maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height.coerceAtLeast(1))
        contentPanel.add(component)
        contentPanel.add(verticalGap(3))
        contentPanel.revalidate()
        scrollToBottomIfEnabled()
    }

    private fun scrollToBottomIfEnabled() {
        if (!autoScrollEnabled) return
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun verticalGap(pixels: Int): Component = Box.createVerticalStrut(pixels)

    private fun newTextPane(fgColor: Color? = null, italic: Boolean = false): Pair<DefaultStyledDocument, JTextPane> {
        val doc = DefaultStyledDocument()
        val pane = JTextPane(doc).apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            foreground = fgColor ?: UIUtil.getLabelForeground()
            font = if (italic) UIUtil.getLabelFont().deriveFont(Font.ITALIC) else UIUtil.getLabelFont()
        }
        return doc to pane
    }

    private fun appendToDoc(doc: DefaultStyledDocument, text: String, attrs: SimpleAttributeSet? = null) {
        try {
            doc.insertString(doc.length, text, attrs)
        } catch (_: BadLocationException) {
        }
    }

    private fun sectionHeader(text: String, color: Color): JLabel =
        JBLabel(text).apply {
            foreground = color
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(2, 0, 1, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }

    // ── Prompt ────────────────────────────────────────────────────────────────

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
        finalizeCurrentText()

        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 2, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(sectionHeader("You", USER_COLOR), BorderLayout.NORTH)

        val (doc, pane) = newTextPane()
        appendToDoc(doc, text)
        pane.border = JBUI.Borders.emptyLeft(8)
        panel.add(pane, BorderLayout.CENTER)

        if (!contextFiles.isNullOrEmpty()) {
            val fileList = contextFiles.joinToString(", ") { (name) -> name }
            val filesLabel = JBLabel("📎 $fileList").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.empty(2, 8, 0, 0)
            }
            panel.add(filesLabel, BorderLayout.SOUTH)
        }

        addRow(panel)
        return java.util.UUID.randomUUID().toString()
    }

    override fun removePromptEntry(entryId: String) {
        // v1: no-op — we don't track native panel entry IDs yet
    }

    // ── Agent text (streaming) ────────────────────────────────────────────────

    override fun startStreaming() {
        // Text pane is created lazily on first appendText
    }

    override fun appendText(text: String) {
        if (currentTextPane == null) {
            val (doc, pane) = newTextPane()
            currentTextDoc = doc
            currentTextPane = pane
            pane.border = JBUI.Borders.empty(2, 0)
            addRow(pane)
        }
        appendToDoc(currentTextDoc!!, text)
        scrollToBottomIfEnabled()
    }

    override fun appendThinkingText(text: String) {
        if (currentThinkingPane == null) {
            val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val header = sectionHeader("Thinking…", THINK_COLOR).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
            }
            val (doc, pane) = newTextPane(fgColor = THINK_COLOR, italic = true)
            pane.border = JBUI.Borders.emptyLeft(8)
            currentThinkingDoc = doc
            currentThinkingPane = pane
            currentThinkingPanel = panel
            panel.add(header, BorderLayout.NORTH)
            panel.add(pane, BorderLayout.CENTER)
            addRow(panel)
        }
        appendToDoc(currentThinkingDoc!!, text)
        scrollToBottomIfEnabled()
    }

    override fun collapseThinking() {
        currentThinkingPanel?.let { panel ->
            // Remove the body pane and replace with a one-liner summary
            panel.removeAll()
            val collapsedLabel = JBLabel("▸ Thought").apply {
                foreground = THINK_COLOR
                font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
            }
            panel.add(collapsedLabel, BorderLayout.CENTER)
            panel.maximumSize = Dimension(Int.MAX_VALUE, collapsedLabel.preferredSize.height)
            panel.revalidate()
            panel.repaint()
        }
        currentThinkingPane = null
        currentThinkingDoc = null
        currentThinkingPanel = null
    }

    private fun finalizeCurrentText() {
        currentTextPane = null
        currentTextDoc = null
    }

    // ── Tool calls ────────────────────────────────────────────────────────────

    override fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String?,
        kind: String?,
        isMcpHandled: Boolean
    ) {
        val (row, statusLabel) = buildChipRow(title, kind, "running")
        chipStatusLabels[id] = statusLabel
        addRow(row)
    }

    override fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) {
        val label = chipStatusLabels[id] ?: return
        label.text = statusText(status)
        label.foreground = statusColor(status)
        (label.parent as? JComponent)?.revalidate()
        (label.parent as? JComponent)?.repaint()
    }

    // ── Sub-agents ────────────────────────────────────────────────────────────

    override fun addSubAgentEntry(
        id: String,
        agentType: String,
        description: String,
        prompt: String?,
        initialState: ChatPanelApi.SubAgentInitialState
    ) {
        val label = SUB_AGENT_INFO[agentType]?.displayName ?: agentType
        val (row, statusLabel) = buildChipRow("[$label] $description", "subagent", initialState.status ?: "running")
        chipStatusLabels[id] = statusLabel
        addRow(row)
    }

    override fun updateSubAgentResult(
        id: String,
        status: String,
        result: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) {
        val label = chipStatusLabels[id] ?: return
        label.text = statusText(status)
        label.foreground = statusColor(status)
        (label.parent as? JComponent)?.revalidate()
        (label.parent as? JComponent)?.repaint()
    }

    override fun addSubAgentToolCall(
        subAgentId: String,
        toolId: String,
        title: String,
        arguments: String?,
        kind: String?
    ) {
        val (row, statusLabel) = buildChipRow("  ↳ $title", kind, "running", indent = true)
        chipStatusLabels[toolId] = statusLabel
        addRow(row)
    }

    override fun updateSubAgentToolCall(
        toolId: String,
        status: String,
        details: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) = updateToolCall(toolId, status, ChatPanelApi.ToolCallUpdate(details = details, description = description))

    // ── Status / errors ───────────────────────────────────────────────────────

    override fun addErrorEntry(message: String) {
        val label = JBLabel("✗ $message").apply {
            foreground = JBColor(Color(0xCC0000), Color(0xFF6B6B))
            font = UIUtil.getLabelFont()
            border = JBUI.Borders.empty(2, 0)
        }
        addRow(label)
    }

    override fun addInfoEntry(message: String) {
        val label = JBLabel("ℹ $message").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont()
            border = JBUI.Borders.empty(2, 0)
        }
        addRow(label)
    }

    // ── Session management ────────────────────────────────────────────────────

    override fun hasContent(): Boolean = contentPanel.componentCount > 0

    override fun addSessionSeparator(timestamp: String, agent: String) {
        finalizeCurrentText()
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val text = buildString {
            if (agent.isNotEmpty()) append("$agent · ")
            if (timestamp.length >= 10) append(timestamp.substring(0, 10))
            else append(timestamp)
        }
        val label = JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
            horizontalAlignment = SwingConstants.CENTER
        }
        val separator = JSeparator(SwingConstants.HORIZONTAL)
        panel.add(separator, BorderLayout.CENTER)
        panel.add(label, BorderLayout.SOUTH)
        addRow(panel)
    }

    override fun showPlaceholder(text: String) {
        if (contentPanel.componentCount == 0) {
            val label = JBLabel(text).apply {
                foreground = UIUtil.getContextHelpForeground()
                horizontalAlignment = SwingConstants.CENTER
                alignmentX = Component.CENTER_ALIGNMENT
            }
            placeholderLabel = label
            contentPanel.add(label)
            contentPanel.revalidate()
        }
    }

    override fun clear() {
        contentPanel.removeAll()
        contentPanel.revalidate()
        contentPanel.repaint()
        currentTextPane = null
        currentTextDoc = null
        currentThinkingPane = null
        currentThinkingDoc = null
        currentThinkingPanel = null
        chipStatusLabels.clear()
        placeholderLabel = null
    }

    // ── Turn lifecycle ────────────────────────────────────────────────────────

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        finalizeCurrentText()
        collapseThinking()
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        val parts = buildList {
            add("${stats.durationMs / 1000}s")
            add("${stats.inputTokens}↑ ${stats.outputTokens}↓")
            if (stats.costUsd > 0) add("${"%.4f".format(stats.costUsd)}$")
            if (stats.model.isNotEmpty()) add(stats.model.substringAfterLast('/').substringAfterLast(':'))
        }
        val label = JBLabel(parts.joinToString(" · ")).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
            border = JBUI.Borders.empty(1, 0, 5, 0)
        }
        addRow(label)
    }

    override fun showQuickReplies(options: List<String>) {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        options.forEach { opt ->
            val btn = JButton(opt).apply {
                addActionListener { onQuickReply?.invoke(opt) }
                font = UIUtil.getLabelFont()
            }
            panel.add(btn)
        }
        addRow(panel)
    }

    override fun disableQuickReplies() { /* no-op for v1 */
    }

    override fun cancelAllRunning() {
        finalizeCurrentText()
        collapseThinking()
    }

    // ── Conversation export ───────────────────────────────────────────────────
    // Read methods are delegated to JCEF by BroadcastChatPanel; stubs here.

    override fun getConversationText(): String = ""
    override fun getCompressedSummary(maxChars: Int): String = ""
    override fun getConversationHtml(): String = ""
    override fun getLastResponseText(): String = ""
    override fun getPageHtml(): String? = null

    // ── Permission / ask-user requests ────────────────────────────────────────

    override fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (PermissionResponse) -> Unit
    ) {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val label = JBLabel("<html><b>Allow:</b> $toolDisplayName — $description</html>").apply {
            foreground = UIUtil.getLabelForeground()
        }
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        listOf(
            "Deny" to PermissionResponse.DENY,
            "Allow Once" to PermissionResponse.ALLOW_ONCE,
            "Allow Session" to PermissionResponse.ALLOW_SESSION,
            "Allow Always" to PermissionResponse.ALLOW_ALWAYS
        ).forEach { (text, resp) ->
            buttonPanel.add(JButton(text).apply { addActionListener { onRespond(resp) } })
        }
        panel.add(label, BorderLayout.NORTH)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        addRow(panel)
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
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val label = JBLabel("<html>$question</html>")
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        options.forEach { opt ->
            buttonPanel.add(JButton(opt).apply { addActionListener { onRespond(opt) } })
        }
        panel.add(label, BorderLayout.NORTH)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        addRow(panel)
    }

    override fun hasPendingAskUserRequest(): Boolean = false
    override fun consumePendingAskUserResponse(response: String): Boolean = false
    override fun clearPendingAskUserRequest(reqId: String?) {}

    // ── Nudges / queued messages ──────────────────────────────────────────────
    // v1: no-op — nudges are handled by JCEF panel only

    override fun showNudgeBubble(id: String, text: String, source: NudgeSource) {}
    override fun resolveNudgeBubble(id: String) {}
    override fun removeNudgeBubble(id: String) {}
    override fun addNudgeEntry(id: String, text: String, source: NudgeSource) {}
    override fun showQueuedMessage(id: String, text: String) {}
    override fun removeQueuedMessage(id: String) {}
    override fun removeQueuedMessageByText(text: String) {}

    // ── Agent / model state ───────────────────────────────────────────────────

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) {}
    override fun setCurrentModel(modelId: String) {}
    override fun setCurrentProfile(profileId: String) {}
    override fun setCurrentAgent(agentName: String, profileId: String, clientType: String) {}
    override fun addContextFilesEntry(files: List<Pair<String, String>>) {}

    // ── Dispose ───────────────────────────────────────────────────────────────

    override fun dispose() {}

    // ── Chip rendering helpers ────────────────────────────────────────────────

    private fun buildChipRow(
        title: String,
        kind: String?,
        status: String,
        indent: Boolean = false
    ): Pair<JPanel, JLabel> {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, if (indent) 16 else 0, 1, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val kindText = kindIcon(kind)
        if (kindText != null) {
            panel.add(JLabel(kindText).apply { font = UIUtil.getLabelFont() })
        }
        panel.add(JBLabel(title).apply {
            foreground = TOOL_COLOR
            font = UIUtil.getLabelFont()
        })
        val statusLabel = JLabel(statusText(status)).apply {
            foreground = statusColor(status)
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getLabelFont().size - 1f)
        }
        panel.add(statusLabel)
        return panel to statusLabel
    }

    private fun kindIcon(kind: String?): String? = when (kind?.lowercase()) {
        "read", "read_file", "view" -> "📖"
        "write", "write_file", "create" -> "✏"
        "run", "bash", "terminal", "shell" -> "▶"
        "search", "grep", "glob" -> "🔍"
        "git" -> "⎇"
        "web", "web_fetch", "web_search" -> "🌐"
        "subagent" -> "🤖"
        else -> null
    }

    private fun statusText(status: String): String = when (status.lowercase()) {
        "running" -> "⟳ running"
        "complete", "completed", "success" -> "✓"
        "error", "failed" -> "✗ error"
        "denied", "auto_denied" -> "⊘ denied"
        "pending" -> "…"
        else -> status
    }

    private fun statusColor(status: String): Color = when (status.lowercase()) {
        "running", "pending" -> UIUtil.getLabelForeground()
        "complete", "completed", "success" -> JBColor(Color(0x008000), Color(0x4EC94E))
        "error", "failed" -> JBColor(Color(0xCC0000), Color(0xFF6B6B))
        "denied", "auto_denied" -> UIUtil.getContextHelpForeground()
        else -> UIUtil.getLabelForeground()
    }
}

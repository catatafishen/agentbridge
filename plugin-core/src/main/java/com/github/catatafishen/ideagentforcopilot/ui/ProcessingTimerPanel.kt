package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Timer

/**
 * Toolbar widget that displays elapsed time, tool-call count, and token/cost usage for the
 * current prompt turn. Clicking toggles between turn-level and session-level aggregate stats.
 */
internal class ProcessingTimerPanel(
    private val supportsMultiplier: () -> Boolean,
    private val localSessionRequests: () -> Int,
) : JBPanel<ProcessingTimerPanel>(), Disposable {

    private val spinner = AsyncProcessIcon("AgentProcessing")
    private val doneIcon = JBLabel(AllIcons.Actions.Checked)
    private val timerLabel = JBLabel("")
    private val toolsLabel = JBLabel("")
    private val addedLabel = JBLabel("")
    private val removedLabel = JBLabel("")
    private val requestsLabel = JBLabel("")
    private var startedAt = 0L
    private var toolCallCount = 0
    private var addedLineCount = 0
    private var removedLineCount = 0
    private val ticker = Timer(1000) { refreshDisplay() }

    private var sessionTotalTimeMs = 0L
    private var sessionTotalToolCalls = 0
    private var sessionTotalAddedLines = 0
    private var sessionTotalRemovedLines = 0
    private var sessionTurnCount = 0
    private var isRunning = false

    private var turnInputTokens = 0
    private var turnOutputTokens = 0
    private var turnCostUsd: Double? = null
    private var sessionTotalInputTokens = 0L
    private var sessionTotalOutputTokens = 0L
    private var sessionTotalCostUsd = 0.0

    private val modeTurn = 0
    private val modeSession = 1
    private var displayMode = modeTurn

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyRight(6)
        alignmentY = CENTER_ALIGNMENT
        val smallGray = JBUI.Fonts.smallFont()
        spinner.isVisible = false
        doneIcon.isVisible = false
        doneIcon.font = smallGray
        timerLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        timerLabel.font = smallGray
        timerLabel.isVisible = false
        toolsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        toolsLabel.font = smallGray
        toolsLabel.isVisible = false
        addedLabel.foreground = ToolRenderers.SUCCESS_COLOR
        addedLabel.font = smallGray
        addedLabel.toolTipText = "Lines added"
        addedLabel.isVisible = false
        removedLabel.foreground = ToolRenderers.FAIL_COLOR
        removedLabel.font = smallGray
        removedLabel.toolTipText = "Lines removed"
        removedLabel.isVisible = false
        requestsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        requestsLabel.font = smallGray
        requestsLabel.isVisible = false
        add(spinner)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(doneIcon)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(timerLabel)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(toolsLabel)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(addedLabel)
        add(Box.createHorizontalStrut(JBUI.scale(2)))
        add(removedLabel)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(requestsLabel)
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Click to toggle turn/session stats"
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                displayMode = if (displayMode == modeTurn) modeSession else modeTurn
                refreshDisplay()
            }
        })
    }

    fun start() {
        startedAt = System.currentTimeMillis()
        toolCallCount = 0
        addedLineCount = 0
        removedLineCount = 0
        turnInputTokens = 0
        turnOutputTokens = 0
        turnCostUsd = null
        isRunning = true
        displayMode = modeTurn
        timerLabel.text = "0s"
        toolsLabel.text = ""
        addedLabel.text = ""
        removedLabel.text = ""
        requestsLabel.text = ""
        spinner.isVisible = true
        spinner.resume()
        doneIcon.isVisible = false
        timerLabel.isVisible = true
        toolsLabel.isVisible = false
        addedLabel.isVisible = false
        removedLabel.isVisible = false
        requestsLabel.isVisible = false
        isVisible = true
        ticker.start()
        revalidate(); repaint()
    }

    fun stop() {
        ticker.stop()
        isRunning = false
        sessionTotalTimeMs += System.currentTimeMillis() - startedAt
        sessionTotalToolCalls += toolCallCount
        sessionTotalAddedLines += addedLineCount
        sessionTotalRemovedLines += removedLineCount
        sessionTurnCount++
        refreshDisplay()
        spinner.suspend()
        spinner.isVisible = false
        doneIcon.isVisible = true
        revalidate(); repaint()
    }

    fun recordUsage(inputTokens: Int, outputTokens: Int, costUsd: Double?) {
        turnInputTokens = inputTokens
        turnOutputTokens = outputTokens
        turnCostUsd = costUsd
        sessionTotalInputTokens += inputTokens
        sessionTotalOutputTokens += outputTokens
        if (costUsd != null) sessionTotalCostUsd += costUsd
        refreshDisplay()
    }

    fun resetSession() {
        sessionTotalTimeMs = 0L
        sessionTotalToolCalls = 0
        sessionTotalAddedLines = 0
        sessionTotalRemovedLines = 0
        sessionTurnCount = 0
        sessionTotalInputTokens = 0L
        sessionTotalOutputTokens = 0L
        sessionTotalCostUsd = 0.0
        displayMode = modeTurn
    }

    fun restoreSessionStats(
        totalTimeMs: Long, totalInputTokens: Long, totalOutputTokens: Long,
        totalCostUsd: Double, totalToolCalls: Int,
        totalLinesAdded: Int, totalLinesRemoved: Int, turnCount: Int
    ) {
        sessionTotalTimeMs = totalTimeMs
        sessionTotalInputTokens = totalInputTokens
        sessionTotalOutputTokens = totalOutputTokens
        sessionTotalCostUsd = totalCostUsd
        sessionTotalToolCalls = totalToolCalls
        sessionTotalAddedLines = totalLinesAdded
        sessionTotalRemovedLines = totalLinesRemoved
        sessionTurnCount = turnCount
        refreshDisplay()
    }

    override fun dispose() {
        ticker.stop()
        com.intellij.openapi.util.Disposer.dispose(spinner)
    }

    fun incrementToolCalls() {
        toolCallCount++
        refreshDisplay()
    }

    fun setCodeChangeStats(added: Int, removed: Int) {
        addedLineCount = added
        removedLineCount = removed
        refreshDisplay()
    }

    private fun refreshDisplay() {
        when (displayMode) {
            modeTurn -> refreshTurnMode()
            modeSession -> refreshSessionMode()
        }
        revalidate(); repaint()
    }

    private fun refreshTurnMode() {
        toolTipText = "Turn stats · Click for session"
        updateLabel()
        toolsLabel.text = if (toolCallCount > 0) "\u2022 $toolCallCount tools" else ""
        toolsLabel.isVisible = toolCallCount > 0

        addedLabel.text = if (addedLineCount > 0) "+$addedLineCount" else ""
        addedLabel.isVisible = addedLineCount > 0
        removedLabel.text = if (removedLineCount > 0) "-$removedLineCount" else ""
        removedLabel.isVisible = removedLineCount > 0

        val hasUsage =
            !isRunning && (turnCostUsd?.let { it > 0.0 } ?: false || (turnInputTokens + turnOutputTokens) > 0)
        if (hasUsage) {
            requestsLabel.text =
                "\u2022 ${BillingManager.formatUsageChip(turnInputTokens, turnOutputTokens, turnCostUsd)}"
            requestsLabel.isVisible = requestsLabel.text.length > 2
        } else {
            requestsLabel.isVisible = false
        }
        if (!isRunning) {
            doneIcon.icon = AllIcons.Actions.Checked; doneIcon.text = null
        }
    }

    private fun refreshSessionMode() {
        val totalMs = sessionTotalTimeMs + if (isRunning) (System.currentTimeMillis() - startedAt) else 0
        val totalSec = totalMs / 1000
        timerLabel.text = if (totalSec < 60) "${totalSec}s" else "${totalSec / 60}m ${totalSec % 60}s"
        val totalTools = sessionTotalToolCalls + if (isRunning) toolCallCount else 0
        toolsLabel.text = if (totalTools > 0) "\u2022 $totalTools tools" else ""
        toolsLabel.isVisible = totalTools > 0

        val totalAdded = sessionTotalAddedLines + if (isRunning) addedLineCount else 0
        val totalRemoved = sessionTotalRemovedLines + if (isRunning) removedLineCount else 0
        addedLabel.text = if (totalAdded > 0) "+$totalAdded" else ""
        addedLabel.isVisible = totalAdded > 0
        removedLabel.text = if (totalRemoved > 0) "-$totalRemoved" else ""
        removedLabel.isVisible = totalRemoved > 0

        toolTipText = "Session totals · Click for turn"
        doneIcon.icon = null; doneIcon.text = "\u2211"
        if (supportsMultiplier()) {
            val sessionReqs = localSessionRequests()
            requestsLabel.text = if (sessionReqs > 0) "\u2022 $sessionReqs req" else "\u2022 0 req"
            requestsLabel.isVisible = true
        } else {
            val totalTok = sessionTotalInputTokens + sessionTotalOutputTokens
            if (totalTok > 0 || sessionTotalCostUsd > 0.0) {
                requestsLabel.text = "\u2022 ${
                    BillingManager.formatUsageChip(
                        sessionTotalInputTokens.toInt(),
                        sessionTotalOutputTokens.toInt(),
                        sessionTotalCostUsd
                    )
                }"
                requestsLabel.isVisible = requestsLabel.text.length > 2
            } else {
                requestsLabel.isVisible = false
            }
        }
    }

    private fun updateLabel() {
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000
        timerLabel.text = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
    }
}

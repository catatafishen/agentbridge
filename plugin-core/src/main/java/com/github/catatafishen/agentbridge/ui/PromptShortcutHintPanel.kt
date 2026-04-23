package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Footer panel showing a single, context-appropriate keyboard shortcut hint
 * below the chat prompt:
 *  - Idle  → `Enter` Send
 *  - Busy  → `Ctrl+Enter` Stop && Send
 *
 * The previous 2×2 hint grid listed four shortcuts at once, which added
 * visual weight without providing information the user needed right now.
 * Users can still see the full list in Settings → AgentBridge → Chat Input
 * documentation, and all shortcuts continue to work regardless of what is
 * displayed here.
 *
 * Reads actual key bindings from the IntelliJ keymap so customized
 * shortcuts are reflected in the displayed hints.
 *
 * The shortcut hints feature can be disabled in Settings → AgentBridge → Chat Input.
 */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val cell: JPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0)).apply {
        isOpaque = false
    }
    private val actionLabel: JBLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 4, 8)
        add(cell, BorderLayout.CENTER)
        setRunning(false)
    }

    /**
     * Switch the displayed shortcut to match the most relevant action for the
     * current state:
     *  - `false` → Enter ▸ Send
     *  - `true`  → Ctrl+Enter ▸ Stop && Send
     */
    fun setRunning(running: Boolean) {
        val (actionId, fallbackStroke, label) = if (running) {
            Triple(
                PromptShortcutAction.STOP_AND_SEND_ID,
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                "Stop && Send"
            )
        } else {
            Triple(
                PromptShortcutAction.SEND_ID,
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                "Send"
            )
        }
        cell.removeAll()
        val stroke = PromptShortcutAction.resolveKeystroke(actionId, fallbackStroke)
        KeyBadge.keystrokeTokens(stroke).forEach { cell.add(KeyBadge(it)) }
        actionLabel.text = label
        cell.add(actionLabel)
        cell.revalidate()
        cell.repaint()
    }

    /**
     * Legacy entry point — now delegates to [setRunning]. Kept so callers that
     * were toggling "send/nudge" don't need to change signature.
     */
    fun setNudgeMode(nudge: Boolean) = setRunning(nudge)
}

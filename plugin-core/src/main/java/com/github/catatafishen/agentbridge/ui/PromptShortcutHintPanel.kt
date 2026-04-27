package com.github.catatafishen.agentbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.util.ui.JBUI
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.retrieveColor
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Bottom-toolbar panel that lists the keyboard shortcuts relevant to the
 * current chat-input state, left-aligned alongside the model selector and
 * Send button.
 *
 *  - Idle  → Enter ▸ Send,  Shift+Enter ▸ New line
 *  - Busy  → Enter ▸ Nudge,  Ctrl+Enter ▸ Stop && send,  Ctrl+Shift+Enter ▸ Queue,  Shift+Enter ▸ New line
 *  - When a nudge or queued message is pending, an extra `↑ ▸ Edit last` hint
 *    is appended.
 *
 * Each entry renders as one or more [KeyBadge] tokens followed by a small
 * action label. Reads actual key bindings from the IntelliJ keymap so
 * customized shortcuts are reflected.
 *
 * Rendered as a [JewelComposePanel] for theme-aware coloring and IDE Compose
 * UI Preview support. Items clip from the right when space is tight rather
 * than wrapping to a second row.
 *
 * Visibility honours the *Show shortcut hints* toggle in
 * Settings → AgentBridge → Chat Input.
 */
class PromptShortcutHintPanel : JPanel(BorderLayout()) {

    private var shortcutsState by mutableStateOf<List<Pair<KeyStroke, String>>>(emptyList())

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        add(JewelComposePanel(focusOnClickInside = false) {
            ShortcutHintStrip(shortcutsState)
        })
    }

    /**
     * Replace the displayed hints with the given keystroke/label pairs. The
     * order of [shortcuts] is preserved, rendered left-to-right.
     */
    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        shortcutsState = shortcuts
    }
}

@Composable
private fun ShortcutHintStrip(shortcuts: List<Pair<KeyStroke, String>>) {
    val isDark = JewelTheme.isDark
    val helpForeground = retrieveColor(
        key = "Label.infoForeground",
        isDark = isDark,
        default = Color(0xFF6E6E6E),
        defaultDark = Color(0xFF8C8C8C),
    )
    Row(
        modifier = Modifier.fillMaxHeight().wrapContentHeight(Alignment.CenterVertically),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shortcuts.forEach { (stroke, label) ->
            ShortcutEntry(stroke = stroke, label = label, helpForeground = helpForeground)
        }
    }
}

@Composable
private fun ShortcutEntry(stroke: KeyStroke, label: String, helpForeground: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyBadge.keystrokeTokens(stroke).forEach { token -> KeyToken(token) }
        Text(text = label, color = helpForeground, fontSize = 11.sp)
    }
}

@Composable
private fun KeyToken(token: String) {
    val background = KeyBadge.BACKGROUND.toComposeColor()
    val borderColor = KeyBadge.BORDER.toComposeColor()
    val labelForeground = retrieveColor(
        key = "Label.foreground",
        isDark = JewelTheme.isDark,
        default = Color.Black,
        defaultDark = Color(0xFFBBBBBB),
    )
    Text(
        text = token,
        color = labelForeground,
        fontSize = 10.sp,
        modifier = Modifier
            .background(color = background, shape = RoundedCornerShape(4.dp))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Preview
@Composable
private fun PreviewShortcutHintStrip() {
    ShortcutHintStrip(
        shortcuts = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0) to "Send",
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK) to "New line",
        ),
    )
}

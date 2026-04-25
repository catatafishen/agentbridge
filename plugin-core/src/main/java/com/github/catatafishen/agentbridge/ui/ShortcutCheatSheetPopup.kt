package com.github.catatafishen.agentbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.vcsUtil.showAbove
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.retrieveColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Cheat-sheet popup listing all chat prompt keyboard shortcuts.
 * Shown when the user presses Ctrl+/ in the prompt — the same pattern
 * used by Slack, GitHub, and VS Code.
 */
object ShortcutCheatSheetPopup {

    private data class ShortcutEntry(
        val actionId: String,
        val fallback: KeyStroke,
        val description: String,
    )

    private val ENTRIES = listOf(
        ShortcutEntry(
            PromptShortcutAction.SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "Send prompt (or nudge agent)"
        ),
        ShortcutEntry(
            PromptShortcutAction.NEW_LINE_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "Insert new line"
        ),
        ShortcutEntry(
            PromptShortcutAction.STOP_AND_SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "Stop agent and send"
        ),
        ShortcutEntry(
            PromptShortcutAction.QUEUE_ID,
            KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ),
            "Queue (send after agent finishes)"
        ),
        ShortcutEntry(
            PromptShortcutAction.SHOW_SHORTCUTS_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.CTRL_DOWN_MASK),
            "Show this popup"
        ),
    )

    fun show(owner: JComponent) {
        val resolvedEntries = ENTRIES.map { entry ->
            PromptShortcutAction.resolveKeystroke(entry.actionId, entry.fallback) to entry.description
        }
        val panel = JewelComposePanel(focusOnClickInside = false) {
            ShortcutCheatSheetContent(resolvedEntries)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("Chat Shortcuts")
            .setFocusable(false)
            .setMovable(true)
            .setResizable(false)
            .createPopup()
            .showAbove(owner)
    }
}

@Composable
private fun ShortcutCheatSheetContent(entries: List<Pair<KeyStroke, String>>) {
    val isDark = JewelTheme.isDark
    val helpForeground = retrieveColor(
        key = "Label.infoForeground",
        isDark = isDark,
        default = Color(0xFF6E6E6E),
        defaultDark = Color(0xFF8C8C8C),
    )
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { (stroke, description) ->
            ShortcutCheatRow(stroke = stroke, description = description, helpForeground = helpForeground)
        }
    }
}

@Composable
private fun ShortcutCheatRow(stroke: KeyStroke, description: String, helpForeground: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyBadge.keystrokeTokens(stroke).forEach { token -> KeyToken(token) }
        }
        Text(text = description, color = helpForeground, fontSize = 11.sp)
    }
}

@Preview
@Composable
private fun PreviewShortcutCheatSheet() {
    ShortcutCheatSheetContent(
        entries = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0) to "Send prompt (or nudge agent)",
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK) to "Insert new line",
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK) to "Stop agent and send",
            KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ) to "Queue (send after agent finishes)",
            KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.CTRL_DOWN_MASK) to "Show this popup",
        )
    )
}

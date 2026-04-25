package com.github.catatafishen.agentbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.retrieveColor
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.KeyStroke

/**
 * Utility object for keyboard badge rendering — color constants and keystroke token parsing.
 * Visual rendering uses [KeyToken] (Compose) rather than the old JBLabel-based class.
 */
object KeyBadge {

    val BACKGROUND: JBColor = JBColor(0xF0F0F0, 0x3C3F41)
    val BORDER: JBColor = JBColor(0xC8C8C8, 0x5E6060)

    /** Formats a keystroke as a single string; prefer [keystrokeTokens] for badge rendering. */
    fun formatKeystroke(stroke: KeyStroke): String = keystrokeTokens(stroke).joinToString("+")

    /**
     * Returns one string per individual key token so each can be rendered in its own badge.
     * On macOS modifiers are returned as Unicode symbols (⌘, ⌃, ⌥, ⇧); on Windows/Linux
     * Ctrl and Alt are returned as text while Shift uses ⇧.
     */
    fun keystrokeTokens(stroke: KeyStroke): List<String> {
        val isMac = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("mac")
        val tokens = mutableListOf<String>()
        val modifiers = stroke.modifiers
        if (isMac) {
            if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) tokens.add("⌃")
            if (modifiers and InputEvent.ALT_DOWN_MASK != 0) tokens.add("⌥")
            if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) tokens.add("⇧")
            if (modifiers and InputEvent.META_DOWN_MASK != 0) tokens.add("⌘")
            tokens.add(
                when (stroke.keyCode) {
                    KeyEvent.VK_ENTER -> "⏎"
                    KeyEvent.VK_BACK_SPACE -> "⌫"
                    KeyEvent.VK_ESCAPE -> "⎋"
                    KeyEvent.VK_TAB -> "⇥"
                    else -> KeyEvent.getKeyText(stroke.keyCode)
                }
            )
        } else {
            if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) tokens.add("Ctrl")
            if (modifiers and InputEvent.ALT_DOWN_MASK != 0) tokens.add("Alt")
            if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) tokens.add("⇧")
            tokens.add(
                when (stroke.keyCode) {
                    KeyEvent.VK_ENTER -> "↵"
                    KeyEvent.VK_BACK_SPACE -> "⌫"
                    KeyEvent.VK_ESCAPE -> "Esc"
                    KeyEvent.VK_TAB -> "⇥"
                    KeyEvent.VK_UP -> "↑"
                    else -> KeyEvent.getKeyText(stroke.keyCode)
                }
            )
        }
        return tokens
    }
}

/**
 * Renders a single key token (e.g. "⌘", "Enter", "Ctrl") as a rounded badge with background
 * and border, theme-aware via [JewelTheme]. Used by [PromptShortcutHintPanel] and
 * [ShortcutCheatSheetPopup].
 */
@Composable
internal fun KeyToken(token: String) {
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

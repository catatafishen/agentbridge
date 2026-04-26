package com.github.catatafishen.agentbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.util.ui.JBUI
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.retrieveColor
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Previewable Jewel Compose layout for the chat input section.
 *
 * The composable handles only **layout and chrome** (rounded rect fill, border, divider).
 * It is designed to be wired inside the existing Swing [inputSection] container in
 * [ChatToolWindowContent] — that outer container owns the resize drag zone
 * (`JBUI.Borders.empty(6, 0, 1, 2)`) and must NOT be replaced.
 *
 * **Production wiring notes:**
 * - Wrap with `JewelComposePanel(focusOnClickInside = false)` to avoid stealing focus from
 *   the [EditorTextField].
 * - Pass [ShortcutHintStrip] directly to the [shortcuts] slot (make it `internal` first).
 *   Do **not** route [PromptShortcutHintPanel] through `SwingPanel`; that creates a
 *   Swing→Compose→Swing→Compose chain which is fragile.
 */
@Composable
fun ChatInputPanel(
    modifier: Modifier = Modifier,
    /** Vertical side-button rail (left column). */
    sideButtons: @Composable BoxScope.() -> Unit = {},
    /** Main text editor area (top of the right column). */
    editor: @Composable BoxScope.() -> Unit = {},
    /** Shortcut hint strip — [RowScope] allows using `Modifier.weight(1f)`. */
    shortcuts: @Composable RowScope.() -> Unit = {},
    /** Model selector toolbar placed to the right of the shortcut strip. */
    modelSelector: @Composable RowScope.() -> Unit = {},
    /** Send / Stop action button (rightmost in the bottom bar). */
    sendButton: @Composable RowScope.() -> Unit = {},
) {
    val isDark = JewelTheme.isDark
    val fillColor = retrieveColor(
        key = "TextField.background",
        isDark = isDark,
        default = Color(0xFFFFFFFF),
        defaultDark = Color(0xFF2B2D30),
    )
    // Matches JBUI.CurrentTheme.ToolWindow.borderColor() used in inputSection.paintComponent.
    val dividerColor = remember(isDark) { JBUI.CurrentTheme.ToolWindow.borderColor().toComposeColor() }
    val borderColor = retrieveColor(
        key = "Component.borderColor",
        isDark = isDark,
        default = Color(0xFFADADAD),
        defaultDark = Color(0xFF5A5D63),
    )

    Row(
        modifier = modifier.drawBehind {
            val arc = CornerRadius(8.dp.toPx())
            val inset = 1.dp.toPx()
            drawRoundRect(fillColor, cornerRadius = arc)
            // Inset border by 1dp on each side — matches Swing's drawRoundRect(1, 1, w-2, h-2).
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = arc,
                style = Stroke(inset),
            )
        },
    ) {
        Box(content = sideButtons)

        // 1dp vertical divider, inset 2dp top and bottom — matches the drawLine in paintComponent.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .width(1.dp)
                .background(dividerColor),
        )

        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                content = editor,
            )
            // Mirrors innerBar: GridBagLayout with shortcuts(weight=1) + rightGroup(fixed).
            // JBUI.Borders.empty(0, 1) → padding(horizontal = 1.dp).
            // height(IntrinsicSize.Max) makes all slot items share the tallest child's height,
            // so shortcuts, model selector, and send button are always the same height.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(horizontal = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shortcuts()
                modelSelector()
                Spacer(modifier = Modifier.width(2.dp))
                sendButton()
            }
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

/**
 * Preview approximating the real chat input panel.
 *
 * Slot content uses lightweight placeholders sized to match the real Swing components:
 * - Side buttons: vertical toolbar with `JBUI.Borders.empty(4, 4, 4, 0)`, icon buttons 22dp each
 * - Editor: `padding(horizontal = 6.dp, vertical = 4.dp)` (matches `promptTextArea.border`)
 * - Model selector: bordered combo-button style
 * - Send button: white bordered square (not active state)
 *
 * Swing components (EditorTextField, ActionToolbar) cannot be rendered in a Compose @Preview —
 * the chrome (border, colors, divider, spacing) is what this preview is designed to verify.
 */
@Preview
@Composable
private fun ChatInputPanelPreview() {
    ChatInputPanel(
        modifier = Modifier.fillMaxWidth().height(140.dp),
        sideButtons = {
            // Mirrors controlsToolbar with JBUI.Borders.empty(4, 4, 4, 0).
            // Actions: RestartSessionGroup (Restart icon), AttachContextDropdownAction (Add icon),
            // DisconnectOrStopAction (power.svg). Each pair separated by a toolbar separator.
            Column(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, bottom = 4.dp),
            ) {
                PreviewToolbarIcon("↺")
                PreviewToolbarSeparator()
                PreviewToolbarIcon("+")
                PreviewToolbarSeparator()
                PreviewToolbarIcon("⏻")
            }
        },
        editor = {
            // Matches promptTextArea.border = JBUI.Borders.empty(4, 6).
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text("Ask GitHub Copilot...", color = Color.Gray, fontSize = 13.sp)
            }
        },
        shortcuts = {
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyToken("↵")
                Text("Send", fontSize = 11.sp, color = Color.Gray)
                KeyToken("⇧")
                Text("+", fontSize = 11.sp, color = Color.Gray)
                KeyToken("↵")
                Text("New line", fontSize = 11.sp, color = Color.Gray)
            }
        },
        modelSelector = {
            // Bordered combo-button matching the real ModelSelectorAction toolbar style.
            Row(
                modifier = Modifier
                    .height(24.dp)
                    .border(1.dp, Color(0xFFADADAD), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Claude Sonnet 4.6", fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Text("▼", fontSize = 9.sp, color = Color.Gray)
            }
        },
        sendButton = {
            // Matches innerInputToolbar style: bordered square, idle (not-sending) state.
            // rightGroup uses BorderLayout(JBUI.scale(2), 0) → 2dp gap before send button.
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(1.dp, Color(0xFFADADAD), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("→", fontSize = 13.sp)
            }
        },
    )
}

/** Plain icon button matching IntelliJ vertical toolbar item (22dp, no background in idle state). */
@Composable
private fun PreviewToolbarIcon(label: String) {
    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp)
    }
}

/** 1dp separator line matching IntelliJ toolbar separator appearance. */
@Composable
private fun PreviewToolbarSeparator() {
    Box(
        modifier = Modifier
            .width(22.dp)
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(Color(0xFFCDCDCD)),
    )
}

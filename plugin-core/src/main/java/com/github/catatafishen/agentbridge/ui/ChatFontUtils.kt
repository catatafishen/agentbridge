package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Client property key used to tag Swing components with their font size delta
 * relative to the current IDE editor font size. The tree-walk in [NativeChatPanel]
 * reads this to update fonts live when the user changes editor font size via
 * Alt+Shift+. / Alt+Shift+,.
 */
const val CHAT_FONT_DELTA = "agentbridge.chatFontDelta"

/**
 * Client property key for the AWT font style (e.g. [Font.PLAIN], [Font.ITALIC]).
 * Only set when non-PLAIN; absence implies [Font.PLAIN].
 */
const val CHAT_FONT_STYLE = "agentbridge.chatFontStyle"

/**
 * Returns a font whose size is the current IDE editor font size plus [delta],
 * using the UI label font family and the given [style].
 */
fun chatFont(delta: Int = 0, style: Int = Font.PLAIN): Font {
    val size = PlatformApiCompat.getEditorFontSize()
    return UIUtil.getLabelFont().deriveFont(style, (size + delta).toFloat())
}

/**
 * Sets this component's font to [chatFont] and tags it with [CHAT_FONT_DELTA]
 * (and [CHAT_FONT_STYLE] when non-PLAIN) so that [NativeChatPanel] can update
 * it live whenever the editor font size changes.
 */
fun JComponent.applyChatFont(delta: Int = 0, style: Int = Font.PLAIN) {
    putClientProperty(CHAT_FONT_DELTA, delta)
    if (style != Font.PLAIN) putClientProperty(CHAT_FONT_STYLE, style)
    font = chatFont(delta, style)
}

package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIManager

/** Theme colors and CSS variable generation for the chat JCEF panel. */
object ChatTheme {

    const val SA_COLOR_COUNT = 8
    const val LINK_COLOR_KEY = "Link.activeForeground"

    val USER_COLOR: JBColor = JBColor(Color(0x28, 0x6B, 0xC0), Color(86, 156, 214))
    val AGENT_COLOR: JBColor = JBColor(Color(0x2A, 0x80, 0x2A), Color(150, 200, 150))
    val TOOL_COLOR: JBColor = JBColor(Color(0x6A, 0x4C, 0xB0), Color(180, 160, 220))
    val THINK_COLOR: JBColor = JBColor(Color(0x68, 0x68, 0x68), Color(176, 176, 176))
    val ERROR_COLOR: JBColor = JBColor(Color(0xC7, 0x22, 0x22), Color(199, 34, 34))

    val SA_COLORS: Array<JBColor> = arrayOf(
        JBColor(Color(0x1E, 0x88, 0x7E), Color(38, 166, 154)),
        JBColor(Color(0xC8, 0x8E, 0x32), Color(240, 173, 78)),
        JBColor(Color(0x7B, 0x5D, 0xAE), Color(156, 120, 216)),
        JBColor(Color(0xB8, 0x58, 0x78), Color(216, 112, 147)),
        JBColor(Color(0x3B, 0x9F, 0xB8), Color(91, 192, 222)),
        JBColor(Color(0x68, 0x9F, 0x30), Color(139, 195, 74)),
        JBColor(Color(0xC6, 0x50, 0x50), Color(229, 115, 115)),
        JBColor(Color(0x28, 0x6B, 0xC0), Color(86, 156, 214)),
    )

    // Tool kind chip colors — semantic categories (kept for external API compatibility)
    val KIND_THINK_COLOR: JBColor = JBColor(Color(0x7A, 0x70, 0xA8), Color(170, 155, 210))
    val KIND_OTHER_COLOR: JBColor = JBColor(Color(0x78, 0x7C, 0x80), Color(160, 165, 170))

    fun buildCssVars(): String = buildCssVars(null)

    /**
     * Builds the CSS variable string for the chat panel, using per-project kind-color
     * overrides from [mcpSettings] when provided.
     */
    fun buildCssVars(mcpSettings: McpServerSettings?): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val bg = JBUI.CurrentTheme.ToolWindow.background()
        val codeBg =
            UIManager.getColor("Editor.backgroundColor") ?: JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
        val tblBorder =
            UIManager.getColor("TableCell.borderColor") ?: JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x45, 0x48, 0x4A))
        val thBg =
            UIManager.getColor("TableHeader.background") ?: JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x35, 0x38, 0x3B))
        val spinBg = UIManager.getColor("Panel.background") ?: JBColor(Color(0xDD, 0xDD, 0xDD), Color(0x55, 0x55, 0x55))
        val linkColor = UIManager.getColor(LINK_COLOR_KEY) ?: JBColor(Color(0x28, 0x7B, 0xDE), Color(0x58, 0x9D, 0xF6))
        val tooltipBg =
            UIManager.getColor("ToolTip.background") ?: JBColor(Color(0xF7, 0xF7, 0xF7), Color(0x3C, 0x3F, 0x41))
        val sb = StringBuilder()
        sb.append("--font-family:'${font.family}',sans-serif;--font-size:${font.size - 2}pt;--code-font-size:${font.size - 3}pt;--code-font:'JetBrains Mono','${font.family}',monospace;")
        sb.append(
            "--fg:${rgb(fg)};--fg-a05:${rgba(fg, 0.05)};--fg-a08:${rgba(fg, 0.08)};--fg-a16:${
                rgba(
                    fg,
                    0.16
                )
            };--fg-muted:${rgba(fg, 0.55)};--bg:${rgb(bg)};"
        )
        sb.append(
            "--user:${rgb(USER_COLOR)};--user-a06:${rgba(USER_COLOR, 0.06)};--user-a08:${
                rgba(
                    USER_COLOR,
                    0.08
                )
            };"
        )
        sb.append(
            "--user-a12:${rgba(USER_COLOR, 0.12)};--user-a15:${rgba(USER_COLOR, 0.15)};--user-a16:${
                rgba(
                    USER_COLOR,
                    0.16
                )
            };"
        )
        sb.append("--user-a18:${rgba(USER_COLOR, 0.18)};--user-a25:${rgba(USER_COLOR, 0.25)};")
        sb.append(
            "--agent:${rgb(AGENT_COLOR)};--agent-a06:${rgba(AGENT_COLOR, 0.06)};--agent-a08:${
                rgba(
                    AGENT_COLOR,
                    0.08
                )
            };"
        )
        sb.append("--agent-a10:${rgba(AGENT_COLOR, 0.10)};--agent-a16:${rgba(AGENT_COLOR, 0.16)};")
        sb.append(
            "--think:${rgb(THINK_COLOR)};--think-a04:${rgba(THINK_COLOR, 0.04)};--think-a06:${
                rgba(
                    THINK_COLOR,
                    0.06
                )
            };"
        )
        sb.append(
            "--think-a08:${rgba(THINK_COLOR, 0.08)};--think-a10:${rgba(THINK_COLOR, 0.10)};--think-a16:${
                rgba(
                    THINK_COLOR,
                    0.16
                )
            };"
        )
        sb.append(
            "--think-a25:${rgba(THINK_COLOR, 0.25)};--think-a30:${rgba(THINK_COLOR, 0.30)};--think-a35:${
                rgba(
                    THINK_COLOR,
                    0.35
                )
            };"
        )
        sb.append("--think-a40:${rgba(THINK_COLOR, 0.40)};--think-a55:${rgba(THINK_COLOR, 0.55)};")
        sb.append(
            "--tool:${rgb(TOOL_COLOR)};--tool-a08:${rgba(TOOL_COLOR, 0.08)};--tool-a16:${
                rgba(
                    TOOL_COLOR,
                    0.16
                )
            };--tool-a40:${rgba(TOOL_COLOR, 0.40)};"
        )
        sb.append("--spin-bg:${rgb(spinBg)};--code-bg:${rgb(codeBg)};--tbl-border:${rgb(tblBorder)};--th-bg:${rgb(thBg)};")
        sb.append("--link:${rgb(linkColor)};--tooltip-bg:${rgb(tooltipBg)};")
        sb.append(
            "--error:${rgb(ERROR_COLOR)};--error-a05:${rgba(ERROR_COLOR, 0.05)};--error-a06:${
                rgba(
                    ERROR_COLOR,
                    0.06
                )
            };"
        )
        sb.append("--error-a12:${rgba(ERROR_COLOR, 0.12)};--error-a16:${rgba(ERROR_COLOR, 0.16)};")
        val bannerInfoBg = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
        val bannerInfoBorder = JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR
        val bannerErrorBg = JBUI.CurrentTheme.Banner.ERROR_BACKGROUND
        val bannerErrorBorder = JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR
        val bannerFg = JBUI.CurrentTheme.Banner.FOREGROUND
        sb.append("--banner-info-bg:${rgb(bannerInfoBg)};--banner-info-border:${rgb(bannerInfoBorder)};")
        sb.append("--banner-error-bg:${rgb(bannerErrorBg)};--banner-error-border:${rgb(bannerErrorBorder)};")
        sb.append("--banner-fg:${rgb(bannerFg)};")
        sb.append("--shadow:${rgba(THINK_COLOR, 0.25)};")
        for (i in SA_COLORS.indices) {
            val c = SA_COLORS[i]
            sb.append(
                "--sa-c$i:${rgb(c)};--sa-c$i-a06:${rgba(c, 0.06)};--sa-c$i-a10:${
                    rgba(
                        c,
                        0.10
                    )
                };--sa-c$i-a15:${rgba(c, 0.15)};"
            )
        }
        sb.append("--active-agent:${rgb(AGENT_COLOR)};--active-agent-a06:${rgba(AGENT_COLOR, 0.06)};")
        val kindRead = ToolKindColors.readColor(mcpSettings)
        val kindEdit = ToolKindColors.editColor(mcpSettings)
        val kindExecute = ToolKindColors.executeColor(mcpSettings)
        sb.append("--kind-read:${rgb(kindRead)};--kind-edit:${rgb(kindEdit)};")
        sb.append("--kind-execute:${rgb(kindExecute)};--kind-search:${rgb(kindRead)};")
        sb.append("--kind-think:${rgb(KIND_THINK_COLOR)};--kind-other:${rgb(KIND_OTHER_COLOR)};")
        return sb.toString()
    }

    /**
     * Map agent profile ID to a color index (from SA_COLORS).
     * Default agents each get a distinct color; custom profiles cycle through.
     */
    fun agentColorIndex(profileId: String): Int = when (profileId) {
        "copilot" -> 0          // teal
        "claude-code" -> 1       // orange
        "claude-cli" -> 1        // orange (same as claude-code)
        "junie" -> 2             // purple
        "kiro" -> 3              // pink
        "opencode" -> 4          // blue
        else -> profileId.hashCode().and(Int.MAX_VALUE) % SA_COLOR_COUNT
    }

    fun activeAgentCss(profileId: String): String {
        val idx = agentColorIndex(profileId)
        return "--active-agent:var(--sa-c$idx);--active-agent-a06:var(--sa-c$idx-a06);"
    }

    fun getAgentIconSvg(profileId: String?, isDark: Boolean): String {
        val name = when (profileId) {
            "anthropic", "claude-cli" -> "claude"
            "copilot" -> "copilot"
            "opencode" -> "opencode"
            "junie" -> "junie"
            "kiro" -> "kiro"
            "codex" -> "codex"
            else -> "agentbridge"
        }
        val suffix = if (isDark) "_dark" else ""
        val path = "/icons/expui/$name$suffix.svg"
        return try {
            ChatTheme::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun rgb(c: Color) = "rgb(${c.red},${c.green},${c.blue})"
    private fun rgba(c: Color, a: Double) = "rgba(${c.red},${c.green},${c.blue},$a)"
}

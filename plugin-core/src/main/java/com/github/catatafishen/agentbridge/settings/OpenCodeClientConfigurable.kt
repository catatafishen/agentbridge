package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.services.GenericSettings
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.Path

@Suppress("unused")
class OpenCodeClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("OpenCode"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()
    private val refreshResultLabel = JBLabel()
    private val genericSettings = GenericSettings(AGENT_ID)

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") {
            cell(statusLabel)
        }
        row {
            val installNote = JBLabel(
                "<html>Install with <code>npm i -g opencode-ai</code>. " +
                    "Ensure it's available on PATH.</html>"
            )
            installNote.foreground = UIUtil.getContextHelpForeground()
            cell(installNote)
        }
        row {
            val link = HyperlinkLabel("Install OpenCode from npmjs.com/package/opencode-ai")
            link.setHyperlinkTarget("https://www.npmjs.com/package/opencode-ai")
            cell(link)
        }
        separator()
        row("OpenCode binary:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { emptyText.text = "Auto-detect (leave empty)" }
                .comment("Leave empty to auto-detect on PATH.")
                .bindText(
                    { AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID).orEmpty() },
                    { AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, it.trim()) }
                )
        }
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment(
                    "Choose a theme-aware accent color for message bubbles when using OpenCode."
                )
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)) },
                    // SonarQube S6619 falsely reports `?.` as useless: bindItem setter receives ThemeColor?
                    @Suppress("kotlin:S6619")
                    { AcpClient.saveAgentBubbleColorKey(AGENT_ID, it?.name) }
                )
        }
        row("Session history limit:") {
            spinner(0..2_000_000, 50_000)
                .comment(
                    "Maximum characters of conversation history exported to OpenCode's database. " +
                        "0 = unlimited. OpenCode handles context compaction internally; set only if " +
                        "you hit overflow errors. Default: unlimited (0)."
                )
                .bindIntValue(
                    { genericSettings.getContextHistoryLimit(DEFAULT_CONTEXT_LIMIT_CHARS) },
                    { genericSettings.setContextHistoryLimit(it) }
                )
        }
        separator()
        row("Model definitions:") {
            button("Refresh") { refreshModelDefinitions() }
                .comment(
                    "OpenCode bundles a snapshot of model capabilities that may be outdated. " +
                        "If a model reports missing features (e.g. no image support), " +
                        "click to clear the cache so OpenCode fetches the latest definitions " +
                        "from models.dev on next launch."
                )
            cell(refreshResultLabel)
        }
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshStatusAsync()
    }

    private fun refreshModelDefinitions() {
        try {
            val deleted = deleteModelCache()
            if (deleted) {
                refreshResultLabel.text = "✓ Cache cleared — restart OpenCode to fetch latest definitions"
                refreshResultLabel.foreground = JBColor(0x008000, 0x4EC94E)
            } else {
                refreshResultLabel.text = "No cached model definitions found"
                refreshResultLabel.foreground = UIUtil.getContextHelpForeground()
            }
        } catch (e: Exception) {
            refreshResultLabel.text = "Error: ${e.message}"
            refreshResultLabel.foreground = JBColor(0xCC0000, 0xFF6B6B)
        }
    }

    private fun refreshStatusAsync() {
        statusLabel.text = "Checking..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val version = AcpClientBinaryResolver(AGENT_ID, AGENT_ID).detectVersion()
            ApplicationManager.getApplication().invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ OpenCode found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text = "OpenCode not found on PATH — install with npm i -g opencode-ai"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_LIMIT_CHARS = 0
        const val ID = "com.github.catatafishen.agentbridge.client.opencode"
        private const val AGENT_ID = "opencode"

        /**
         * Resolves the OpenCode model cache directory based on the OS.
         *
         * - Linux: `$XDG_CACHE_HOME/opencode/` or `~/.cache/opencode/`
         * - macOS: `~/Library/Caches/opencode/`
         * - Windows: `%LOCALAPPDATA%/opencode/`
         */
        private fun resolveModelCacheDir(): Path {
            val home = Path.of(System.getProperty("user.home"))
            return when {
                SystemInfo.isMac -> home.resolve("Library/Caches/opencode")
                SystemInfo.isWindows -> {
                    val localAppData = System.getenv("LOCALAPPDATA")
                    if (localAppData != null) Path.of(localAppData, "opencode")
                    else home.resolve("AppData/Local/opencode")
                }

                else -> {
                    val xdgCache = System.getenv("XDG_CACHE_HOME")
                    if (xdgCache != null) Path.of(xdgCache, "opencode")
                    else home.resolve(".cache/opencode")
                }
            }
        }

        /**
         * Deletes the cached `models.json` so OpenCode fetches fresh definitions
         * from models.dev on next launch.
         *
         * @return true if a cache file was found and deleted
         */
        fun deleteModelCache(): Boolean {
            val cacheFile = resolveModelCacheDir().resolve("models.json")
            return Files.deleteIfExists(cacheFile)
        }
    }
}

package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.bridge.ProfileBasedAgentConfig
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Encapsulates all authentication login logic for Copilot CLI and GitHub CLI.
 * Extracted from AgenticCopilotToolWindowContent to keep the tool window lean.
 */
class AuthLoginService(private val project: Project) {

    private companion object {
        private val LOG = Logger.getInstance(AuthLoginService::class.java)
        private const val OS_NAME_PROPERTY = "os.name"

        /** Matches device codes like ABCD-1234, AB12-CD34, etc. */
        private val CODE_PATTERN = Regex("\\b[A-Z0-9]{4,8}-[A-Z0-9]{4,8}\\b")

        /** Matches verification URLs (GitHub device flow endpoints). */
        private val URL_PATTERN = Regex("https?://[\\w.-]+(?:/[\\w./-]*)?/device(?:/[\\w./-]*)?")
    }

    // ── Auth error tracking ─────────────────────────────────────────────────

    /**
     * Sticky auth-error message, set when a prompt fails with an authentication error.
     * Checked by [copilotSetupDiagnostics] so the banner stays visible even when
     * `session/new` succeeds without auth (auth is only enforced on `session/update`).
     * Cleared when the user signs in (diagnostics passes with no auth error).
     */
    @Volatile
    var pendingAuthError: String? = null

    /** Record an auth error so the setup banner can pick it up on next poll. */
    fun markAuthError(message: String) {
        pendingAuthError = message
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    /** Returns null if the active agent is installed and authenticated, or an error description. */
    fun copilotSetupDiagnostics(): String? {
        // If there's a pending auth error that hasn't been cleared, return it
        // BUT give it a chance to recover by checking fresh auth periodically
        val agentManager = ActiveAgentManager.getInstance(project)

        // Always attempt fresh auth check - don't rely solely on sticky pendingAuthError
        return try {
            val authCheck = agentManager.client.checkAuthentication()
            // If auth check succeeds (returns null), clear any stale pending error
            if (authCheck == null) {
                pendingAuthError = null
            }
            authCheck
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to connect to agent"
            // Only set pendingAuthError for auth-related failures, not network/process issues
            if (errorMsg.lowercase().contains("auth") || errorMsg.lowercase().contains("sign in")) {
                pendingAuthError = errorMsg
            }
            errorMsg
        }
    }

    /** Clear the pending auth error so the next diagnostic check re-verifies from scratch. */
    fun clearPendingAuthError() {
        pendingAuthError = null
    }

    /** Returns null if GH CLI is installed and authenticated, or an error description. */
    internal fun ghSetupDiagnostics(billing: com.github.catatafishen.ideagentforcopilot.ui.BillingManager): String? {
        val ghCli = billing.client.findGhCli()
            ?: return "GitHub CLI (gh) is not installed — it is used to display billing and usage information."
        return if (!billing.client.isGhAuthenticated(ghCli))
            "Not authenticated with GitHub CLI (gh) — click Sign In in the banner above."
        else null
    }

    /** Returns true when [message] indicates a Copilot CLI authentication failure. */
    fun isAuthenticationError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("auth") ||
            lower.contains("copilot cli") ||
            lower.contains("authenticated")
    }

    // ── Login flows ──────────────────────────────────────────────────────────

    /** Parsed device-flow info from the CLI's stdout. */
    data class DeviceCodeInfo(val code: String, val url: String)

    /**
     * Resolves the auth command from the ACP `authMethod` or falls back to `copilot auth login`.
     * Splits the result into a list suitable for [ProcessBuilder].
     */
    private fun resolveAuthCommand(): List<String> {
        var command = "copilot auth login"
        try {
            val authMethod = ActiveAgentManager.getInstance(project).client.authMethod
            if (authMethod?.command != null) {
                val args = authMethod.args?.joinToString(" ") ?: ""
                command = "${authMethod.command} $args".trim()
            }
        } catch (_: Exception) { /* best-effort */
        }
        return command.split(" ").filter { it.isNotEmpty() }
    }

    fun startInlineAuth(
        onDeviceCode: (DeviceCodeInfo) -> Unit,
        onAuthComplete: () -> Unit,
        onFallback: () -> Unit,
    ): Process? {
        val cmd = resolveAuthCommand()
        val process: Process
        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)

            // Configure agent-specific environment (e.g., COPILOT_HOME for copilot CLI)
            configureAuthEnvironment(pb)

            process = pb.start()
        } catch (e: Exception) {
            LOG.warn("Inline auth: could not start process, falling back to terminal", e)
            onFallback()
            return null
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val foundCode = readAuthProcessOutput(process, onDeviceCode)
                val exitCode = process.waitFor()
                ApplicationManager.getApplication().invokeLater {
                    handleAuthProcessExit(exitCode, foundCode, onAuthComplete, onFallback)
                }
            } catch (e: Exception) {
                if (!process.isAlive) return@executeOnPooledThread
                LOG.warn("Inline auth: reader failed, falling back", e)
                ApplicationManager.getApplication().invokeLater { onFallback() }
            }
        }
        return process
    }

    private fun readAuthProcessOutput(process: Process, onDeviceCode: (DeviceCodeInfo) -> Unit): Boolean {
        var foundCode = false
        var pendingCode: String? = null
        var pendingUrl: String? = null
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val parsed = parseDeviceCode(line, pendingCode, pendingUrl)
                if (parsed.code != null) pendingCode = parsed.code
                if (parsed.url != null) pendingUrl = parsed.url
                if (pendingCode != null && pendingUrl != null && !foundCode) {
                    foundCode = true
                    val info = DeviceCodeInfo(pendingCode, pendingUrl)
                    ApplicationManager.getApplication().invokeLater { onDeviceCode(info) }
                }
            }
        }
        return foundCode
    }

    private fun handleAuthProcessExit(
        exitCode: Int, foundCode: Boolean,
        onAuthComplete: () -> Unit, onFallback: () -> Unit
    ) {
        if (exitCode == 0) {
            onAuthComplete()
        } else if (!foundCode) {
            LOG.info("Inline auth: process exited with $exitCode, no device code found — falling back")
            onFallback()
        }
        // If we did show a code but exit != 0, user probably cancelled — do nothing
    }

    private data class ParseResult(val code: String?, val url: String?)

    /**
     * Tries to extract a device code or verification URL from a single line of CLI output.
     * Returns any newly found values; previous partial results are passed in so the caller
     * can accumulate across lines (code and URL may appear on separate lines).
     *
     * Patterns are intentionally broad so minor CLI format changes don't break parsing.
     */
    private fun parseDeviceCode(line: String, existingCode: String?, existingUrl: String?): ParseResult {
        // Device codes: 4-8 uppercase-alphanumeric groups separated by a hyphen
        val codeMatch = CODE_PATTERN.find(line)
        val code = codeMatch?.value ?: existingCode

        // Verification URLs: any https URL containing "login/device" or "/device" path
        val urlMatch = URL_PATTERN.find(line)
        val url = urlMatch?.value ?: existingUrl

        return ParseResult(code, url)
    }

    fun startCopilotLogin() {
        val resolvedCommand = resolveAuthCommand().joinToString(" ")
        val envVars = getAuthEnvironmentVars()
        runAuthInEmbeddedTerminal(project, resolvedCommand, envVars, "Copilot Sign In") {
            startCopilotLoginExternal(resolvedCommand)
        }
    }

    /**
     * Opens a "GitHub Sign In" terminal tab and runs `gh auth login`.
     * Falls back to an external terminal if the embedded terminal plugin is absent.
     */
    fun startGhLogin() {
        runAuthInEmbeddedTerminal(project, "gh auth login", "GitHub Sign In") {
            startGhLoginExternal()
        }
    }

    // ── External-terminal fallbacks (used only when terminal plugin is absent) ──

    private fun startCopilotLoginExternal(command: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val envVars = getAuthEnvironmentVars()
                launchExternalTerminal(command, envVars)
            } catch (e: Exception) {
                LOG.warn("Could not open external terminal for Copilot auth", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "The IntelliJ Terminal plugin is not available and no external terminal could be opened.\n\n" +
                            "Run manually: $command",
                        "Authentication Setup",
                    )
                }
            }
        }
    }

    private fun startGhLoginExternal() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launchExternalTerminal("gh auth login", emptyMap())
            } catch (e: Exception) {
                LOG.warn("Could not open external terminal for GitHub auth", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "The IntelliJ Terminal plugin is not available and no external terminal could be opened.\n\n" +
                            "Run manually: gh auth login",
                        "GitHub CLI Authentication",
                    )
                }
            }
        }
    }

    private fun launchExternalTerminal(command: String, envVars: Map<String, String>) {
        val os = System.getProperty(OS_NAME_PROPERTY).lowercase()
        val fullCommand = buildCommandWithEnvironment(command, envVars)

        when {
            os.contains("win") ->
                ProcessBuilder("cmd", "/c", "start", "cmd", "/k", fullCommand).start()

            os.contains("mac") ->
                ProcessBuilder(
                    "osascript", "-e",
                    "tell application \"Terminal\" to do script \"$fullCommand\"",
                ).start()

            else ->
                ProcessBuilder(
                    "sh", "-c",
                    "x-terminal-emulator -e '$fullCommand' || " +
                        "gnome-terminal -- bash -c '$fullCommand' || " +
                        "konsole -e bash -c '$fullCommand' || " +
                        "xterm -e bash -c '$fullCommand'",
                ).start()
        }
    }

    /**
     * Configures environment variables for auth commands to use the same
     * home directories as the main ACP agent processes.
     */
    private fun configureAuthEnvironment(pb: ProcessBuilder) {
        try {
            val agentManager = ActiveAgentManager.getInstance(project)
            val profile = agentManager.getActiveProfile()

            // Use the same environment configuration as agent processes
            val config = ProfileBasedAgentConfig(profile, ToolRegistry.getInstance(project))
            config.configureAgentEnvironment(pb.environment(), project.basePath)
        } catch (e: Exception) {
            LOG.warn("Failed to configure auth environment", e)
        }
    }

    /**
     * Gets environment variables for auth commands to use the same
     * home directories as the main ACP agent processes.
     */
    private fun getAuthEnvironmentVars(): Map<String, String> {
        return try {
            val agentManager = ActiveAgentManager.getInstance(project)
            val profile = agentManager.getActiveProfile()

            // Get environment configuration as agent processes would use
            val config = ProfileBasedAgentConfig(profile, ToolRegistry.getInstance(project))
            val envMap = mutableMapOf<String, String>()
            config.configureAgentEnvironment(envMap, project.basePath)
            envMap
        } catch (e: Exception) {
            LOG.warn("Failed to get auth environment variables", e)
            emptyMap()
        }
    }

    /**
     * Builds a shell command that exports environment variables before running the main command.
     * Handles both Unix-like and Windows shells.
     */
    private fun buildCommandWithEnvironment(command: String, envVars: Map<String, String>): String {
        if (envVars.isEmpty()) return command

        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        return if (isWindows) {
            // Windows cmd: set VAR=value && command
            val exports = envVars.entries.joinToString(" && ") { (key, value) -> "set $key=$value" }
            "$exports && $command"
        } else {
            // Unix shells: export VAR=value; command
            val exports = envVars.entries.joinToString("; ") { (key, value) -> "export $key='$value'" }
            "$exports; $command"
        }
    }
}

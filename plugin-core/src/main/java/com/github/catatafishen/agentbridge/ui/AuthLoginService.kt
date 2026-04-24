package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient
import com.github.catatafishen.agentbridge.bridge.ProfileBasedAgentConfig
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.settings.BinaryDetector
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
        val agentManager = ActiveAgentManager.getInstance(project)
        return try {
            val authCheck = agentManager.checkAuthentication()
            // checkAuthentication() returns null both when the agent is verified-OK AND
            // when there's no live agent to probe (not started / unhealthy). We must NOT
            // treat the second case as "authenticated" — otherwise a previous runtime
            // auth failure (Claude/Codex, observed from a failed prompt) would have its
            // sticky pendingAuthError cleared here, hiding the setup banner and re-enabling
            // sending while auth is still broken. pendingAuthError is cleared explicitly by
            // the login flows (and by the banner Retry handler), so we just preserve it
            // whenever the runtime check has nothing concrete to report.
            authCheck ?: pendingAuthError
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to connect to agent"
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
    internal fun ghSetupDiagnostics(billing: BillingManager): String? {
        val ghCli = billing.client.findGhCli()
            ?: return "GitHub CLI (gh) is not installed — it is used to display billing and usage information."
        return if (!billing.client.isGhAuthenticated(ghCli))
            "Not authenticated with GitHub CLI (gh) — click Sign In in the banner above."
        else null
    }

    /** Returns true when [message] indicates a Copilot CLI authentication failure. */
    fun isAuthenticationError(message: String): Boolean =
        AuthCommandBuilder.isAuthenticationError(message)

    // ── Login flows ──────────────────────────────────────────────────────────

    /** Parsed device-flow info from the CLI's stdout. */
    data class DeviceCodeInfo(val code: String, val url: String)

    /**
     * Resolves the auth command for the active agent profile.
     * Returns the full path to the binary and login args.
     *
     * Binary path resolution is done independently of the running client so that
     * the auth command can be built even when the client has not started yet (e.g.
     * because it failed with "Authentication required" before we could show the login
     * button).
     */
    private fun resolveAuthCommand(): List<String> {
        val agentManager = ActiveAgentManager.getInstance(project)
        val profile = agentManager.getActiveProfile()
        val config = ProfileBasedAgentConfig.create(profile, ToolRegistry.getInstance(project), project)

        // Resolve binary path without relying on the running client
        val binaryPath = config.agentBinaryPath
            ?: try {
                config.findAgentBinary()
            } catch (e: Exception) {
                LOG.warn("AuthLoginService: findAgentBinary failed, trying BinaryDetector", e)
                BinaryDetector.findBinaryPath(profile.binaryName)
                    ?: profile.binaryName.also {
                        LOG.warn("AuthLoginService: BinaryDetector could not find '${profile.binaryName}', using bare name as last resort")
                    }
            }

        // Determine the login subcommand from the running client if available;
        // fall back to "login" if the client hasn't started (auth failure during startup).
        val loginArgs = try {
            val authMethod = agentManager.client.authMethod
            LOG.info("AuthLoginService: authMethod = $authMethod, id = ${authMethod?.id}")
            when {
                authMethod?.id?.contains("copilot") == true -> listOf("login")
                authMethod?.id?.contains("github") == true -> listOf("login")
                authMethod?.id != null -> authMethod.id.replace("-", " ").split(" ").drop(1)
                else -> listOf("login")
            }
        } catch (e: Exception) {
            LOG.info("AuthLoginService: client not available (${e.message}), defaulting to 'login'")
            listOf("login")
        }

        LOG.info("AuthLoginService: resolved command = '$binaryPath ${loginArgs.joinToString(" ")}'")
        return listOf(binaryPath) + loginArgs
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

    private fun parseDeviceCode(
        line: String,
        existingCode: String?,
        existingUrl: String?,
    ): AuthCommandBuilder.ParseResult =
        AuthCommandBuilder.parseDeviceCode(line, existingCode, existingUrl)

    fun startCopilotLogin() {
        val command = resolveAuthCommand().joinToString(" ")
        val envVars = getAuthEnvironmentVars()
        LOG.info("AuthLoginService: starting copilot login with command='$command', envVars=$envVars")
        runAuthInEmbeddedTerminal(project, command, envVars, "Copilot Sign In") {
            startCopilotLoginExternal(command)
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

    /**
     * Opens an embedded terminal tab and runs the given [command] for terminal-based sign-in
     * (e.g. {@code codex login --device-auth}). Falls back to an external terminal if needed.
     */
    fun startTerminalSignIn(command: String) {
        val tabName = "${command.substringBefore(" ").replaceFirstChar { it.uppercase() }} Sign In"
        runAuthInEmbeddedTerminal(project, command, tabName) {
            startTerminalSignInExternal(command)
        }
    }

    private fun startTerminalSignInExternal(command: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launchExternalTerminal(command, emptyMap())
            } catch (e: Exception) {
                LOG.warn("Could not open external terminal for sign-in: $command", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "The IntelliJ Terminal plugin is not available and no external terminal could be opened.\n\nRun manually: $command",
                        "Sign In",
                    )
                }
            }
        }
    }

    /**
     * Logs out the active agent by cleaning up its authentication data.
     * Always clears pending auth errors.
     *
     * - **Claude CLI**: no-op — the plugin does not manage Claude credentials.
     *   Users must run `claude /logout` themselves or remove `~/.claude/.credentials.json`
     *   (and the macOS Keychain entry on newer Claude versions) manually
     * - **Kiro CLI**: delegates to `kiro-cli logout`
     * - **Copilot CLI**: runs `gh auth logout` because Copilot CLI authenticates
     *   via the `gh` token stored in the system keyring — there is no
     *   `copilot logout` subcommand
     * - **Codex**: not invoked — there is no `codex logout` subcommand. The Logout
     *   button is hidden when Codex is the active agent (see ChatToolWindowContent).
     * - **Others**: deletes `.agent-work/<agentId>/` if it exists
     *
     * The caller is responsible for stopping the agent process
     * (disconnectFromAgent) after this method returns.
     */
    fun logout() {
        try {
            val agentManager = ActiveAgentManager.getInstance(project)
            val profile = agentManager.getActiveProfile()
            val agentId = profile.id

            // Claude CLI: no programmatic logout. The plugin does not touch credential stores
            // (files, OS keychain). Users must run `claude /logout` themselves or delete their
            // credentials manually. See docs/AUTH-HANDLING.md.
            if (agentId == ClaudeCliClient.PROFILE_ID) {
                LOG.info("Claude CLI logout: no-op (plugin does not manage Claude credentials)")
                clearPendingAuthError()
                return
            }

            // Kiro CLI manages its own credentials — delegate to `kiro-cli logout`
            if (agentId == AgentProfileManager.KIRO_PROFILE_ID) {
                val binary = profile.binaryName.ifEmpty { "kiro-cli" }
                val result = ProcessBuilder(binary, "logout")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                LOG.info("Kiro logout exit code: $result")
                clearPendingAuthError()
                return
            }

            // Copilot CLI has no logout subcommand — it authenticates via `gh` keyring token.
            // Run `gh auth logout` to clear the credential from the system keyring.
            if (agentId == AgentProfileManager.COPILOT_PROFILE_ID) {
                logoutGhAuth()
                clearPendingAuthError()
                return
            }

            // Generic fallback: delete .agent-work/<agentId>/ if it exists
            val projectBasePath = project.basePath ?: return
            val agentWorkDir = java.nio.file.Path.of(projectBasePath, ".agent-work", agentId)
            if (java.nio.file.Files.exists(agentWorkDir)) {
                agentWorkDir.toFile().deleteRecursively()
                LOG.info("Deleted agent data for '$agentId' at $agentWorkDir")
            } else {
                LOG.info("No agent data found for '$agentId' at $agentWorkDir (nothing to clean up)")
            }
            clearPendingAuthError()
        } catch (e: Exception) {
            LOG.warn("Failed to clean up during logout", e)
        }
    }

    /**
     * Runs `gh auth logout --hostname github.com` to revoke the credential
     * from the system keyring. Copilot CLI authenticates via this credential,
     * so removing it is the only way to fully log out.
     *
     * The command is run with a 10-second timeout. Failure is logged but
     * does not prevent the rest of the logout flow.
     */
    private fun logoutGhAuth() {
        try {
            val pb = ProcessBuilder("gh", "auth", "logout", "--hostname", "github.com")
            pb.redirectErrorStream(true)
            val process = pb.start()
            // gh auth logout prompts for confirmation — pipe "Y" via stdin
            process.outputStream.bufferedWriter().use { it.write("Y\n"); it.flush() }
            val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (finished) {
                val output = process.inputStream.bufferedReader().readText().trim()
                LOG.info("gh auth logout exit=${process.exitValue()}: $output")
            } else {
                LOG.warn("gh auth logout timed out after 10s — killing process")
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            LOG.warn("Failed to run gh auth logout", e)
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
            val config = ProfileBasedAgentConfig.create(profile, ToolRegistry.getInstance(project), project)
            // Use login-specific env so HOME is NOT overridden (overriding HOME breaks copilot npm wrapper)
            config.configureLoginCommandEnvironment(pb.environment(), project.basePath)
        } catch (e: Exception) {
            LOG.warn("Failed to configure auth environment", e)
        }
    }

    /**
     * Gets environment variables for auth (login) commands.
     * Only sets agent-specific credential-directory vars; does NOT override HOME.
     */
    private fun getAuthEnvironmentVars(): Map<String, String> {
        return try {
            val agentManager = ActiveAgentManager.getInstance(project)
            val profile = agentManager.getActiveProfile()
            val config = ProfileBasedAgentConfig.create(profile, ToolRegistry.getInstance(project), project)
            val envMap = mutableMapOf<String, String>()
            config.configureLoginCommandEnvironment(envMap, project.basePath)
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
    private fun buildCommandWithEnvironment(command: String, envVars: Map<String, String>): String =
        AuthCommandBuilder.buildCommandWithEnvironment(command, envVars)
}

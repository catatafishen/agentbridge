package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.services.CopilotService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Encapsulates all authentication login logic for Copilot CLI and GitHub CLI.
 * Extracted from AgenticCopilotToolWindowContent to keep the tool window lean.
 */
internal class AuthLoginService(private val project: Project) {

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

    /** Returns null if Copilot CLI is installed and authenticated, or an error description. */
    fun copilotSetupDiagnostics(): String? = try {
        CopilotService.getInstance(project).getClient().listModels()
        // listModels / session/new may succeed without auth; check sticky flag
        val pending = pendingAuthError
        if (pending != null) pending else null
    } catch (e: Exception) {
        e.message ?: "Failed to connect to Copilot CLI"
    }

    /** Returns null if GH CLI is installed and authenticated, or an error description. */
    internal fun ghSetupDiagnostics(billing: BillingManager): String? {
        val ghCli = billing.findGhCli()
            ?: return "GitHub CLI (gh) is not installed — it is used to display billing and usage information."
        return if (!billing.isGhAuthenticated(ghCli))
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

    // ── Logout ─────────────────────────────────────────────────────────────

    /**
     * Signs out of Copilot by stopping the ACP process and running `copilot auth logout`.
     * Must be called on a background thread. Returns null on success or an error message.
     */
    fun logout(): String? {
        // Resolve the copilot CLI path before stopping (stop() clears the client)
        val copilotPath: String? = try {
            CopilotService.getInstance(project).getClient().copilotCliPath
        } catch (_: Exception) {
            null
        }

        try {
            // Stop the running ACP subprocess so its cached token is discarded
            CopilotService.getInstance(project).stop()
        } catch (e: Exception) {
            LOG.error("Failed to stop ACP process during logout", e)
            return e.message ?: "Logout failed"
        }

        // Best-effort CLI logout using the resolved binary (not just "copilot" from PATH)
        if (copilotPath != null) {
            try {
                val pb = ProcessBuilder(copilotPath, "auth", "logout")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    LOG.warn("copilot auth logout exited with code $exitCode: $output")
                } else {
                    LOG.info("Copilot auth logout succeeded")
                }
            } catch (e: Exception) {
                LOG.warn("copilot auth logout command failed (non-fatal)", e)
            }
        }

        // Mark auth error so the banner shows after logout
        pendingAuthError = "Signed out of Copilot"
        return null
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
            val authMethod = CopilotService.getInstance(project).getClient().authMethod
            if (authMethod?.command != null) {
                val args = authMethod.args?.joinToString(" ") ?: ""
                command = "${authMethod.command} $args".trim()
            }
        } catch (_: Exception) { /* best-effort */
        }
        return command.split(" ").filter { it.isNotEmpty() }
    }

    /**
     * Attempts to run the auth command via [ProcessBuilder], capturing stdout line-by-line
     * to extract the device code and verification URL.
     *
     * @param onDeviceCode  called on EDT when a device code + URL are parsed from stdout
     * @param onAuthComplete called on EDT when the process exits successfully (auth done)
     * @param onFallback     called on EDT if we cannot parse or the process fails — caller
     *                       should open the embedded terminal as a fallback
     * @return the spawned [Process], or null if it could not be started.  Callers should
     *         [Process.destroy] it when no longer needed (e.g. banner dismissed).
     */
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
            process = pb.start()
        } catch (e: Exception) {
            LOG.warn("Inline auth: could not start process, falling back to terminal", e)
            onFallback()
            return null
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
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
                            SwingUtilities.invokeLater { onDeviceCode(info) }
                        }
                    }
                }

                val exitCode = process.waitFor()
                SwingUtilities.invokeLater {
                    if (exitCode == 0) {
                        onAuthComplete()
                    } else if (!foundCode) {
                        LOG.info("Inline auth: process exited with $exitCode, no device code found — falling back")
                        onFallback()
                    }
                    // If we did show a code but exit != 0, user probably cancelled — do nothing
                }
            } catch (e: Exception) {
                if (!process.isAlive) return@executeOnPooledThread // killed intentionally
                LOG.warn("Inline auth: reader failed, falling back", e)
                SwingUtilities.invokeLater { onFallback() }
            }
        }
        return process
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

    /**
     * Opens a "Copilot Sign In" terminal tab and runs the auth command.
     * Used as a fallback when inline auth cannot parse the device code.
     */
    fun startCopilotLogin() {
        val resolvedCommand = resolveAuthCommand().joinToString(" ")
        runAuthInEmbeddedTerminal(project, resolvedCommand, "Copilot Sign In") {
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
                launchExternalTerminal(command)
            } catch (e: Exception) {
                LOG.warn("Could not open external terminal for Copilot auth", e)
                SwingUtilities.invokeLater {
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
                launchExternalTerminal("gh auth login")
            } catch (e: Exception) {
                LOG.warn("Could not open external terminal for GitHub auth", e)
                SwingUtilities.invokeLater {
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

    private fun launchExternalTerminal(command: String) {
        val os = System.getProperty(OS_NAME_PROPERTY).lowercase()
        when {
            os.contains("win") ->
                ProcessBuilder("cmd", "/c", "start", "cmd", "/k", command).start()

            os.contains("mac") ->
                ProcessBuilder(
                    "osascript", "-e",
                    "tell application \"Terminal\" to do script \"$command\"",
                ).start()

            else ->
                ProcessBuilder(
                    "sh", "-c",
                    "x-terminal-emulator -e '$command' || " +
                        "gnome-terminal -- $command || " +
                        "konsole -e $command || " +
                        "xterm -e $command",
                ).start()
        }
    }
}

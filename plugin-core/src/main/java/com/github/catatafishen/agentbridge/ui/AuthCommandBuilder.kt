package com.github.catatafishen.agentbridge.ui

/**
 * Pure parsing and command-building logic for auth flows.
 * Extracted from [AuthLoginService] to enable unit testing without Project or UI dependencies.
 */
object AuthCommandBuilder {

    /** Matches device codes like ABCD-1234, AB12-CD34, etc. */
    private val CODE_PATTERN = Regex("\\b[A-Z0-9]{4,8}-[A-Z0-9]{4,8}\\b")

    /** Matches verification URLs (GitHub device flow endpoints). */
    private val URL_PATTERN = Regex("https?://[\\w.-]+(?:/[\\w./-]*)?/device(?:/[\\w./-]*)?")

    /**
     * Returns `true` when [message] indicates a Copilot CLI authentication failure.
     */
    fun isAuthenticationError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("auth") ||
            lower.contains("copilot cli") ||
            lower.contains("authenticated")
    }

    /**
     * Result of parsing a single CLI output line for device-flow credentials.
     */
    data class ParseResult(val code: String?, val url: String?)

    /**
     * Tries to extract a device code or verification URL from a single line of CLI output.
     * Returns any newly found values; previous partial results are passed in so the caller
     * can accumulate across lines (code and URL may appear on separate lines).
     *
     * Patterns are intentionally broad so minor CLI format changes don't break parsing.
     */
    fun parseDeviceCode(line: String, existingCode: String?, existingUrl: String?): ParseResult {
        // Device codes: 4-8 uppercase-alphanumeric groups separated by a hyphen
        val codeMatch = CODE_PATTERN.find(line)
        val code = codeMatch?.value ?: existingCode

        // Verification URLs: any https URL containing "login/device" or "/device" path
        val urlMatch = URL_PATTERN.find(line)
        val url = urlMatch?.value ?: existingUrl

        return ParseResult(code, url)
    }

    /**
     * Builds a shell command that exports environment variables before running the main command.
     * Handles both Unix-like (`export K='V'; cmd`) and Windows (`set K=V && cmd`) shells.
     */
    fun buildCommandWithEnvironment(command: String, envVars: Map<String, String>): String {
        if (envVars.isEmpty()) return command

        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        return if (isWindows) {
            // Windows cmd: set VAR=value && command
            val exports = envVars.entries.joinToString(" && ") { (key, value) -> "set $key=$value" }
            "$exports && $command"
        } else {
            // Unix shells: export VAR=value; command
            // Wrap value in single quotes; escape embedded single quotes using the '\'' shell idiom
            val exports = envVars.entries.joinToString("; ") { (key, value) ->
                "export $key='${value.replace("'", "'\\''")}'"
            }
            "$exports; $command"
        }
    }
}

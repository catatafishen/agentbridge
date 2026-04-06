package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

private val LOG = Logger.getInstance("AuthTerminalHelper")

/**
 * Opens a named tab in IntelliJ's embedded Terminal tool window and runs [command] once the
 * shell is ready.  Falls back to [onTerminalUnavailable] (called on the EDT) when the terminal
 * plugin class is not on the classpath.
 *
 * Readiness is determined by polling `getProcessTtyConnector()` (non-null == shell connected)
 * rather than a fixed sleep, with a 5-second timeout.
 */
fun runAuthInEmbeddedTerminal(
    project: Project,
    command: String,
    tabName: String,
    onTerminalUnavailable: () -> Unit,
) {
    runAuthInEmbeddedTerminal(project, command, emptyMap(), tabName, onTerminalUnavailable)
}

/**
 * Opens a named tab in IntelliJ's embedded Terminal tool window and runs [command] once the
 * shell is ready, with additional environment variables exported first.
 */
fun runAuthInEmbeddedTerminal(
    project: Project,
    command: String,
    envVars: Map<String, String>,
    tabName: String,
    onTerminalUnavailable: () -> Unit,
) {
    // Widget creation must happen on the EDT; polling and command execution on a pooled thread.
    ApplicationManager.getApplication().invokeLater {
        try {
            val terminalViewClass = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            val terminalView = terminalViewClass
                .getMethod("getInstance", Project::class.java)
                .invoke(null, project)
                ?: throw IllegalStateException("TerminalView.getInstance returned null")

            val widget = terminalViewClass.getMethod(
                "createLocalShellWidget",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(terminalView, project.basePath, tabName, /* requestFocus */ true)
                ?: throw IllegalStateException("createLocalShellWidget returned null")

            // Poll for shell readiness on a background thread so we don't block EDT.
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val getTty = widget.javaClass.getMethod("getProcessTtyConnector")
                    val deadline = System.currentTimeMillis() + 5_000L
                    while (System.currentTimeMillis() < deadline) {
                        if (getTty.invoke(widget) != null) break
                        Thread.sleep(100)
                    }
                    val fullCommand = buildCommandWithEnvironment(command, envVars)
                    widget.javaClass.getMethod("executeCommand", String::class.java)
                        .invoke(widget, fullCommand)
                } catch (e: Exception) {
                    LOG.warn("Failed to execute auth command in terminal widget", e)
                    ApplicationManager.getApplication().invokeLater { onTerminalUnavailable() }
                }
            }
        } catch (_: ClassNotFoundException) {
            LOG.info("Terminal plugin not available, falling back to external terminal")
            onTerminalUnavailable()
        } catch (e: Exception) {
            LOG.warn("Failed to open auth terminal tab", e)
            onTerminalUnavailable()
        }
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

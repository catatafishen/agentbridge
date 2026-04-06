package com.github.catatafishen.agentbridge.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Warning banner shown when the project has no git repository or no git remote configured.
 * Warns users that the agent can make destructive edits and version control is important
 * for safe rollbacks. Dismissible per-project via "Don't show again" action.
 */
class GitWarningBanner(private val project: Project) : InlineBanner("", EditorNotificationPanel.Status.Error) {

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            g2.color = statusBorderColor(status)
            g2.fillRect(0, 0, width, 1)
            g2.fillRect(0, height - 1, width, 1)
        } finally {
            g2.dispose()
        }
        paintChildren(g)
    }

    private companion object {
        const val KEY_DISMISSED = "copilot.gitWarningDismissed"
    }

    init {
        isVisible = false
        showCloseButton(true)
        setCloseAction {
            isVisible = false
        }
        addAction("Don't show again") {
            PropertiesComponent.getInstance(project).setValue(KEY_DISMISSED, true)
            close()
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val warning = checkGitStatus()
            if (warning != null) {
                ApplicationManager.getApplication().invokeLater {
                    setMessage(
                        "No version control detected. $warning " +
                            "The agent can make destructive edits \u2014 having git with a remote " +
                            "is important for safe rollbacks."
                    )
                    isVisible = true
                }
            }
        }
    }

    private fun checkGitStatus(): String? {
        if (PropertiesComponent.getInstance(project).getBoolean(KEY_DISMISSED, false)) {
            return null
        }
        val basePath = project.basePath ?: return "No project directory found."
        val dir = java.io.File(basePath)

        if (!isGitInstalled(dir)) {
            return "Git is not installed or not found in PATH."
        }
        if (!isGitRepo(dir)) {
            return "This project is not a git repository."
        }
        if (!hasGitRemote(dir)) {
            return "No git remote is configured \u2014 local commits alone may not be enough for recovery."
        }
        return null
    }

    private fun isGitInstalled(dir: java.io.File): Boolean {
        return try {
            val p = ProcessBuilder("git", "--version")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isGitRepo(dir: java.io.File): Boolean {
        return try {
            val p = ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8).trim()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && output == "true"
        } catch (_: Exception) {
            false
        }
    }

    private fun hasGitRemote(dir: java.io.File): Boolean {
        return try {
            val p = ProcessBuilder("git", "remote")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8).trim()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && output.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}

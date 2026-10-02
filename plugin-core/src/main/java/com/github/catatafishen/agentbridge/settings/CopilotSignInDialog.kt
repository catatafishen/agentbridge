package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.client.koog.CopilotAuth
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Action
import javax.swing.JComponent

/**
 * Shows the GitHub device code while the sign-in waits for the user to authorize it in the browser.
 *
 * The code has to stay in front of the user: GitHub's page asks for it, and the browser window usually
 * covers the IDE. So it is shown large, copied to the clipboard, and kept on screen until the sign-in
 * finishes or is cancelled. The wait runs on a background thread; the dialog closes itself on success.
 *
 * @param waitForToken blocks until GitHub issues the token; it must return promptly once `isCancelled`
 *   reports true. Runs on a pooled thread.
 * @param onToken stores the token; runs on that same pooled thread.
 */
class CopilotSignInDialog(
    project: Project?,
    private val code: CopilotAuth.DeviceCode,
    private val waitForToken: (isCancelled: () -> Boolean) -> String,
    private val onToken: (String) -> Unit,
) : DialogWrapper(project, true) {

    private val cancelled = AtomicBoolean(false)
    private val status = JBLabel("Waiting for you to authorize on GitHub…")

    /** Set when the wait ended in an error, so the dialog can show it and offer Close instead of Cancel. */
    @Volatile
    var failure: String? = null
        private set

    @Volatile
    var succeeded: Boolean = false
        private set

    init {
        title = "Sign in to GitHub Copilot"
        init()
        copyCode()
        BrowserUtil.browse(code.verificationUri)
        ApplicationManager.getApplication().executeOnPooledThread { awaitAuthorization() }
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("Authorize AgentBridge on GitHub in three steps:")
        }
        row {
            label("1. Your browser should have opened ${code.verificationUri}.")
            button("Open it again") { BrowserUtil.browse(code.verificationUri) }
        }
        row {
            label("2. Enter this code when GitHub asks for it:")
        }
        row {
            val codeLabel = JBLabel(code.userCode).apply {
                font = font.deriveFont(Font.BOLD, font.size * 2.4f)
                border = JBUI.Borders.empty(6, 16)
                foreground = JBColor.foreground()
            }
            cell(codeLabel)
            button("Copy code") { copyCode() }
        }
        row {
            label("3. Click Authorize on GitHub. This window closes by itself when it is done.")
        }
        row {
            status.foreground = UIUtil.getContextHelpForeground()
            cell(status)
        }
    }

    /** A single button: Cancel while waiting, Close once the wait has failed. */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun doCancelAction() {
        cancelled.set(true)
        super.doCancelAction()
    }

    private fun copyCode() {
        CopyPasteManager.getInstance().setContents(StringSelection(code.userCode))
    }

    private fun awaitAuthorization() {
        try {
            onToken(waitForToken { cancelled.get() })
            succeeded = true
            runOnEdt { close(OK_EXIT_CODE) }
        } catch (e: CopilotAuth.AuthException) {
            // A cancel by the user is not a failure worth showing.
            if (!cancelled.get()) fail(e.message ?: "Sign-in failed")
        } catch (e: IOException) {
            fail("Could not reach GitHub: ${e.message}")
        }
    }

    private fun fail(message: String) {
        failure = message
        runOnEdt {
            status.text = "<html>$message</html>"
            status.foreground = JBColor.RED
            setCancelButtonText("Close")
        }
    }

    /** `ModalityState.any()` because this dialog is modal and the default would wait for it to close. */
    private fun runOnEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
}

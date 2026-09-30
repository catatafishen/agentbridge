package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AppIcon
import com.intellij.ui.SystemNotifications
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.util.concurrent.ConcurrentHashMap

/**
 * OS-level alerting for pending agent permission requests, so an approval waiting
 * on the user is visible even when the IDE window is not active.
 *
 * Deliberately button-less: the chat card is the single answering surface (Deny /
 * Allow Once / Allow Session / Allow Always). OS notifications don't support action
 * buttons anyway, and an additional IDE balloon with response actions proved
 * redundant — clicking it only raised the frame, after which the user still had to
 * find the chat. Instead, when the IDE frame gains focus while a request is
 * pending, the AgentBridge tool window is activated automatically (once per
 * request), so the OS notification's default click-through (raise the IDE) lands
 * the user directly on the approval card.
 *
 * Alerts fired per request id: a system notification via [SystemNotifications] and
 * taskbar attention via [AppIcon.requestAttention], matching the existing
 * notify-if-unfocused pattern (`AgentEditSession.notifyReviewPending`). When the
 * request resolves by any path (chat card, web panel, timeout), [expire] forgets
 * the id and detaches the focus hook so it stops re-opening the tool window.
 */
object PermissionRequestNotifier {

    private val LOG = Logger.getInstance(PermissionRequestNotifier::class.java)

    /**
     * A pending request: the frame-level focus hook that opens the AgentBridge tool
     * window when the user returns to the IDE. Auto-activation fires at most once
     * per request so a user who deliberately focuses the editor isn't dragged back
     * to the chat on every window switch; detaching the listener on first fire
     * (and on [expire]) keeps the AWT side clean.
     */
    private class PendingHook(val project: Project) : WindowFocusListener {
        @Volatile
        var fired = false

        @Volatile
        private var watchedWindow: java.awt.Window? = null

        override fun windowGainedFocus(e: WindowEvent) {
            if (fired) return
            fired = true
            detach()
            try {
                ToolWindowManager.getInstance(project)
                    .getToolWindow("AgentBridge")?.activate(null, true)
            } catch (t: Throwable) {
                LOG.warn("Failed to activate AgentBridge tool window for pending permission request", t)
            }
        }

        override fun windowLostFocus(e: WindowEvent) {}

        /** Registers on the given IDE frame; replaces any previous registration. EDT. */
        fun watch(window: java.awt.Window?) {
            detach()
            watchedWindow = window
            window?.addWindowFocusListener(this)
        }

        /** Removes this listener from the watched frame. Safe to call repeatedly, from any thread. */
        fun detach() {
            val window = watchedWindow ?: return
            watchedWindow = null
            window.removeWindowFocusListener(this)
        }
    }

    private val pending = ConcurrentHashMap<String, PendingHook>()

    /**
     * Fires the alerts for a pending permission request. No-op when the IDE frame
     * is active (the chat card is already visible) or when this request is already
     * pending (e.g. web re-render). Must run on the EDT.
     *
     * @param reqId           the permission request id the chat panel uses for responses
     * @param agentName       display name of the agent asking (notification title context)
     * @param toolDisplayName what the agent wants to do (e.g. "Approve edit: Foo.java")
     */
    fun notify(
        project: Project,
        reqId: String,
        agentName: String,
        toolDisplayName: String,
    ) {
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
        if (frame != null && frame.isFocused) return
        if (pending.containsKey(reqId)) return

        val hook = PendingHook(project)
        pending[reqId] = hook
        hook.watch(frame)

        val title = "$agentName needs your approval"
        SystemNotifications.getInstance()
            .notify("AgentBridge Notifications", title, stripHtml(toolDisplayName))
        AppIcon.getInstance().requestAttention(project, true)
    }

    /** Forgets the request and detaches its focus hook. Safe for unknown ids. Any thread. */
    fun expire(reqId: String) {
        pending.remove(reqId)?.detach()
    }

    private fun stripHtml(s: String): String = s
        .replace("<br>", " ")
        .replace(Regex("<[^>]*>"), "")
        .trim()
}

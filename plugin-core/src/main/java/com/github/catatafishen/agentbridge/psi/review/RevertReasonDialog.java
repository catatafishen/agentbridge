package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;

/**
 * Modal dialog asking the user to confirm a revert and (optionally) provide a reason
 * that becomes part of the structured nudge sent to the agent.
 *
 * <p>When called while a review gate is active (a git tool blocked on
 * {@link AgentEditSession#awaitReviewCompletion}), the dialog exposes a third action —
 * <b>Continue reviewing</b> — so the user can reject several files in the same review
 * pass without the gated tool short-circuiting on the first rejection. The default
 * action in that mode becomes <b>Continue reviewing</b>; <b>Send to agent now</b>
 * remains available as the explicit "I'm done, abort the gate" choice.</p>
 *
 * <p>Outside a gate the dialog has only <b>Revert</b> (which fires the nudge into the
 * normal pendingNudge path) and <b>Cancel</b>.</p>
 */
public final class RevertReasonDialog extends DialogWrapper {

    /** What the user chose to do with the revert. */
    public enum Result {
        /** Cancel — no revert happens. */
        CANCEL,
        /** Revert and send the nudge into the normal pending-nudge channel. */
        REVERT,
        /** Revert, queue the nudge, but keep the gate blocking. */
        CONTINUE_REVIEWING,
        /** Revert and short-circuit the gate immediately. */
        SEND_NOW
    }

    private final VirtualFile file;
    private final String relativePath;
    private final boolean gateActive;
    private JBTextArea reasonArea;
    private Result result = Result.CANCEL;

    public RevertReasonDialog(@NotNull Project project,
                              @NotNull VirtualFile file,
                              @NotNull String relativePath) {
        this(project, file, relativePath, false);
    }

    /**
     * @param gateActive when {@code true}, exposes the "Continue reviewing" option and
     *                   makes it the default action.
     */
    public RevertReasonDialog(@NotNull Project project,
                              @NotNull VirtualFile file,
                              @NotNull String relativePath,
                              boolean gateActive) {
        super(project, false);
        this.file = file;
        this.relativePath = relativePath;
        this.gateActive = gateActive;
        setTitle("Revert Agent Edit");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(8));

        JBLabel header = new JBLabel(
            "<html>Revert <b>" + escapeHtml(relativePath) + "</b> to its pre-edit state?</html>");
        panel.add(header);
        panel.add(Box.createVerticalStrut(8));

        String hintHtml = gateActive
            ? "<html><i>A git operation is currently waiting for review.<br>"
                + "<b>Continue reviewing</b> queues this revert and keeps the gate blocking so you can reject more files.<br>"
                + "<b>Send to agent now</b> aborts the gate immediately with all queued reverts.</i></html>"
            : "<html><i>Optional: explain why. The reason and a unified diff are sent to the agent as a nudge so it can try a different approach.</i></html>";
        JBLabel hint = new JBLabel(hintHtml);
        panel.add(hint);
        panel.add(Box.createVerticalStrut(4));

        reasonArea = new JBTextArea(4, 60);
        reasonArea.setLineWrap(true);
        reasonArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(reasonArea);
        scroll.setBorder(BorderFactory.createLineBorder(JBUI.CurrentTheme.Focus.focusColor()));

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(scroll, BorderLayout.CENTER);
        wrap.setPreferredSize(new Dimension(500, 120));
        panel.add(wrap);

        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        if (gateActive) {
            Action continueAction = new RevertAction("Continue reviewing", Result.CONTINUE_REVIEWING);
            continueAction.putValue(DEFAULT_ACTION, Boolean.TRUE);
            Action sendNowAction = new RevertAction("Send to agent now", Result.SEND_NOW);
            return new Action[] { continueAction, sendNowAction, getCancelAction() };
        }
        Action revertAction = new RevertAction("Revert", Result.REVERT);
        revertAction.putValue(DEFAULT_ACTION, Boolean.TRUE);
        return new Action[] { revertAction, getCancelAction() };
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return reasonArea;
    }

    /** What the user chose. */
    public @NotNull Result getResult() {
        return result;
    }

    /** Trimmed reason text, or {@code null} if the user left it empty. */
    public @Nullable String getReason() {
        if (reasonArea == null) return null;
        String text = reasonArea.getText();
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public @NotNull VirtualFile getFile() {
        return file;
    }

    private final class RevertAction extends AbstractAction {
        private final Result chosen;

        RevertAction(@NotNull String name, @NotNull Result chosen) {
            super(name);
            this.chosen = chosen;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            result = chosen;
            close(OK_EXIT_CODE);
        }
    }

    private static @NotNull String escapeHtml(@NotNull String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

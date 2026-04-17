package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * Modal dialog asking the user to confirm a revert and (optionally) provide a reason
 * that will be forwarded to the agent as a nudge. The reason is free-form text; an
 * empty value skips the nudge and performs a silent revert.
 */
public final class RevertReasonDialog extends DialogWrapper {

    private final VirtualFile file;
    private final String relativePath;
    private JBTextArea reasonArea;

    public RevertReasonDialog(@NotNull Project project,
                              @NotNull VirtualFile file,
                              @NotNull String relativePath) {
        super(project, false);
        this.file = file;
        this.relativePath = relativePath;
        setTitle("Revert Agent Edit");
        setOKButtonText("Revert");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(8));

        JBLabel header = new JBLabel(
            "<html>Revert <b>" + escapeHtml(relativePath) + "</b> to its pre-session state?</html>");
        panel.add(header);
        panel.add(Box.createVerticalStrut(8));

        JBLabel hint = new JBLabel(
            "<html><i>Optional: explain why. This is sent to the agent as a nudge so it can try a different approach.</i></html>");
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
    public @Nullable JComponent getPreferredFocusedComponent() {
        return reasonArea;
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

    private static @NotNull String escapeHtml(@NotNull String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

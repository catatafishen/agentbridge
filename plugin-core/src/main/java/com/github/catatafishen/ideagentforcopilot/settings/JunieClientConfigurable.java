package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient;
import com.github.catatafishen.ideagentforcopilot.agent.junie.JunieKeyStore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class JunieClientConfigurable implements Configurable {

    private static final String AGENT_ID = "junie";

    @SuppressWarnings("unused")
    public JunieClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Junie";
    }

    private JBLabel statusLabel;
    private JBTextField binaryPathField;
    private JBPasswordField authTokenField;
    private JPanel panel;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the junie binary. Leave empty to find it on PATH.");

        authTokenField = new JBPasswordField();
        authTokenField.getEmptyText().setText("Optional: enter token to bypass CLI auth");
        authTokenField.setToolTipText("Generate a token at https://junie.jetbrains.com/cli. Leave empty to use CLI credentials.");

        HyperlinkLabel authLink = new HyperlinkLabel("Generate an auth token at junie.jetbrains.com/cli");
        authLink.setHyperlinkTarget("https://junie.jetbrains.com/cli");

        JBLabel authNote = new JBLabel(
            "<html><b>Authentication:</b> You can either:<br>" +
                "1. Enter a token above (recommended for plugin use), OR<br>" +
                "2. Run <code>junie</code> in a terminal and use <code>/account</code> to log in with JetBrains Account</html>");
        authNote.setForeground(UIUtil.getContextHelpForeground());
        authNote.setFont(JBUI.Fonts.smallFont());

        JBLabel toolWarning = new JBLabel(
            "<html><b>⚠ Tool Selection Limitation:</b> Junie ignores <code>excludedTools</code> and does not send "
                + "<code>request_permission</code> for any tools. Built-in tools (Edit, View, Bash) may bypass "
                + "IntelliJ's editor buffer. The plugin uses prompt engineering to encourage MCP tool usage, but "
                + "compliance depends on the LLM. See <code>docs/JUNIE-TOOL-WORKAROUND.md</code> for details.</html>");
        toolWarning.setForeground(new Color(184, 134, 11));
        toolWarning.setFont(JBUI.Fonts.smallFont());

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusLabel)
            .addSeparator(8)
            .addLabeledComponent("Auth Token:", authTokenField)
            .addTooltip("Generate token at https://junie.jetbrains.com/cli (optional)")
            .addComponent(authLink, 2)
            .addComponent(authNote, 2)
            .addSeparator(8)
            .addComponent(toolWarning, 2)
            .addSeparator(8)
            .addLabeledComponent("Junie binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));
        return panel;
    }

    @Override
    public boolean isModified() {
        if (binaryPathField == null || authTokenField == null) return false;
        String storedPath = nullToEmpty(AcpClient.loadCustomBinaryPath(AGENT_ID));
        String storedToken = nullToEmpty(JunieKeyStore.getAuthToken());
        return !binaryPathField.getText().trim().equals(storedPath)
            || !new String(authTokenField.getPassword()).equals(storedToken);
    }

    @Override
    public void apply() {
        if (binaryPathField == null || authTokenField == null) return;
        AcpClient.saveCustomBinaryPath(AGENT_ID, binaryPathField.getText().trim());

        String token = new String(authTokenField.getPassword()).trim();
        if (token.isEmpty()) {
            JunieKeyStore.setAuthToken(null);
        } else {
            JunieKeyStore.setAuthToken(token);
        }
    }

    @Override
    public void reset() {
        if (binaryPathField == null || authTokenField == null) return;
        refreshStatusAsync();
        binaryPathField.setText(nullToEmpty(AcpClient.loadCustomBinaryPath(AGENT_ID)));
        authTokenField.setText(nullToEmpty(JunieKeyStore.getAuthToken()));
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        binaryPathField = null;
        authTokenField = null;
        panel = null;
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String customPath = AcpClient.loadCustomBinaryPath(AGENT_ID);
            String binary = customPath != null ? customPath : "junie";
            String version = BinaryDetector.detectBinaryVersion(binary, new String[0]);
            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (version != null) {
                    statusLabel.setText("✓ Junie CLI found — " + version);
                    statusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    statusLabel.setText("Junie CLI not found on PATH — install from junie.jetbrains.com");
                    statusLabel.setForeground(Color.RED);
                }
            });
        });
    }

    @NotNull
    private static String nullToEmpty(@Nullable String s) {
        return s != null ? s : "";
    }
}

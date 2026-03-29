package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient;
import com.github.catatafishen.ideagentforcopilot.agent.junie.JunieKeyStore;
import com.github.catatafishen.ideagentforcopilot.ui.ThemeColor;
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
    private @Nullable ThemeColorComboBox bubbleColorCombo;
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

        bubbleColorCombo = new ThemeColorComboBox();

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
            .addLabeledComponent("Bubble color:", bubbleColorCombo)
            .addTooltip("Choose a theme-aware accent color for message bubbles when using Junie.")
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
        if (!binaryPathField.getText().trim().equals(storedPath)) return true;
        if (!new String(authTokenField.getPassword()).equals(storedToken)) return true;
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            String key = tc != null ? tc.name() : null;
            if (!java.util.Objects.equals(key, AcpClient.loadAgentBubbleColorKey(AGENT_ID))) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        if (binaryPathField == null || authTokenField == null) return;

        String oldToken = JunieKeyStore.getAuthToken();
        String newToken = new String(authTokenField.getPassword()).trim();
        boolean tokenChanged = !nullToEmpty(oldToken).equals(newToken);

        AcpClient.saveCustomBinaryPath(AGENT_ID, binaryPathField.getText().trim());

        if (newToken.isEmpty()) {
            JunieKeyStore.setAuthToken(null);
        } else {
            JunieKeyStore.setAuthToken(newToken);
        }

        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            AcpClient.saveAgentBubbleColorKey(AGENT_ID, tc != null ? tc.name() : null);
        }

        // Restart Junie if auth token changed (so new token is picked up by the process)
        if (tokenChanged) {
            restartJunieProcesses();
        }
    }

    /**
     * Stops all running Junie processes across all projects so they restart with the new auth token.
     */
    private void restartJunieProcesses() {
        ApplicationManager.getApplication().invokeLater(() -> {
            com.intellij.openapi.project.ProjectManager pm = com.intellij.openapi.project.ProjectManager.getInstance();
            for (Project project : pm.getOpenProjects()) {
                com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager manager =
                    com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager.getInstance(project);
                // Only restart if Junie is the active agent
                if (AGENT_ID.equals(manager.getActiveProfileId())) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(JunieClientConfigurable.class)
                        .info("Junie auth token changed — restarting Junie process to pick up new token");
                    manager.restart();
                }
            }
        });
    }

    @Override
    public void reset() {
        if (binaryPathField == null || authTokenField == null) return;
        refreshStatusAsync();
        binaryPathField.setText(nullToEmpty(AcpClient.loadCustomBinaryPath(AGENT_ID)));
        authTokenField.setText(nullToEmpty(JunieKeyStore.getAuthToken()));
        if (bubbleColorCombo != null) {
            bubbleColorCombo.setSelectedThemeColor(ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)));
        }
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        binaryPathField = null;
        authTokenField = null;
        bubbleColorCombo = null;
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

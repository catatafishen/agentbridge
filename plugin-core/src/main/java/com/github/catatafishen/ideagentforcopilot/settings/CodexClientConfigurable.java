package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.agent.codex.CodexAppServerClient;
import com.github.catatafishen.ideagentforcopilot.agent.codex.CodexCredentials;
import com.github.catatafishen.ideagentforcopilot.ui.AuthTerminalHelperKt;
import com.github.catatafishen.ideagentforcopilot.ui.ThemeColor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class CodexClientConfigurable implements Configurable {

    private static final String CHECKING = "Checking…";

    private final Project project;

    private JBLabel binaryStatusLabel;
    private JBLabel authStatusLabel;
    private JBTextField binaryPathField;
    private @Nullable ThemeColorComboBox bubbleColorCombo;
    private JPanel mainPanel;

    public CodexClientConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Codex";
    }

    @Override
    public @NotNull JComponent createComponent() {
        binaryStatusLabel = new JBLabel(CHECKING);
        authStatusLabel = new JBLabel(CHECKING);

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the codex binary. Leave empty to find it on PATH.");

        bubbleColorCombo = new ThemeColorComboBox();

        HyperlinkLabel installLink = new HyperlinkLabel("Install Codex CLI — npmjs.com/@openai/codex");
        installLink.setHyperlinkTarget("https://www.npmjs.com/package/@openai/codex");

        JBLabel installNote = new JBLabel(
            "<html>Install with <code>npm install -g @openai/codex</code>, then run <code>codex login</code>.</html>");
        installNote.setForeground(UIUtil.getContextHelpForeground());
        installNote.setFont(JBUI.Fonts.smallFont());

        JButton signInButton = new JButton("Sign In (codex login)");
        signInButton.addActionListener(e -> openSignInTerminal(false));

        JButton signInDeviceButton = new JButton("Sign In — headless (codex login --device-auth)");
        signInDeviceButton.addActionListener(e -> openSignInTerminal(true));

        JBLabel deviceAuthNote = new JBLabel(
            "<html>Use <i>headless</i> sign-in on remote/SSH machines where a browser cannot open automatically.</html>");
        deviceAuthNote.setForeground(UIUtil.getContextHelpForeground());
        deviceAuthNote.setFont(JBUI.Fonts.smallFont());

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Binary:", binaryStatusLabel)
            .addLabeledComponent("Auth:", authStatusLabel)
            .addComponent(installNote, 2)
            .addComponent(installLink, 2)
            .addSeparator(8)
            .addLabeledComponent("Codex binary path:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Bubble color:", bubbleColorCombo)
            .addTooltip("Choose a theme-aware accent color for message bubbles when using Codex.")
            .addSeparator(8)
            .addComponent(signInButton, 4)
            .addComponent(signInDeviceButton, 2)
            .addComponent(deviceAuthNote, 2)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        if (binaryPathField == null) return false;
        String stored = nullToEmpty(loadCustomBinaryPath());
        if (!binaryPathField.getText().trim().equals(stored)) return true;
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            String key = tc != null ? tc.name() : null;
            if (!java.util.Objects.equals(key, loadBubbleColorKey())) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        if (binaryPathField == null) return;
        saveCustomBinaryPath(binaryPathField.getText().trim());
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            saveBubbleColorKey(tc != null ? tc.name() : null);
        }
    }

    @Override
    public void reset() {
        if (binaryPathField == null) return;
        refreshStatusAsync();
        binaryPathField.setText(nullToEmpty(loadCustomBinaryPath()));
        if (bubbleColorCombo != null) {
            bubbleColorCombo.setSelectedThemeColor(ThemeColor.fromKey(loadBubbleColorKey()));
        }
    }

    @Override
    public void disposeUIResources() {
        binaryStatusLabel = null;
        authStatusLabel = null;
        binaryPathField = null;
        bubbleColorCombo = null;
        mainPanel = null;
    }

    // ── Status refresh ────────────────────────────────────────────────────────

    private void refreshStatusAsync() {
        if (binaryStatusLabel == null) return;
        binaryStatusLabel.setText(CHECKING);
        binaryStatusLabel.setForeground(UIUtil.getLabelForeground());
        authStatusLabel.setText(CHECKING);
        authStatusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String customPath = loadCustomBinaryPath();
            String binary = customPath != null && !customPath.isEmpty() ? customPath : "codex";
            String version = BinaryDetector.detectBinaryVersion(binary, new String[0]);
            CodexCredentials creds = CodexCredentials.read();
            SwingUtilities.invokeLater(() -> applyStatusResults(version, creds));
        });
    }

    private void applyStatusResults(String version, CodexCredentials creds) {
        if (binaryStatusLabel == null) return;
        if (version != null) {
            binaryStatusLabel.setText("✓ Codex CLI found — " + version);
            binaryStatusLabel.setForeground(new Color(0, 128, 0));
        } else {
            binaryStatusLabel.setText("Codex CLI not found on PATH — install with npm install -g @openai/codex");
            binaryStatusLabel.setForeground(Color.RED);
        }

        if (authStatusLabel == null) return;
        if (creds.isLoggedIn()) {
            String name = creds.getDisplayName();
            authStatusLabel.setText("✓ Authenticated" + (name != null ? " — " + name : ""));
            authStatusLabel.setForeground(new Color(0, 128, 0));
        } else {
            authStatusLabel.setText("Not authenticated — run 'codex login' or click Sign In below");
            authStatusLabel.setForeground(Color.RED);
        }
    }

    // ── Sign-in helpers ───────────────────────────────────────────────────────

    /**
     * Opens a terminal tab and runs {@code codex login} (or {@code codex login --device-auth}
     * for headless/remote environments).
     */
    private void openSignInTerminal(boolean deviceAuth) {
        String customPath = loadCustomBinaryPath();
        String binary = customPath != null && !customPath.isEmpty() ? customPath : "codex";
        String cmd = deviceAuth ? binary + " login --device-auth" : binary + " login";
        AuthTerminalHelperKt.runAuthInEmbeddedTerminal(project, cmd, "Codex Sign In",
            () -> {
                openExternalTerminal(cmd);
                return kotlin.Unit.INSTANCE;
            });
    }

    private void openExternalTerminal(String cmd) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("cmd", "/c", "start", "cmd", "/k", cmd).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("osascript", "-e",
                        "tell application \"Terminal\" to do script \"" + cmd + "\"").start();
                } else {
                    new ProcessBuilder("sh", "-c",
                        "x-terminal-emulator -e '" + cmd + "' || gnome-terminal -- bash -c '" + cmd + "' || xterm -e bash -c '" + cmd + "'").start();
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(null,
                        "Could not open a terminal. Run manually: " + cmd,
                        "Codex Sign In", JOptionPane.INFORMATION_MESSAGE));
            }
        });
    }

    // ── Storage helpers (delegate to AcpClient persistence) ──────────────────

    private String loadCustomBinaryPath() {
        return com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient
            .loadCustomBinaryPath(CodexAppServerClient.PROFILE_ID);
    }

    private void saveCustomBinaryPath(String path) {
        com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient
            .saveCustomBinaryPath(CodexAppServerClient.PROFILE_ID, path);
    }

    private @Nullable String loadBubbleColorKey() {
        return com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient
            .loadAgentBubbleColorKey(CodexAppServerClient.PROFILE_ID);
    }

    private void saveBubbleColorKey(@Nullable String colorKey) {
        com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient
            .saveAgentBubbleColorKey(CodexAppServerClient.PROFILE_ID, colorKey);
    }

    @NotNull
    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}

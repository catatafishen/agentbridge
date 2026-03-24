package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ChatWebServer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Settings page for the Chat Web Server.
 * Located under Settings → Tools → AgentBridge → Web Access.
 */
public final class ChatWebServerConfigurable implements Configurable {

    private final Project project;
    private JBCheckBox enabledCheckbox;
    private JSpinner portSpinner;
    private JButton startStopButton;
    private JLabel urlLabel;
    private JPanel mainPanel;

    public ChatWebServerConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Web Access";
    }

    @Override
    public @NotNull JComponent createComponent() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);

        enabledCheckbox = new JBCheckBox(
            "Start web server automatically when project opens",
            settings.isEnabled());

        portSpinner = new JSpinner(new SpinnerNumberModel(settings.getPort(), 1024, 65535, 1));

        startStopButton = new JButton(getStartStopLabel());
        startStopButton.addActionListener(e -> toggleServer());

        urlLabel = new JBLabel("");
        urlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateUrlLabel();

        JButton copyUrlButton = new JButton("Copy URL");
        copyUrlButton.addActionListener(e -> {
            String url = getServerUrl();
            if (!url.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(url), null);
                String orig = copyUrlButton.getText();
                copyUrlButton.setText("Copied!");
                Timer t = new Timer(2000, ev -> copyUrlButton.setText(orig));
                t.setRepeats(false);
                t.start();
            }
        });

        JPanel urlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        urlRow.add(urlLabel);
        urlRow.add(copyUrlButton);

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel("<html>Serve the chat panel as a local web app accessible from "
                + "any device on the same network (phone, tablet, etc.).<br>"
                + "Supports prompt sending, nudging, quick replies, and PWA push notifications.</html>"))
            .addSeparator()
            .addComponent(enabledCheckbox)
            .addLabeledComponent("Port:", portSpinner)
            .addSeparator()
            .addComponent(startStopButton)
            .addComponent(urlRow)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        if (enabledCheckbox.isSelected() != settings.isEnabled()) return true;
        return (Integer) portSpinner.getValue() != settings.getPort();
    }

    @Override
    public void apply() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        settings.setEnabled(enabledCheckbox.isSelected());
        settings.setPort((Integer) portSpinner.getValue());
    }

    @Override
    public void reset() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        enabledCheckbox.setSelected(settings.isEnabled());
        portSpinner.setValue(settings.getPort());
        updateUrlLabel();
        if (startStopButton != null) startStopButton.setText(getStartStopLabel());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        enabledCheckbox = null;
        portSpinner = null;
        startStopButton = null;
        urlLabel = null;
    }

    private void toggleServer() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws.isRunning()) {
            ws.stop();
        } else {
            apply(); // save port before starting
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    ws.start();
                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() ->
                        JOptionPane.showMessageDialog(mainPanel,
                            "Failed to start web server: " + e.getMessage(),
                            "Chat Web Server Error", JOptionPane.ERROR_MESSAGE));
                }
                ApplicationManager.getApplication().invokeLater(this::refresh);
            });
            return;
        }
        refresh();
    }

    private void refresh() {
        if (startStopButton != null) startStopButton.setText(getStartStopLabel());
        updateUrlLabel();
    }

    private String getStartStopLabel() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        return ws != null && ws.isRunning() ? "Stop Web Server" : "Start Web Server";
    }

    private void updateUrlLabel() {
        if (urlLabel == null) return;
        String url = getServerUrl();
        if (url.isEmpty()) {
            urlLabel.setText("<html><i style='color:gray'>Server not running</i></html>");
        } else {
            urlLabel.setText("<html><a href='" + url + "'>" + url + "</a></html>");
        }
    }

    private String getServerUrl() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws == null || !ws.isRunning()) return "";
        try {
            String host = java.net.InetAddress.getLocalHost().getHostAddress();
            return "http://" + host + ":" + ws.getPort();
        } catch (Exception e) {
            return "http://localhost:" + ws.getPort();
        }
    }
}

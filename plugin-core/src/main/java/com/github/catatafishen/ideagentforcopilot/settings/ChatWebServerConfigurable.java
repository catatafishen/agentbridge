package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ChatWebServer;
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
    private JRadioButton httpRadio;
    private JRadioButton httpsRadio;
    private JButton startStopButton;
    private JLabel urlLabel;
    private QrCodePanel appQrPanel;
    private QrCodePanel certQrPanel;
    private JPanel certQrRow;
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

        httpRadio = new JRadioButton("HTTP");
        httpsRadio = new JRadioButton("HTTPS (generates self-signed CA cert for device trust)");
        ButtonGroup protocolGroup = new ButtonGroup();
        protocolGroup.add(httpRadio);
        protocolGroup.add(httpsRadio);
        if (settings.isHttpsEnabled()) httpsRadio.setSelected(true);
        else httpRadio.setSelected(true);

        startStopButton = new JButton(getStartStopLabel());
        startStopButton.addActionListener(e -> toggleServer());

        urlLabel = new JBLabel("");
        urlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        appQrPanel = new QrCodePanel();
        certQrPanel = new QrCodePanel();

        JButton copyUrlButton = new JButton("Copy URL");
        copyUrlButton.addActionListener(e -> {
            String url = getServerUrl();
            if (!url.isEmpty()) copyToClipboard(url, copyUrlButton);
        });

        JPanel urlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        urlRow.add(new JBLabel("URL:"));
        urlRow.add(urlLabel);
        urlRow.add(copyUrlButton);

        JPanel appQrRow = buildQrRow(appQrPanel, "Open on phone");
        certQrRow = buildQrRow(certQrPanel, "Install CA cert");

        JPanel qrSection = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        qrSection.add(appQrRow);
        qrSection.add(certQrRow);

        JPanel protocolPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        protocolPanel.add(httpRadio);
        protocolPanel.add(httpsRadio);

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel("<html>Serve the chat panel as a local web app accessible from "
                + "any device on the same network (phone, tablet, etc.).<br>"
                + "Supports prompt sending, nudging, quick replies, and PWA push notifications.</html>"))
            .addSeparator()
            .addComponent(enabledCheckbox)
            .addLabeledComponent("Port:", portSpinner)
            .addLabeledComponent("Protocol:", protocolPanel)
            .addSeparator()
            .addComponent(startStopButton)
            .addComponent(urlRow)
            .addComponent(qrSection)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        updateUrlAndQr();
        return mainPanel;
    }

    private static JPanel buildQrRow(QrCodePanel qr, String label) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        JBLabel lbl = new JBLabel(label);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        qr.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(lbl);
        col.add(Box.createVerticalStrut(4));
        col.add(qr);
        return col;
    }

    @Override
    public boolean isModified() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        if (enabledCheckbox.isSelected() != settings.isEnabled()) return true;
        if ((Integer) portSpinner.getValue() != settings.getPort()) return true;
        return httpsRadio.isSelected() != settings.isHttpsEnabled();
    }

    @Override
    public void apply() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        settings.setEnabled(enabledCheckbox.isSelected());
        settings.setPort((Integer) portSpinner.getValue());
        settings.setHttpsEnabled(httpsRadio.isSelected());
    }

    @Override
    public void reset() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        enabledCheckbox.setSelected(settings.isEnabled());
        portSpinner.setValue(settings.getPort());
        if (settings.isHttpsEnabled()) httpsRadio.setSelected(true);
        else httpRadio.setSelected(true);
        updateUrlAndQr();
        if (startStopButton != null) startStopButton.setText(getStartStopLabel());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        enabledCheckbox = null;
        portSpinner = null;
        httpRadio = null;
        httpsRadio = null;
        startStopButton = null;
        urlLabel = null;
        appQrPanel = null;
        certQrPanel = null;
        certQrRow = null;
    }

    private void toggleServer() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws.isRunning()) {
            ws.stop();
            refresh();
        } else {
            apply();
            startStopButton.setEnabled(false);
            startStopButton.setText("Starting…");
            new Thread(() -> {
                try {
                    ws.start();
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(mainPanel,
                            "Failed to start web server: " + e.getMessage(),
                            "Chat Web Server Error", JOptionPane.ERROR_MESSAGE);
                        refresh();
                    });
                    return;
                }
                SwingUtilities.invokeLater(this::refresh);
            }, "ChatWebServer-start").start();
        }
    }

    private void refresh() {
        if (startStopButton == null) return;
        startStopButton.setEnabled(true);
        startStopButton.setText(getStartStopLabel());
        updateUrlAndQr();
        if (mainPanel != null) {
            mainPanel.revalidate();
            mainPanel.repaint();
        }
    }

    private String getStartStopLabel() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        return ws != null && ws.isRunning() ? "Stop Web Server" : "Start Web Server";
    }

    private void updateUrlAndQr() {
        if (urlLabel == null) return;
        ChatWebServer ws = ChatWebServer.getInstance(project);
        String url = getServerUrl();
        boolean https = isRunningHttps();

        if (url.isEmpty()) {
            urlLabel.setText("<html><i style='color:gray'>Not running</i></html>");
            if (appQrPanel != null) appQrPanel.setUrl(null);
            if (certQrPanel != null) certQrPanel.setUrl(null);
        } else {
            urlLabel.setText("<html><a href='" + url + "'>" + url + "</a></html>");
            if (appQrPanel != null) appQrPanel.setUrl(url);
            if (certQrPanel != null && https) {
                String host = ChatWebServer.getLanIp();
                String certUrl = "http://" + (host != null ? host : "localhost") + ":" + ws.getHttpCertPort() + "/cert.crt";
                certQrPanel.setUrl(certUrl);
            }
        }

        if (certQrRow != null) certQrRow.setVisible(https);
    }

    private String getServerUrl() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws == null || !ws.isRunning()) return "";
        String protocol = ws.isHttps() ? "https" : "http";
        return buildUrl(protocol, ws.getPort());
    }

    private boolean isRunningHttps() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        return ws != null && ws.isRunning() && ws.isHttps();
    }

    private String buildUrl(String protocol, int port) {
        String lanIp = ChatWebServer.getLanIp();
        String host = lanIp != null ? lanIp : "localhost";
        return protocol + "://" + host + ":" + port;
    }

    private void copyToClipboard(String text, JButton feedbackButton) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(text), null);
        String orig = feedbackButton.getText();
        feedbackButton.setText("Copied!");
        Timer t = new Timer(2000, ev -> feedbackButton.setText(orig));
        t.setRepeats(false);
        t.start();
    }
}

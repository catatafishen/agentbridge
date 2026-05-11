package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public final class ChatToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final Logger LOG = Logger.getInstance(ChatToolWindowFactory.class);

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Log diagnostic info to help identify remote-dev scenarios.
        String prefix = System.getProperty("idea.platform.prefix", "idea");
        boolean jcefSupported = JBCefApp.isSupported();
        boolean isJbc = PlatformApiCompat.isJetBrainsClient();
        boolean isRds = PlatformApiCompat.isRemoteDevServer();
        boolean isRdb = PlatformApiCompat.isRemoteDevBackend();
        LOG.info("AgentBridge tool window: prefix=" + prefix
            + " jcef=" + jcefSupported
            + " isJetBrainsClient=" + isJbc
            + " isRemoteDevServer=" + isRds
            + " isRemoteDevBackend=" + isRdb);

        // JCEF-based chat UI cannot be serialized over the Gateway Rd protocol.
        // isJetBrainsClient() catches the thin-client frontend process;
        // isRemoteDevServer() catches the headless backend process (prefix=RemoteDevServer);
        // isRemoteDevBackend() catches IntelliJ IDEA acting as a Remote Dev host via
        //   "Open in JetBrains Client" — it retains the "idea" prefix and reports jcef=true
        //   but JCEF content still cannot be forwarded to the thin client over the Rd protocol;
        // !JBCefApp.isSupported() catches any other environment where JCEF cannot render.
        if (isJbc || isRds || isRdb || !jcefSupported) {
            Content content = ContentFactory.getInstance().createContent(
                buildThinClientPlaceholder(), "", false
            );
            toolWindow.getContentManager().addContent(content);
        } else {
            ChatToolWindowContent windowContent = new ChatToolWindowContent(project, toolWindow);
            Content content = ContentFactory.getInstance().createContent(
                windowContent.getComponent(), "", false
            );
            toolWindow.getContentManager().addContent(content);
        }
    }

    private static JPanel buildThinClientPlaceholder() {
        JPanel panel = new JPanel(new GridBagLayout());
        JBLabel label = new JBLabel(
            "<html><center>AgentBridge is running on the remote machine.<br>" +
                "The full chat UI is not available in the thin client.</center></html>"
        );
        label.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(label);
        return panel;
    }
}

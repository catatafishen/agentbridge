package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public final class ChatToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // JCEF-based chat UI cannot be serialized over the Gateway Rd protocol.
        // isJetBrainsClient() catches the thin-client frontend process;
        // isRemoteDevServer() catches the headless backend process (where createToolWindowContent
        // actually runs and where JCEF components would fail to render in the thin client).
        if (PlatformApiCompat.isJetBrainsClient() || PlatformApiCompat.isRemoteDevServer()) {
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

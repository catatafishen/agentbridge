package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Returns the plain-text conversation transcript from the native chat panel.
 */
public final class GetChatHtmlTool extends EditorTool {

    public GetChatHtmlTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_chat_html";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Chat Conversation";
    }

    @Override
    public @NotNull String description() {
        return "Get the plain-text conversation transcript from the chat panel. " +
            "Returns all messages visible in the native chat panel. " +
            "Requires the Copilot tool window to be open.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        var panel = com.github.catatafishen.agentbridge.ui.BroadcastChatPanel.getInstance(project);
        if (panel == null) {
            return "Error: Chat panel not found. Is the Copilot tool window open?";
        }
        return panel.getConversationText();
    }
}

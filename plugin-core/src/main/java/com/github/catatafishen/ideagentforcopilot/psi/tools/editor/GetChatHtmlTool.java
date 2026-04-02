package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Gets the path and content of the currently active chat HTML.
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
        return "Get Chat HTML";
    }

    @Override
    public @NotNull String description() {
        return "Get the path and content of the currently active chat HTML";
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
        var panel = com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel.Companion.getInstance(project);
        if (panel == null) {
            return "Error: Chat panel not found. Is the Copilot tool window open?";
        }
        String html = panel.getPageHtml();
        if (html == null) {
            return "Error: Could not retrieve page HTML. Browser may not be ready.";
        }
        return html;
    }
}

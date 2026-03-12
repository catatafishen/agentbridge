package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists, reads, and searches past conversation sessions from the chat history.
 */
@SuppressWarnings("java:S112")
public final class SearchConversationHistoryTool extends EditorTool {

    public SearchConversationHistoryTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override
    public @NotNull String id() {
        return "search_conversation_history";
    }

    @Override
    public @NotNull String displayName() {
        return "Search Conversation History";
    }

    @Override
    public @NotNull String description() {
        return "List, read, and search past conversation sessions from the chat history";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"query", TYPE_STRING, "Text to search for across conversations (case-insensitive)"},
            {"file", TYPE_STRING, "Conversation to read: 'current' for the active session, or an archive timestamp (e.g., '2026-03-04T15-30-00')"},
            {"max_chars", TYPE_INTEGER, "Maximum characters to return (default: 8000)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.searchConversationHistory(args);
    }
}

package com.github.catatafishen.agentbridge.client.nested;

import com.github.catatafishen.agentbridge.bridge.MessageFormatter;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.github.catatafishen.agentbridge.ui.ChatPanelApi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Shows a nested session as a sub-agent entry in the chat: its text and tool calls stream into the
 * entry's section, and the entry ends with the session's status.
 *
 * <p>Only the chat panel is touched, through {@link ChatPanelApi}; the session itself is run by
 * {@link NestedSessionRunner}.
 */
public final class NestedSessionChatView implements NestedSessionRunner.Listener {

    /**
     * Prefix of the id of a nested session's entry. The chat panel recognises its entries by it, which is how it
     * shows them apart from other sub-agents, also after the conversation was stored and loaded again: the entry id
     * is part of what is stored.
     */
    public static final String ENTRY_ID_PREFIX = "nested-";

    private final ChatPanelApi panel;
    private final String entryId = ENTRY_ID_PREFIX + UUID.randomUUID();
    /**
     * Whether any answer text was streamed into the entry. It is read by the tool's thread at the end and written by
     * the thread the agent streams on.
     */
    private volatile boolean streamedText;

    public NestedSessionChatView(@NotNull ChatPanelApi panel) {
        this.panel = panel;
    }

    /**
     * Whether {@code entryId} belongs to a nested session's entry.
     */
    public static boolean isNestedEntryId(@Nullable String entryId) {
        return entryId != null && entryId.startsWith(ENTRY_ID_PREFIX);
    }

    /**
     * Adds the entry. It is running until {@link #finish}: the panel shows its chip as running without being told,
     * and an initial status would make it draw a result at once, which for a session that has produced nothing yet
     * reads as a failure.
     */
    public void start(@NotNull String agentName, @NotNull String description, @NotNull String prompt) {
        panel.addSubAgentEntry(entryId, agentName, description, prompt, new ChatPanelApi.SubAgentInitialState());
    }

    @Override
    public void onText(@NotNull String text) {
        streamedText = true;
        panel.appendSubAgentText(entryId, text);
    }

    @Override
    public void onToolCall(@NotNull SessionUpdate.ToolCall call) {
        if (call.toolCallId().isEmpty()) return;
        SessionUpdate.ToolKind kind = call.kind();
        panel.addSubAgentToolCall(entryId, toolId(call.toolCallId()), call.title(), call.arguments(),
            kind != null ? kind.value() : null);
    }

    @Override
    public void onToolCallUpdate(@NotNull SessionUpdate.ToolCallUpdate update) {
        String status = update.status() == SessionUpdate.ToolCallStatus.COMPLETED
            ? MessageFormatter.ChipStatus.COMPLETE
            : update.status() == SessionUpdate.ToolCallStatus.FAILED
            ? MessageFormatter.ChipStatus.FAILED
            : MessageFormatter.ChipStatus.RUNNING;
        String details = update.error() != null ? update.error() : update.result();
        panel.updateSubAgentToolCall(toolId(update.toolCallId()), status, details, update.description(),
            update.autoDenied(), update.denialReason(), update.arguments());
    }

    /**
     * Ends the entry with the session's outcome.
     */
    public void finish(@NotNull NestedSessionRunner.Result result) {
        boolean ok = result.status() == NestedSessionRunner.Status.COMPLETED;
        panel.updateSubAgentResult(entryId,
            ok ? MessageFormatter.ChipStatus.COMPLETE : MessageFormatter.ChipStatus.FAILED,
            resultToShow(result, streamedText), null, false, null);
    }

    /**
     * The text to put under the entry when the session ends. Text that was streamed is already on screen, so it is
     * not shown a second time: a completed session then shows only its status, and a failed one only why. Text that
     * was not streamed, because the agent delivers its answer all at once, is shown here.
     *
     * @return the text, or {@code null} to show just the status
     */
    static @Nullable String resultToShow(@NotNull NestedSessionRunner.Result result, boolean streamedText) {
        if (result.status() == NestedSessionRunner.Status.COMPLETED) {
            return streamedText ? null : result.text();
        }
        String error = result.error() != null ? result.error() : "";
        if (streamedText || result.text().isEmpty()) {
            return error;
        }
        return error + "\n\n" + result.text();
    }

    private String toolId(@NotNull String agentToolCallId) {
        return entryId + "/" + agentToolCallId;
    }
}

package com.github.catatafishen.agentbridge.bridge;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Abstraction for showing permission/approval prompts to the user.
 * <p>
 * Backend services ({@code PsiBridgeService}, {@code CodexAppServerClient}) depend on this
 * interface rather than importing UI panel classes directly. The UI layer provides the
 * implementation (currently {@code BroadcastChatPanel}).
 * <p>
 * Retrieve the active provider via {@link #getInstance(Project)}.
 */
public interface PermissionPromptProvider {

    /**
     * Show a permission request (Deny / Allow / Allow for Session) and invoke the callback
     * with the user's choice.
     *
     * @param reqId           unique ID for this request
     * @param toolDisplayName human-readable tool name shown in the prompt
     * @param description     additional context (e.g., tool arguments summary)
     * @param onRespond       callback invoked on the EDT with the user's decision
     */
    void showPermissionPrompt(
        @NotNull String reqId,
        @NotNull String toolDisplayName,
        @NotNull String description,
        @NotNull Consumer<PermissionResponse> onRespond
    );

    /**
     * Show an ask-user bubble with quick-reply options and a countdown timer.
     *
     * @param reqId           unique ID for this request
     * @param question        the question text
     * @param options         quick-reply button labels
     * @param deadlineEpochMs initial deadline (millis since epoch) for the countdown
     * @param onRespond       callback invoked with the user's text response
     * @param onExtend        called when user clicks "I need more time"; returns new deadline
     * @param onSuperseded    called if a new ask-user request replaces this one
     */
    void showAskUserPrompt(
        @NotNull String reqId,
        @NotNull String question,
        @NotNull List<String> options,
        long deadlineEpochMs,
        @NotNull Consumer<String> onRespond,
        @NotNull Supplier<Long> onExtend,
        @NotNull Runnable onSuperseded
    );

    /**
     * Returns the active provider for the given project, or {@code null} if the chat panel
     * is not yet initialized.
     */
    @Nullable
    static PermissionPromptProvider getInstance(@NotNull Project project) {
        return PermissionPromptProviderHolder.getInstance(project);
    }
}

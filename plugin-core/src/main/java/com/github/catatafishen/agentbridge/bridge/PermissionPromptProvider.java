package com.github.catatafishen.agentbridge.bridge;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Abstraction for displaying tool permission prompts to the user.
 * <p>
 * Backend services ({@code PsiBridgeService}, {@code CodexClient}) depend on this
 * interface instead of the UI class directly, inverting the dependency so the backend layer
 * has no compile-time coupling to Swing/UI components.
 * <p>
 * The UI layer ({@code BroadcastChatPanel}) implements this interface and registers itself
 * via {@link PermissionPromptProviderHolder}.
 */
public interface PermissionPromptProvider {

    /**
     * Show a tool permission request in the chat panel.
     *
     * @param reqId           unique request identifier
     * @param toolDisplayName human-readable tool name
     * @param description     JSON or summary of what the tool wants to do
     * @param onRespond       callback invoked with the user's decision
     */
    void showPermissionPrompt(
        @NotNull String reqId,
        @NotNull String toolDisplayName,
        @NotNull String description,
        @NotNull Consumer<PermissionResponse> onRespond
    );

    /**
     * Notifies the UI that a previously shown permission request has been resolved on the
     * backend without a user decision — the fail-closed timeout expired, the turn was
     * cancelled, or the client stopped. The UI should expire any notification shown for
     * the request and disable its approval card so stale buttons cannot act on an
     * already-denied request. No-op for unknown request ids. Default no-op for providers
     * that don't track shown prompts.
     *
     * @param reqId request identifier previously passed to {@link #showPermissionPrompt}
     */
    default void expirePermissionPrompt(@NotNull String reqId) {
    }

    /**
     * Convenience accessor — equivalent to {@code PermissionPromptProviderHolder.get(project)}.
     */
    static PermissionPromptProvider getInstance(@NotNull Project project) {
        return PermissionPromptProviderHolder.get(project);
    }
}

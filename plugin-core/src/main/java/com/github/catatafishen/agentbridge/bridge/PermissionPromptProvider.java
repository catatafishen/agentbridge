package com.github.catatafishen.agentbridge.bridge;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Abstraction for displaying permission and ask-user prompts.
 * <p>
 * Backend services ({@code PsiBridgeService}, {@code CodexAppServerClient}) depend on this
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
     * Convenience accessor — equivalent to {@code PermissionPromptProviderHolder.get(project)}.
     */
    static PermissionPromptProvider getInstance(@NotNull Project project) {
        return PermissionPromptProviderHolder.get(project);
    }
}

package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.session.db.ConversationQuery;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class PromptDbService {

    /**
     * Opens/shows the side panel. Set by ChatToolWindowContent.
     * AtomicReference used for thread-safe publication without synchronised blocks.
     */
    private final AtomicReference<Runnable> showPanelCallback = new AtomicReference<>();
    /**
     * Switches to the Prompt DB tab and populates filters. Set by SidePanel.
     * AtomicReference used for thread-safe publication without synchronised blocks.
     */
    private final AtomicReference<Consumer<ConversationQuery.QueryParams>> navigateCallback = new AtomicReference<>();

    public static PromptDbService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, PromptDbService.class);
    }

    public void registerShowPanelCallback(@Nullable Runnable callback) {
        showPanelCallback.set(callback);
    }

    public void registerNavigateCallback(@Nullable Consumer<ConversationQuery.QueryParams> callback) {
        navigateCallback.set(callback);
    }

    /**
     * Opens the side panel (if needed), switches to the Prompt DB tab, and applies the
     * given query params as search filters. No-op if no callbacks are registered yet.
     * <b>Must be called off the EDT</b> — dispatches to EDT internally.
     */
    public void navigateToSearch(@NotNull ConversationQuery.QueryParams params) {
        EdtUtil.invokeLater(() -> {
            Runnable showCb = showPanelCallback.get();
            if (showCb != null) showCb.run();

            Consumer<ConversationQuery.QueryParams> navCb = navigateCallback.get();
            if (navCb != null) navCb.accept(params);
        });
    }
}

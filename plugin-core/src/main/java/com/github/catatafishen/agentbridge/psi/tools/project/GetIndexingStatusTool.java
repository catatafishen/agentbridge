package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Checks whether IntelliJ indexing is in progress; optionally waits until it finishes.
 */
@SuppressWarnings("java:S112")
public final class GetIndexingStatusTool extends ProjectTool {

    private static final String PARAM_TIMEOUT = "timeout";

    public GetIndexingStatusTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_indexing_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Indexing Status";
    }

    @Override
    public @NotNull String description() {
        return "Check whether IntelliJ indexing is in progress; optionally wait until it finishes";
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
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"wait", TYPE_BOOLEAN, "If true, blocks until indexing finishes"},
            {PARAM_TIMEOUT, TYPE_INTEGER, "Max seconds to wait when wait=true (default: 30)"}
        });
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        boolean wait = args.has("wait") && args.get("wait").getAsBoolean();
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;

        var dumbService = DumbService.getInstance(project);
        boolean indexing = dumbService.isDumb();

        if (indexing && wait) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            dumbService.runWhenSmart(() -> done.complete(null));
            try {
                done.get(timeoutSec, TimeUnit.SECONDS);
                return "Indexing finished. IDE is ready.";
            } catch (TimeoutException e) {
                return "Indexing still in progress after " + timeoutSec + "s timeout. Try again later.";
            }
        }

        if (indexing) {
            return "Indexing is in progress. Use wait=true to block until finished. " +
                "Some tools (inspections, find_references, search_symbols) may return incomplete results while indexing.";
        }
        return "IDE is ready. Indexing is complete.";
    }
}

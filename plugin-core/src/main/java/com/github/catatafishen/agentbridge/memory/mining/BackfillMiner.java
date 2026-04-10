package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class BackfillMiner {

    private static final Logger LOG = Logger.getInstance(BackfillMiner.class);

    private final Project project;

    /**
     * Package-private constructor for testing — bypasses project dependency.
     * Tests should call {@link #executeBackfill} directly.
     */
    BackfillMiner() {
        this.project = null;
    }

    public BackfillMiner(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Mine all historical sessions asynchronously.
     *
     * @param progressCallback called on the background thread with progress messages
     *                         (e.g., "Mining session 3 of 15: Fix auth bug")
     * @return future completing with the aggregate result
     */
    public CompletableFuture<BackfillResult> run(@NotNull Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(
            () -> doBackfill(progressCallback),
            AppExecutorUtil.getAppExecutorService()
        );
    }

    /**
     * Loads entries for a session by ID.
     */
    @FunctionalInterface
    interface EntryLoader {
        List<EntryData> load(String sessionId);
    }

    /**
     * Mines a list of entries for a session, returning the result synchronously.
     */
    @FunctionalInterface
    interface MineFunction {
        TurnMiner.MineResult mine(List<EntryData> entries, String sessionId, String agent);
    }

    private BackfillResult doBackfill(Consumer<String> progressCallback) {
        SessionStoreV2 sessionStore = SessionStoreV2.getInstance(project);
        String basePath = project.getBasePath();

        List<SessionStoreV2.SessionRecord> sessions = sessionStore.listSessions(basePath);
        if (sessions.isEmpty()) {
            progressCallback.accept("No sessions found to mine.");
            MemorySettings.getInstance(project).setBackfillCompleted(true);
            return new BackfillResult(0, 0, 0, 0, 0);
        }

        TurnMiner miner = new TurnMiner(project);
        EntryLoader loader = sessionId -> sessionStore.loadEntriesBySessionId(basePath, sessionId);
        MineFunction mineFn = (entries, sid, agent) -> miner.mineTurn(entries, sid, agent).join();

        BackfillResult result = executeBackfill(sessions, loader, mineFn, progressCallback);
        MemorySettings.getInstance(project).setBackfillCompleted(true);
        return result;
    }

    /**
     * Package-private for testing — runs the backfill iteration with explicit dependencies.
     */
    BackfillResult executeBackfill(List<SessionStoreV2.SessionRecord> sessions,
                                   EntryLoader entryLoader,
                                   MineFunction miner,
                                   Consumer<String> progressCallback) {
        progressCallback.accept("Found " + sessions.size() + " sessions to mine.");

        int totalSessions = sessions.size();
        int processedSessions = 0;
        int totalStored = 0;
        int totalFiltered = 0;
        int totalDuplicates = 0;
        int totalExchanges = 0;

        for (SessionStoreV2.SessionRecord session : sessions) {
            processedSessions++;
            String sessionLabel = session.name().isEmpty()
                ? session.id().substring(0, Math.min(8, session.id().length()))
                : session.name();
            progressCallback.accept("Mining session " + processedSessions + " of " + totalSessions
                + ": " + sessionLabel);

            try {
                List<EntryData> entries = entryLoader.load(session.id());
                if (entries == null || entries.isEmpty()) continue;

                TurnMiner.MineResult result = miner.mine(entries, session.id(), session.agent());
                totalStored += result.stored();
                totalFiltered += result.filtered();
                totalDuplicates += result.duplicates();
                totalExchanges += result.total();
            } catch (Exception e) {
                LOG.warn("Failed to mine session " + session.id(), e);
            }
        }

        String summary = "Backfill complete: " + totalStored + " memories stored from "
            + processedSessions + " sessions (" + totalDuplicates + " duplicates, "
            + totalFiltered + " filtered).";
        progressCallback.accept(summary);
        LOG.info(summary);

        return new BackfillResult(processedSessions, totalStored, totalFiltered, totalDuplicates, totalExchanges);
    }

    /**
     * @param sessions   number of sessions processed
     * @param stored     total drawers stored
     * @param filtered   total exchanges filtered out
     * @param duplicates total duplicate exchanges skipped
     * @param exchanges  total exchanges extracted
     */
    public record BackfillResult(int sessions, int stored, int filtered, int duplicates, int exchanges) {
    }
}

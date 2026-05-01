package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

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
     * Mine all historical sessions synchronously on the caller's thread.
     * Use this when the caller already provides a background thread with a progress indicator
     * (e.g., {@link com.intellij.openapi.progress.Task.Backgroundable}).
     *
     * @param textCallback     called with progress text (e.g., "Mining session 3 of 15: Fix auth bug (exchange 2 of 8)")
     * @param fractionCallback called with progress fraction (0.0–1.0) for progress bar updates
     * @param isCancelled      checked between exchanges — returns true to abort mining
     * @return the aggregate result
     */
    public BackfillResult runSync(@NotNull Consumer<String> textCallback,
                                  @NotNull DoubleConsumer fractionCallback,
                                  @NotNull BooleanSupplier isCancelled) {
        return doBackfill(textCallback, fractionCallback, isCancelled);
    }

    /**
     * Mine all historical sessions synchronously with text-only progress.
     * Convenience overload without fraction updates or cancellation support.
     */
    public BackfillResult runSync(@NotNull Consumer<String> progressCallback) {
        return doBackfill(progressCallback, fraction -> {
        }, () -> false);
    }

    /**
     * Mine all historical sessions asynchronously.
     *
     * @param progressCallback called on the background thread with progress messages
     * @return future completing with the aggregate result
     */
    public CompletableFuture<BackfillResult> run(@NotNull Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(
            () -> doBackfill(progressCallback, fraction -> {
            }, () -> false),
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
     * The optional exchange progress listener fires per-exchange within the session.
     */
    @FunctionalInterface
    interface MineFunction {
        TurnMiner.MineResult mine(List<EntryData> entries, String sessionId, String agent,
                                  @Nullable TurnMiner.ExchangeProgressListener exchangeProgress);
    }

    private BackfillResult doBackfill(Consumer<String> textCallback,
                                      DoubleConsumer fractionCallback,
                                      BooleanSupplier isCancelled) {
        SessionStoreV2 sessionStore = SessionStoreV2.getInstance(project);
        String basePath = project.getBasePath();

        List<SessionStoreV2.SessionRecord> sessions = sessionStore.listSessions(basePath);
        if (sessions.isEmpty()) {
            textCallback.accept("No sessions found to mine.");
            MemorySettings.getInstance(project).setBackfillCompleted(true);
            return new BackfillResult(0, 0, 0, 0, 0);
        }

        TurnMiner miner = new TurnMiner(project);
        EntryLoader loader = sessionId -> sessionStore.loadEntriesBySessionId(basePath, sessionId);
        MineFunction mineFn = miner::mineTurnSync;

        BackfillResult result = executeBackfill(sessions, loader, mineFn,
            textCallback, fractionCallback, isCancelled);
        MemorySettings.getInstance(project).setBackfillCompleted(true);
        return result;
    }

    /**
     * Package-private for testing — runs the backfill iteration with explicit dependencies.
     * Overload without fraction or cancellation support.
     */
    BackfillResult executeBackfill(List<SessionStoreV2.SessionRecord> sessions,
                                   EntryLoader entryLoader,
                                   MineFunction miner,
                                   Consumer<String> progressCallback) {
        return executeBackfill(sessions, entryLoader, miner,
            progressCallback, fraction -> {
            }, () -> false);
    }

    /**
     * Package-private for testing — runs the backfill iteration with explicit dependencies.
     */
    BackfillResult executeBackfill(List<SessionStoreV2.SessionRecord> sessions,
                                   EntryLoader entryLoader,
                                   MineFunction miner,
                                   Consumer<String> textCallback,
                                   DoubleConsumer fractionCallback,
                                   BooleanSupplier isCancelled) {
        textCallback.accept("Found " + sessions.size() + " sessions to mine.");

        int totalSessions = sessions.size();
        int processedSessions = 0;
        int totalStored = 0;
        int totalFiltered = 0;
        int totalDuplicates = 0;
        int totalExchanges = 0;

        for (SessionStoreV2.SessionRecord session : sessions) {
            if (isCancelled.getAsBoolean()) {
                textCallback.accept("Mining cancelled after " + processedSessions + " of " + totalSessions + " sessions.");
                LOG.info("Backfill cancelled by user after " + processedSessions + " sessions.");
                break;
            }

            processedSessions++;
            String sessionLabel = session.name().isEmpty()
                ? session.id().substring(0, Math.min(8, session.id().length()))
                : session.name();
            textCallback.accept("Mining session " + processedSessions + " of " + totalSessions
                + ": " + sessionLabel);
            fractionCallback.accept((double) (processedSessions - 1) / totalSessions);

            try {
                List<EntryData> entries = entryLoader.load(session.id());
                if (entries != null && !entries.isEmpty()) {
                    final int currentSession = processedSessions;
                    TurnMiner.MineResult result = miner.mine(entries, session.id(), session.agent(),
                        (exchangeNum, exchangeTotal) -> {
                            double sessionFraction = (double) exchangeNum / exchangeTotal;
                            double overallFraction = (currentSession - 1.0 + sessionFraction) / totalSessions;
                            fractionCallback.accept(overallFraction);
                            textCallback.accept("Mining session " + currentSession + " of " + totalSessions
                                + ": " + sessionLabel + " (exchange " + exchangeNum + " of " + exchangeTotal + ")");
                        });
                    totalStored += result.stored();
                    totalFiltered += result.filtered();
                    totalDuplicates += result.duplicates();
                    totalExchanges += result.total();
                }
            } catch (Exception e) {
                LOG.warn("Failed to mine session " + session.id(), e);
            }
        }

        fractionCallback.accept(1.0);
        String summary = "Backfill complete: " + totalStored + " memories stored from "
            + processedSessions + " sessions (" + totalDuplicates + " duplicates, "
            + totalFiltered + " filtered).";
        textCallback.accept(summary);
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

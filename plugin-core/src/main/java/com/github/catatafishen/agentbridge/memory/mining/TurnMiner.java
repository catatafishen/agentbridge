package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.memory.embedding.Embedder;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the mining pipeline: extracts Q+A pairs from conversation entries,
 * filters, classifies, detects rooms, generates embeddings, and stores in Lucene.
 *
 * <p>Runs asynchronously on a pooled thread so the IDE is never blocked.
 *
 * <p><b>Attribution:</b> pipeline design adapted from MemPalace's convo_miner.py (MIT License).
 */
public final class TurnMiner {

    private static final Logger LOG = Logger.getInstance(TurnMiner.class);

    private final Project project;

    /**
     * Package-private constructor for testing — bypasses project dependency.
     * Tests should call {@link #executePipeline} directly.
     */
    TurnMiner() {
        this.project = null;
    }

    public TurnMiner(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Mine a completed turn's entries into the memory store.
     * Returns a future that completes with the mining result.
     *
     * @param entries   conversation entries for this turn
     * @param sessionId current session ID
     * @param agentName name of the active agent
     * @return future completing with mine result
     */
    public CompletableFuture<MineResult> mineTurn(@NotNull List<EntryData> entries,
                                                  @NotNull String sessionId,
                                                  @NotNull String agentName) {
        return CompletableFuture.supplyAsync(
            () -> doMine(entries, sessionId, agentName),
            AppExecutorUtil.getAppExecutorService()
        );
    }

    private MineResult doMine(List<EntryData> entries, String sessionId, String agentName) {
        MemoryService memoryService = MemoryService.getInstance(project);
        MemoryStore store = memoryService.getStore();
        Embedder embedding = memoryService.getEmbeddingService();

        if (store == null || embedding == null) {
            return MineResult.EMPTY;
        }

        int maxDrawers = MemorySettings.getInstance(project).getMaxDrawersPerTurn();
        String wing = memoryService.getEffectiveWing();
        QualityFilter filter = new QualityFilter(project);

        return executePipeline(entries, sessionId, agentName, store, embedding, filter, maxDrawers, wing);
    }

    /**
     * Package-private for testing — runs the full mining pipeline with explicit dependencies.
     */
    MineResult executePipeline(List<EntryData> entries, String sessionId, String agentName,
                               MemoryStore store, Embedder embedder, QualityFilter filter,
                               int maxDrawers, String wing) {
        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        if (exchanges.isEmpty()) {
            return MineResult.EMPTY;
        }

        int stored = 0;
        int filtered = 0;
        int duplicates = 0;

        for (ExchangeChunker.Exchange exchange : exchanges) {
            if (stored >= maxDrawers) break;

            MineExchangeResult result = mineOneExchange(exchange, wing, sessionId, agentName, filter, store, embedder);
            stored += result.stored;
            filtered += result.filtered;
            duplicates += result.duplicates;
        }

        LOG.info("TurnMiner: stored=" + stored + " filtered=" + filtered
            + " duplicates=" + duplicates + " exchanges=" + exchanges.size());
        return new MineResult(stored, filtered, duplicates, exchanges.size());
    }

    private record MineExchangeResult(int stored, int filtered, int duplicates) {
    }

    private MineExchangeResult mineOneExchange(ExchangeChunker.Exchange exchange,
                                               String wing, String sessionId, String agentName,
                                               QualityFilter filter, MemoryStore store,
                                               Embedder embedder) {
        if (!filter.passes(exchange.prompt(), exchange.response())) {
            return new MineExchangeResult(0, 1, 0);
        }

        String combinedText = exchange.combinedText();
        String memoryType = MemoryClassifier.classify(combinedText);
        String room = RoomDetector.detect(combinedText);

        try {
            float[] vector = embedder.embed(combinedText);
            String drawerId = MemoryStore.generateDrawerId(wing, room, combinedText);
            DrawerDocument drawer = DrawerDocument.builder()
                .id(drawerId)
                .wing(wing)
                .room(room)
                .content(combinedText)
                .memoryType(memoryType)
                .sourceSession(sessionId)
                .agent(agentName)
                .filedAt(Instant.now())
                .addedBy(DrawerDocument.ADDED_BY_MINER)
                .build();

            String result = store.addDrawer(drawer, vector);
            return result != null ? new MineExchangeResult(1, 0, 0) : new MineExchangeResult(0, 0, 1);
        } catch (Exception e) {
            LOG.warn("Failed to mine exchange", e);
            return new MineExchangeResult(0, 0, 0);
        }
    }

    /**
     * Result of a mining operation.
     *
     * @param stored     number of drawers successfully stored
     * @param filtered   number of exchanges filtered out by quality check
     * @param duplicates number of exchanges skipped as duplicates
     * @param total      total number of exchanges extracted
     */
    public record MineResult(int stored, int filtered, int duplicates, int total) {
        static final MineResult EMPTY = new MineResult(0, 0, 0, 0);
    }
}

package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.memory.embedding.Embedder;
import com.github.catatafishen.agentbridge.memory.kg.KgTriple;
import com.github.catatafishen.agentbridge.memory.kg.KnowledgeGraph;
import com.github.catatafishen.agentbridge.memory.kg.TripleExtractor;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.validation.EvidenceExtractor;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
            () -> doMine(entries, sessionId, agentName, null),
            AppExecutorUtil.getAppExecutorService()
        );
    }

    /**
     * Listener for per-exchange progress during mining.
     * Used by {@link BackfillMiner} to report granular progress.
     */
    @FunctionalInterface
    interface ExchangeProgressListener {
        void onExchange(int current, int total);
    }

    /**
     * Mine a turn synchronously, with per-exchange progress reporting.
     * For use by {@link BackfillMiner} which already runs on a background thread.
     */
    MineResult mineTurnSync(@NotNull List<EntryData> entries,
                            @NotNull String sessionId,
                            @NotNull String agentName,
                            @Nullable ExchangeProgressListener exchangeProgress) {
        return doMine(entries, sessionId, agentName, exchangeProgress);
    }

    private MineResult doMine(List<EntryData> entries, String sessionId, String agentName,
                              @Nullable ExchangeProgressListener exchangeProgress) {
        MemoryService memoryService = MemoryService.getInstance(project);
        MemoryStore store = memoryService.getStore();
        Embedder embedding = memoryService.getEmbeddingService();

        if (store == null || embedding == null) {
            return MineResult.EMPTY;
        }

        int maxDrawers = MemorySettings.getInstance(project).getMaxDrawersPerTurn();
        String wing = memoryService.getEffectiveWing();
        QualityFilter filter = new QualityFilter(project);
        KnowledgeGraph kg = memoryService.getKnowledgeGraph();

        PipelineContext context = new PipelineContext(store, embedding, filter, maxDrawers, wing, kg, exchangeProgress);
        return executePipeline(entries, sessionId, agentName, context);
    }

    /**
     * Package-private for testing — runs the full mining pipeline with explicit dependencies.
     */
    MineResult executePipeline(List<EntryData> entries, String sessionId, String agentName,
                               PipelineContext context) {
        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        if (exchanges.isEmpty()) {
            return MineResult.EMPTY;
        }

        int stored = 0;
        int filtered = 0;
        int duplicates = 0;

        for (int i = 0; i < exchanges.size(); i++) {
            if (stored >= context.maxDrawers()) break;

            if (context.exchangeProgress() != null) {
                context.exchangeProgress().onExchange(i + 1, exchanges.size());
            }

            ExchangeChunker.Exchange exchange = exchanges.get(i);
            int turnIndex = i + 1;
            MineExchangeResult result = mineOneExchange(exchange, context, sessionId, agentName, turnIndex);
            stored += result.stored;
            filtered += result.filtered;
            duplicates += result.duplicates;
        }

        LOG.info("TurnMiner: stored=" + stored + " filtered=" + filtered
            + " duplicates=" + duplicates + " exchanges=" + exchanges.size());
        return new MineResult(stored, filtered, duplicates, exchanges.size());
    }

    record PipelineContext(MemoryStore store, Embedder embedder, QualityFilter filter,
                           int maxDrawers, String wing, @Nullable KnowledgeGraph kg,
                           @Nullable ExchangeProgressListener exchangeProgress) {
    }

    private record MineExchangeResult(int stored, int filtered, int duplicates) {
    }

    private MineExchangeResult mineOneExchange(ExchangeChunker.Exchange exchange,
                                               PipelineContext context, String sessionId,
                                               String agentName, int turnIndex) {
        if (!context.filter().passes(exchange.prompt(), exchange.response())) {
            return new MineExchangeResult(0, 1, 0);
        }

        String combinedText = exchange.combinedText();
        String memoryType = MemoryClassifier.classify(combinedText);
        String room = RoomDetector.detect(combinedText);
        Instant filedAt = parseExchangeTimestamp(exchange.timestamp());
        String commits = String.join(",", exchange.commitHashes());

        try {
            float[] vector = context.embedder().embed(combinedText);
            String drawerId = MemoryStore.generateDrawerId(context.wing(), room, combinedText);
            String evidenceJson = EvidenceExtractor.extractAsJson(combinedText);
            DrawerDocument drawer = DrawerDocument.builder()
                .id(drawerId)
                .wing(context.wing())
                .room(room)
                .content(combinedText)
                .memoryType(memoryType)
                .sourceSession(sessionId)
                .agent(agentName)
                .filedAt(filedAt)
                .addedBy(DrawerDocument.ADDED_BY_MINER)
                .sourceTurnIndex(String.valueOf(turnIndex))
                .sourceCommits(commits)
                .evidence(evidenceJson)
                .build();

            String result = context.store().addDrawer(drawer, vector);
            if (result != null) {
                extractTriples(combinedText, context.wing(), drawerId, context.kg());
                return new MineExchangeResult(1, 0, 0);
            }
            return new MineExchangeResult(0, 0, 1);
        } catch (Exception e) {
            LOG.warn("Failed to mine exchange", e);
            return new MineExchangeResult(0, 0, 0);
        }
    }

    private static void extractTriples(String text, String wing, String drawerId,
                                       @Nullable KnowledgeGraph kg) {
        if (kg == null) return;

        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, wing, drawerId);
        for (TripleExtractor.ExtractedTriple extracted : triples) {
            try {
                // Extract evidence only from the triple's object text, not the full
                // exchange. The full exchange evidence is stored on the drawer and
                // reachable via sourceDrawer — storing it on every triple wastes space
                // and produces noisy KG queries (stack traces, unrelated file paths).
                String tripleEvidence = EvidenceExtractor.extractAsJson(extracted.object());

                KgTriple triple = KgTriple.builder()
                    .subject(extracted.subject())
                    .predicate(extracted.predicate())
                    .object(extracted.object())
                    .sourceDrawer(extracted.sourceDrawerId())
                    .evidence(tripleEvidence)
                    .build();
                kg.addTriple(triple);
            } catch (Exception e) {
                LOG.debug("Failed to add extracted triple: " + extracted, e);
            }
        }
        if (!triples.isEmpty()) {
            LOG.info("Extracted " + triples.size() + " KG triple(s) from drawer " + drawerId);
        }
    }

    /**
     * Parse the ISO 8601 timestamp from an exchange, falling back to now if missing or unparseable.
     */
    private static Instant parseExchangeTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return Instant.now();
        }
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            LOG.debug("Unparseable exchange timestamp: " + timestamp);
            return Instant.now();
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

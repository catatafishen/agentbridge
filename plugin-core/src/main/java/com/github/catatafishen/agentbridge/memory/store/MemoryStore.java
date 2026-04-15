package com.github.catatafishen.agentbridge.memory.store;

import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a Lucene index at {@code .agent-work/memory/lucene-index/} to store
 * and search memory drawers with 384-dimensional vector embeddings.
 *
 * <p>Provides:
 * <ul>
 *   <li>Drawer storage with duplicate detection</li>
 *   <li>Semantic (KNN) search with optional metadata filters</li>
 *   <li>Taxonomy (drawer counts by wing/room)</li>
 *   <li>Top-N drawers by recency for L1 essential story</li>
 * </ul>
 *
 * <p><b>Attribution:</b> drawer ID generation and duplicate detection scheme adapted
 * from <a href="https://github.com/milla-jovovich/mempalace">MemPalace</a> (MIT License).
 */
public final class MemoryStore implements Disposable {

    private static final Logger LOG = Logger.getInstance(MemoryStore.class);

    // Lucene field names
    private static final String FLD_ID = "id";
    private static final String FLD_CONTENT = "content";
    private static final String FLD_EMBEDDING = "embedding";
    private static final String FLD_WING = "wing";
    private static final String FLD_ROOM = "room";
    private static final String FLD_MEMORY_TYPE = "memory_type";
    private static final String FLD_SOURCE_SESSION = "source_session";
    private static final String FLD_SOURCE_FILE = "source_file";
    private static final String FLD_AGENT = "agent";
    private static final String FLD_FILED_AT = "filed_at";
    private static final String FLD_ADDED_BY = "added_by";
    private static final String FLD_SOURCE_TURN_INDEX = "source_turn_index";
    private static final String FLD_SOURCE_COMMITS = "source_commits";
    private static final String FLD_EVIDENCE = "evidence";
    private static final String FLD_VERIFICATION_STATE = "verification_state";
    private static final String FLD_LAST_VERIFIED_AT = "last_verified_at";

    private static final float DUPLICATE_THRESHOLD = 0.9f;

    private final Path indexPath;
    private final WriteAheadLog wal;
    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    public MemoryStore(@NotNull Path indexPath, @NotNull WriteAheadLog wal) {
        this.indexPath = indexPath;
        this.wal = wal;
    }

    /**
     * Initialize the Lucene index. Must be called before any operations.
     */
    public void initialize() throws IOException {
        directory = FSDirectory.open(indexPath);
        IndexWriterConfig config = new IndexWriterConfig();
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        writer = new IndexWriter(directory, config);
        writer.commit();
        searcherManager = new SearcherManager(writer, null);
        LOG.info("MemoryStore initialized at " + indexPath);
    }

    /**
     * Generate a drawer ID using the MemPalace SHA-256 scheme.
     */
    public static @NotNull String generateDrawerId(@NotNull String wing, @NotNull String room, @NotNull String content) {
        String input = wing + room + content.substring(0, Math.min(100, content.length()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "drawer_" + wing + "_" + room + "_" + hex.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public @Nullable String addDrawer(@NotNull DrawerDocument drawer, float @NotNull [] embedding) throws IOException {
        if (isDuplicate(embedding)) {
            LOG.debug("Skipping duplicate drawer: " + drawer.id());
            return null;
        }

        Document doc = buildLuceneDocument(drawer, embedding);

        // WAL before write
        JsonObject walPayload = new JsonObject();
        walPayload.addProperty("wing", drawer.wing());
        walPayload.addProperty("room", drawer.room());
        walPayload.addProperty("type", drawer.memoryType());
        walPayload.addProperty("content_length", drawer.content().length());
        wal.log("add_drawer", drawer.id(), walPayload);

        writer.updateDocument(new Term(FLD_ID, drawer.id()), doc);
        writer.commit();
        searcherManager.maybeRefresh();

        return drawer.id();
    }

    /**
     * Semantic search with optional metadata filters.
     */
    public List<DrawerDocument.SearchResult> search(@NotNull MemoryQuery query,
                                                    float @Nullable [] queryEmbedding) throws IOException {
        searcherManager.maybeRefresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs topDocs;
            if (queryEmbedding != null) {
                // KNN vector search with optional pre-filter
                BooleanQuery.Builder filter = buildMetadataFilter(query);
                if (filter != null) {
                    topDocs = searcher.search(
                        new KnnFloatVectorQuery(FLD_EMBEDDING, queryEmbedding, query.limit(), filter.build()),
                        query.limit());
                } else {
                    topDocs = searcher.search(
                        new KnnFloatVectorQuery(FLD_EMBEDDING, queryEmbedding, query.limit()),
                        query.limit());
                }
            } else {
                // Metadata-only query
                BooleanQuery.Builder filter = buildMetadataFilter(query);
                topDocs = searcher.search(
                    filter != null ? filter.build() : new MatchAllDocsQuery(),
                    query.limit());
            }

            return toSearchResults(searcher, topDocs);
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Get drawer counts grouped by wing and room.
     */
    public Map<String, Map<String, Integer>> getTaxonomy() throws IOException {
        searcherManager.maybeRefresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Map<String, Map<String, Integer>> taxonomy = new LinkedHashMap<>();
            TopDocs all = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
            for (ScoreDoc sd : all.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String wing = doc.get(FLD_WING);
                String room = doc.get(FLD_ROOM);
                taxonomy.computeIfAbsent(wing, k -> new LinkedHashMap<>())
                    .merge(room, 1, Integer::sum);
            }
            return taxonomy;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Check if content with high similarity already exists (duplicate detection).
     */
    public boolean isDuplicate(float @NotNull [] embedding) throws IOException {
        searcherManager.maybeRefresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs results = searcher.search(
                new KnnFloatVectorQuery(FLD_EMBEDDING, embedding, 1), 1);
            if (results.scoreDocs.length > 0) {
                return results.scoreDocs[0].score >= DUPLICATE_THRESHOLD;
            }
            return false;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Get top N drawers for a wing, sorted by recency (filed_at descending).
     * Used by L1 EssentialStoryLayer.
     */
    public List<DrawerDocument> getTopDrawers(@NotNull String wing, int maxDrawers) throws IOException {
        searcherManager.maybeRefresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            BooleanQuery wingQuery = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(FLD_WING, wing)), BooleanClause.Occur.MUST)
                .build();
            TopDocs topDocs = searcher.search(wingQuery, maxDrawers * 2);

            List<DrawerDocument> drawers = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                drawers.add(documentToDrawer(searcher.storedFields().document(sd.doc)));
            }

            // Sort by filedAt descending and limit
            drawers.sort((a, b) -> b.filedAt().compareTo(a.filedAt()));
            if (drawers.size() > maxDrawers) {
                drawers = drawers.subList(0, maxDrawers);
            }
            return drawers;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Get top N drawers with per-room diversity, ensuring wake-up context
     * spans multiple topic areas rather than being dominated by the most
     * recent session's room.
     *
     * <p>Strategy: fetches recent drawers, groups by room, then round-robin
     * selects from each room to fill the quota. This guarantees at least one
     * drawer per room (when available) before any room gets a second.
     *
     * @param wing       project wing
     * @param maxDrawers maximum drawers to return
     * @return drawers with room diversity, most recent first within each room
     */
    public List<DrawerDocument> getTopDrawersDiverse(@NotNull String wing, int maxDrawers) throws IOException {
        // Fetch a larger pool to have enough diversity candidates
        List<DrawerDocument> pool = getTopDrawers(wing, maxDrawers * 3);
        if (pool.size() <= maxDrawers) return pool;

        // Group by room, preserving recency order within each group
        java.util.LinkedHashMap<String, List<DrawerDocument>> byRoom = new java.util.LinkedHashMap<>();
        for (DrawerDocument d : pool) {
            byRoom.computeIfAbsent(d.room(), k -> new ArrayList<>()).add(d);
        }

        // Round-robin across rooms
        List<DrawerDocument> result = new ArrayList<>(maxDrawers);
        int round = 0;
        while (result.size() < maxDrawers) {
            boolean added = false;
            for (List<DrawerDocument> roomDrawers : byRoom.values()) {
                if (round < roomDrawers.size() && result.size() < maxDrawers) {
                    result.add(roomDrawers.get(round));
                    added = true;
                }
            }
            if (!added) break;
            round++;
        }
        return result;
    }

    /**
     * Get total number of drawers in the index.
     */
    public int getDrawerCount() throws IOException {
        searcherManager.maybeRefresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release(searcher);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private @Nullable BooleanQuery.Builder buildMetadataFilter(@NotNull MemoryQuery query) {
        BooleanQuery.Builder builder = null;
        builder = addTermFilter(builder, FLD_WING, query.wing());
        builder = addTermFilter(builder, FLD_ROOM, query.room());
        builder = addTermFilter(builder, FLD_MEMORY_TYPE, query.memoryType());
        builder = addTermFilter(builder, FLD_AGENT, query.agent());
        return builder;
    }

    private static @Nullable BooleanQuery.Builder addTermFilter(
        @Nullable BooleanQuery.Builder builder, @NotNull String field, @Nullable String value
    ) {
        if (value == null || value.isEmpty()) return builder;
        if (builder == null) builder = new BooleanQuery.Builder();
        builder.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.MUST);
        return builder;
    }

    private List<DrawerDocument.SearchResult> toSearchResults(
        @NotNull IndexSearcher searcher, @NotNull TopDocs topDocs
    ) throws IOException {
        List<DrawerDocument.SearchResult> results = new ArrayList<>();
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(sd.doc);
            DrawerDocument drawer = documentToDrawer(doc);
            results.add(new DrawerDocument.SearchResult(drawer, sd.score));
        }
        return results;
    }

    private static DrawerDocument documentToDrawer(@NotNull Document doc) {
        return DrawerDocument.builder()
            .id(getString(doc, FLD_ID))
            .wing(getString(doc, FLD_WING))
            .room(getString(doc, FLD_ROOM))
            .content(getString(doc, FLD_CONTENT))
            .memoryType(getString(doc, FLD_MEMORY_TYPE))
            .sourceSession(getString(doc, FLD_SOURCE_SESSION))
            .sourceFile(getString(doc, FLD_SOURCE_FILE))
            .agent(getString(doc, FLD_AGENT))
            .filedAt(Instant.parse(doc.get(FLD_FILED_AT)))
            .addedBy(getString(doc, FLD_ADDED_BY))
            .sourceTurnIndex(getString(doc, FLD_SOURCE_TURN_INDEX))
            .sourceCommits(getString(doc, FLD_SOURCE_COMMITS))
            .evidence(getString(doc, FLD_EVIDENCE))
            .verificationState(parseVerificationState(doc))
            .lastVerifiedAt(parseNullableInstant(doc, FLD_LAST_VERIFIED_AT))
            .build();
    }

    /**
     * Returns the stored field value, or {@code ""} if the field is absent (schema evolution).
     */
    @NotNull
    private static String getString(@NotNull Document doc, @NotNull String field) {
        String value = doc.get(field);
        return value != null ? value : "";
    }

    private static @NotNull String parseVerificationState(@NotNull Document doc) {
        String state = doc.get(FLD_VERIFICATION_STATE);
        if (state == null || state.isEmpty()) return DrawerDocument.STATE_UNVERIFIED;
        return state;
    }

    private static @Nullable Instant parseNullableInstant(@NotNull Document doc, @NotNull String field) {
        String value = doc.get(field);
        if (value == null || value.isEmpty()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    public void updateVerificationState(@NotNull String drawerId, @NotNull String newState) throws IOException {
        IndexWriter w = writer;
        if (w == null) throw new IOException("MemoryStore not initialized");

        Term idTerm = new Term(FLD_ID, drawerId);
        try (DirectoryReader reader = DirectoryReader.open(w)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TermQuery query = new TermQuery(idTerm);
            TopDocs docs = searcher.search(query, 1);
            if (docs.totalHits.value() == 0) {
                throw new IOException("Drawer not found: " + drawerId);
            }

            int docId = docs.scoreDocs[0].doc;
            Document oldDoc = searcher.storedFields().document(docId);
            DrawerDocument drawer = documentToDrawer(oldDoc);

            // Read the embedding vector from the KNN vector index
            float[] vector = readVectorFromIndex(reader, docId);

            DrawerDocument updated = DrawerDocument.builder()
                .id(drawer.id())
                .wing(drawer.wing())
                .room(drawer.room())
                .content(drawer.content())
                .memoryType(drawer.memoryType())
                .sourceSession(drawer.sourceSession())
                .sourceFile(drawer.sourceFile())
                .agent(drawer.agent())
                .filedAt(drawer.filedAt())
                .addedBy(drawer.addedBy())
                .sourceTurnIndex(drawer.sourceTurnIndex())
                .sourceCommits(drawer.sourceCommits())
                .evidence(drawer.evidence())
                .verificationState(newState)
                .lastVerifiedAt(Instant.now())
                .build();

            w.deleteDocuments(idTerm);
            Document newDoc = buildLuceneDocument(updated, vector);
            w.addDocument(newDoc);
            w.commit();

            if (searcherManager != null) {
                searcherManager.maybeRefresh();
            }
        }
    }

    private static float @Nullable [] readVectorFromIndex(@NotNull DirectoryReader reader, int docId) throws IOException {
        for (var leafCtx : reader.leaves()) {
            int localDocId = docId - leafCtx.docBase;
            if (localDocId < 0 || localDocId >= leafCtx.reader().maxDoc()) continue;
            var vectorValues = leafCtx.reader().getFloatVectorValues(FLD_EMBEDDING);
            if (vectorValues == null) continue;
            try {
                float[] vec = vectorValues.vectorValue(localDocId);
                return vec.clone();
            } catch (Exception e) {
                // Document may not have a vector — return null
            }
        }
        return null;
    }

    /**
     * Find all drawers whose evidence field contains the given file path or reference.
     *
     * @param reference a file path, FQN, or file:line reference to search for
     * @return drawers whose evidence JSON contains the reference string
     */
    public @NotNull List<DrawerDocument> findByEvidence(@NotNull String reference) throws IOException {
        IndexWriter w = writer;
        if (w == null) throw new IOException("MemoryStore not initialized");

        List<DrawerDocument> results = new ArrayList<>();
        try (DirectoryReader reader = DirectoryReader.open(w)) {
            for (int i = 0; i < reader.maxDoc(); i++) {
                Document doc = reader.storedFields().document(i);
                String evidence = getString(doc, FLD_EVIDENCE);
                if (evidence.contains(reference)) {
                    results.add(documentToDrawer(doc));
                }
            }
        }
        return results;
    }

    /**
     * Update evidence references in all drawer documents, replacing old references with new ones.
     * Used when a symbol is renamed or moved. Marks affected documents as stale so they
     * get re-validated on next access.
     *
     * @return number of drawers updated
     */
    public int updateEvidenceRef(@NotNull String oldRef, @NotNull String newRef) throws IOException {
        IndexWriter w = writer;
        if (w == null) throw new IOException("MemoryStore not initialized");

        List<DrawerDocument> affected = findByEvidence(oldRef);
        int updated = 0;

        for (DrawerDocument drawer : affected) {
            String updatedEvidence = drawer.evidence().replace(oldRef, newRef);

            Term idTerm = new Term(FLD_ID, drawer.id());
            try (DirectoryReader reader = DirectoryReader.open(w)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs docs = searcher.search(new TermQuery(idTerm), 1);
                if (docs.totalHits.value() == 0) continue;

                int docId = docs.scoreDocs[0].doc;
                float[] vector = readVectorFromIndex(reader, docId);

                DrawerDocument updatedDrawer = DrawerDocument.builder()
                    .id(drawer.id())
                    .wing(drawer.wing())
                    .room(drawer.room())
                    .content(drawer.content())
                    .memoryType(drawer.memoryType())
                    .sourceSession(drawer.sourceSession())
                    .sourceFile(drawer.sourceFile())
                    .agent(drawer.agent())
                    .filedAt(drawer.filedAt())
                    .addedBy(drawer.addedBy())
                    .sourceTurnIndex(drawer.sourceTurnIndex())
                    .sourceCommits(drawer.sourceCommits())
                    .evidence(updatedEvidence)
                    .verificationState(DrawerDocument.STATE_STALE)
                    .lastVerifiedAt(Instant.now())
                    .build();

                w.deleteDocuments(idTerm);
                Document newDoc = buildLuceneDocument(updatedDrawer, vector);
                w.addDocument(newDoc);
                updated++;
            }
        }

        if (updated > 0) {
            w.commit();
            if (searcherManager != null) {
                searcherManager.maybeRefresh();
            }
        }

        return updated;
    }

    private Document buildLuceneDocument(@NotNull DrawerDocument drawer, float @Nullable [] vector) {
        Document doc = new Document();
        doc.add(new StringField(FLD_ID, drawer.id(), Field.Store.YES));
        doc.add(new TextField(FLD_CONTENT, drawer.content(), Field.Store.YES));
        doc.add(new StringField(FLD_WING, drawer.wing(), Field.Store.YES));
        doc.add(new StringField(FLD_ROOM, drawer.room(), Field.Store.YES));
        doc.add(new StringField(FLD_MEMORY_TYPE, drawer.memoryType(), Field.Store.YES));
        doc.add(new StringField(FLD_SOURCE_SESSION, drawer.sourceSession(), Field.Store.YES));
        doc.add(new StringField(FLD_SOURCE_FILE, drawer.sourceFile(), Field.Store.YES));
        doc.add(new StringField(FLD_AGENT, drawer.agent(), Field.Store.YES));
        doc.add(new StringField(FLD_FILED_AT, drawer.filedAt().toString(), Field.Store.YES));
        doc.add(new StringField(FLD_ADDED_BY, drawer.addedBy(), Field.Store.YES));
        doc.add(new StringField(FLD_SOURCE_TURN_INDEX, drawer.sourceTurnIndex(), Field.Store.YES));
        doc.add(new StringField(FLD_SOURCE_COMMITS, drawer.sourceCommits(), Field.Store.YES));
        doc.add(new StoredField(FLD_EVIDENCE, drawer.evidence()));
        doc.add(new StringField(FLD_VERIFICATION_STATE, drawer.verificationState(), Field.Store.YES));
        String verifiedAt = drawer.lastVerifiedAt() != null ? drawer.lastVerifiedAt().toString() : "";
        doc.add(new StringField(FLD_LAST_VERIFIED_AT, verifiedAt, Field.Store.YES));
        if (vector != null) {
            doc.add(new KnnFloatVectorField(FLD_EMBEDDING, vector, VectorSimilarityFunction.COSINE));
        }
        return doc;
    }

    @Override
    public void dispose() {
        try {
            if (searcherManager != null) {
                searcherManager.close();
                searcherManager = null;
            }
            if (writer != null) {
                writer.close();
                writer = null;
            }
            if (directory != null) {
                directory.close();
                directory = null;
            }
        } catch (IOException e) {
            LOG.warn("Error closing MemoryStore", e);
        }
    }
}

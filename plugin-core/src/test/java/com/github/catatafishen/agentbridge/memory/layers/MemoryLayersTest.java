package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for L1 ({@link EssentialStoryLayer}) and L2 ({@link OnDemandLayer}).
 * Uses a real {@link MemoryStore} backed by a temp Lucene index.
 * L0 (IdentityLayer) requires a Project mock and is tested separately.
 * L3 (DeepSearchLayer) requires EmbeddingService and ONNX model, so is skipped.
 */
class MemoryLayersTest {

    private static final String WING = "test-project";
    private static int vectorCounter;

    @TempDir
    Path tempDir;

    private MemoryStore store;

    @BeforeEach
    void setUp() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal"));
        wal.initialize();
        store = new MemoryStore(tempDir.resolve("index"), wal);
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.dispose();
    }

    // --- EssentialStoryLayer (L1) ---

    @Test
    void essentialLayerIdAndName() {
        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        assertEquals("L1-essential", layer.layerId());
        assertEquals("Essential Story", layer.displayName());
    }

    @Test
    void essentialReturnsEmptyWhenNoDrawers() {
        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void essentialRendersDrawers() throws IOException {
        addDrawer("d1", WING, "coding", "decision", "Decided to use Lucene for vector search");
        addDrawer("d2", WING, "coding", "technical", "Implemented embedding service with ONNX Runtime");

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);

        assertTrue(result.startsWith("## Essential Story"));
        assertTrue(result.contains(WING));
        assertTrue(result.contains("[decision]"));
        assertTrue(result.contains("[technical]"));
        assertTrue(result.contains("Lucene"));
        assertTrue(result.contains("ONNX"));
    }

    @Test
    void essentialRespectsMaxDrawers() throws IOException {
        for (int i = 0; i < 5; i++) {
            addDrawer("d" + i, WING, "coding", "technical", "Memory " + i);
        }

        EssentialStoryLayer layer = new EssentialStoryLayer(store, 2);
        String result = layer.render(WING, null);
        assertFalse(result.isEmpty());
        // Count the number of list items (- [ lines)
        long lineCount = result.lines().filter(l -> l.startsWith("- [")).count();
        assertEquals(2, lineCount);
    }

    @Test
    void essentialIgnoresOtherWings() throws IOException {
        addDrawer("d1", "other-project", "coding", "decision", "Some other project memory");

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void essentialTruncatesLongContent() throws IOException {
        String longContent = "A".repeat(500);
        addDrawer("d1", WING, "coding", "technical", longContent);

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);
        assertTrue(result.contains("…"));
        assertTrue(result.length() < 500);
    }

    // --- OnDemandLayer (L2) ---

    @Test
    void onDemandLayerIdAndName() {
        OnDemandLayer layer = new OnDemandLayer(store);
        assertEquals("L2-on-demand", layer.layerId());
        assertEquals("On-Demand Recall", layer.displayName());
    }

    @Test
    void onDemandReturnsEmptyWhenNoDrawers() {
        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "coding");
        assertEquals("", result);
    }

    @Test
    void onDemandFiltersbyRoom() throws IOException {
        addDrawer("d1", WING, "coding", "decision", "Use Gradle for build system");
        addDrawer("d2", WING, "design", "preference", "Prefer dark themes in UI");

        OnDemandLayer layer = new OnDemandLayer(store);
        String resultCoding = layer.render(WING, "coding");
        assertTrue(resultCoding.contains("Gradle"));
        assertFalse(resultCoding.contains("dark themes"));
    }

    @Test
    void onDemandIncludesQueryInHeader() throws IOException {
        addDrawer("d1", WING, "testing", "technical", "Use JUnit 5 for tests");

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "testing");
        assertTrue(result.contains("## On-Demand Recall — testing"));
    }

    @Test
    void onDemandReturnsAllWhenNoRoomFilter() throws IOException {
        addDrawer("d1", WING, "coding", "decision", "Memory one");
        addDrawer("d2", WING, "design", "preference", "Memory two");

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, null);
        assertTrue(result.contains("Memory one"));
        assertTrue(result.contains("Memory two"));
    }

    @Test
    void onDemandRespectsLimit() throws IOException {
        for (int i = 0; i < 5; i++) {
            addDrawer("od" + i, WING, "coding", "technical", "On demand memory " + i);
        }

        OnDemandLayer layer = new OnDemandLayer(store, 2);
        String result = layer.render(WING, null);
        assertFalse(result.isEmpty());
        long lineCount = result.lines().filter(l -> l.startsWith("- [")).count();
        assertEquals(2, lineCount);
    }

    // --- DeepSearchLayer (L3) — interface only, requires EmbeddingService + ONNX ---

    @Test
    void deepSearchImplementsMemoryStack() {
        // Verify DeepSearchLayer is a MemoryStack (full test requires ONNX model)
        assertNotNull(DeepSearchLayer.class.getInterfaces());
        assertEquals(1, DeepSearchLayer.class.getInterfaces().length);
        assertEquals(MemoryStack.class, DeepSearchLayer.class.getInterfaces()[0]);
    }

    // --- MemoryStack interface contract ---

    @Test
    void allLayersImplementMemoryStack() {
        EssentialStoryLayer l1 = new EssentialStoryLayer(store);
        OnDemandLayer l2 = new OnDemandLayer(store);
        // Verify interface contract
        assertInstanceOf(MemoryStack.class, l1);
        assertInstanceOf(MemoryStack.class, l2);
    }

    private void addDrawer(String id, String wing, String room, String memoryType, String content) throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id(id)
            .wing(wing)
            .room(room)
            .content(content)
            .memoryType(memoryType)
            .sourceSession("test-session")
            .agent("test-agent")
            .filedAt(Instant.now())
            .addedBy("test")
            .build();
        store.addDrawer(drawer, uniqueVector());
    }

    private static float[] uniqueVector() {
        float[] v = new float[384];
        v[vectorCounter % 384] = 1.0f;
        vectorCounter++;
        return v;
    }
}

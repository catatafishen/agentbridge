package com.github.catatafishen.agentbridge.memory.kg;

import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link KnowledgeGraph} — SQLite triple store.
 */
class KnowledgeGraphTest {

    @TempDir
    Path tempDir;

    private KnowledgeGraph kg;

    @BeforeEach
    void setUp() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal"));
        wal.initialize();
        kg = new KnowledgeGraph(tempDir.resolve("knowledge.sqlite3"), wal);
        kg.initialize();
    }

    @AfterEach
    void tearDown() {
        kg.dispose();
    }

    @Test
    void addTripleReturnsId() throws IOException {
        KgTriple triple = KgTriple.builder()
            .subject("project")
            .predicate("uses")
            .object("Java 21")
            .build();

        long id = kg.addTriple(triple);
        assertTrue(id > 0);
    }

    @Test
    void queryBySubject() throws IOException {
        addTriple("project", "uses", "Java 21");
        addTriple("project", "prefers", "Gradle");
        addTriple("team", "follows", "Scrum");

        List<KgTriple> results = kg.query("project", null, null, 10);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(t -> "project".equals(t.subject())));
    }

    @Test
    void queryByPredicate() throws IOException {
        addTriple("project", "uses", "Java 21");
        addTriple("project", "uses", "Gradle");
        addTriple("project", "prefers", "tabs");

        List<KgTriple> results = kg.query(null, "uses", null, 10);
        assertEquals(2, results.size());
    }

    @Test
    void queryByObjectSubstring() throws IOException {
        addTriple("project", "uses", "Java 21");
        addTriple("project", "uses", "JavaScript");

        List<KgTriple> results = kg.query(null, null, "Java", 10);
        assertEquals(2, results.size());
    }

    @Test
    void queryExcludesInvalidatedTriples() throws IOException {
        long id = addTriple("project", "uses", "Java 17");
        kg.invalidateTriple(id);

        addTriple("project", "uses", "Java 21");

        List<KgTriple> results = kg.query("project", "uses", null, 10);
        assertEquals(1, results.size());
        assertEquals("Java 21", results.getFirst().object());
    }

    @Test
    void invalidateTripleReturnsTrue() throws IOException {
        long id = addTriple("project", "uses", "Java 17");
        assertTrue(kg.invalidateTriple(id));
    }

    @Test
    void invalidateNonexistentReturnsFalse() throws IOException {
        assertFalse(kg.invalidateTriple(999));
    }

    @Test
    void invalidateAlreadyInvalidatedReturnsFalse() throws IOException {
        long id = addTriple("project", "uses", "Java 17");
        assertTrue(kg.invalidateTriple(id));
        assertFalse(kg.invalidateTriple(id));
    }

    @Test
    void invalidateBySubjectPredicate() throws IOException {
        addTriple("project", "uses", "Java 17");
        addTriple("project", "uses", "Java 11");
        addTriple("project", "prefers", "Gradle");

        int invalidated = kg.invalidateBySubjectPredicate("project", "uses");
        assertEquals(2, invalidated);

        List<KgTriple> remaining = kg.query("project", null, null, 10);
        assertEquals(1, remaining.size());
        assertEquals("prefers", remaining.getFirst().predicate());
    }

    @Test
    void getTimelineIncludesInvalidated() throws IOException {
        long id = addTriple("project", "uses", "Java 17");
        kg.invalidateTriple(id);
        addTriple("project", "uses", "Java 21");

        List<KgTriple> timeline = kg.getTimeline("project", 10);
        assertEquals(2, timeline.size());
    }

    @Test
    void getTimelineOrderedByCreatedAtDesc() throws IOException {
        addTriple("project", "uses", "Java 11");
        addTriple("project", "uses", "Java 17");
        addTriple("project", "uses", "Java 21");

        List<KgTriple> timeline = kg.getTimeline("project", 10);
        assertEquals(3, timeline.size());
        // Most recent first
        assertEquals("Java 21", timeline.getFirst().object());
    }

    @Test
    void getTimelineRespectsLimit() throws IOException {
        for (int i = 0; i < 10; i++) {
            addTriple("project", "version", "v" + i);
        }

        List<KgTriple> timeline = kg.getTimeline("project", 3);
        assertEquals(3, timeline.size());
    }

    @Test
    void getTripleCount() throws IOException {
        assertEquals(0, kg.getTripleCount());

        addTriple("a", "b", "c");
        addTriple("d", "e", "f");
        assertEquals(2, kg.getTripleCount());

        kg.addTriple(KgTriple.builder().subject("g").predicate("h").object("i").build());
        List<KgTriple> allTriples = kg.query(null, null, null, 100);
        long lastId = allTriples.getFirst().id();
        assertTrue(kg.invalidateTriple(lastId));
        assertEquals(2, kg.getTripleCount());
    }

    @Test
    void queryWithNoFiltersReturnsAll() throws IOException {
        addTriple("a", "b", "c");
        addTriple("d", "e", "f");

        List<KgTriple> results = kg.query(null, null, null, 10);
        assertEquals(2, results.size());
    }

    @Test
    void tripleWithValidFrom() throws IOException {
        Instant validFrom = Instant.parse("2024-06-01T00:00:00Z");
        KgTriple triple = KgTriple.builder()
            .subject("project")
            .predicate("uses")
            .object("Java 21")
            .validFrom(validFrom)
            .build();

        kg.addTriple(triple);
        List<KgTriple> results = kg.query("project", null, null, 10);
        assertEquals(1, results.size());
        assertEquals(validFrom, results.getFirst().validFrom());
    }

    @Test
    void tripleWithSourceDrawer() throws IOException {
        KgTriple triple = KgTriple.builder()
            .subject("project")
            .predicate("uses")
            .object("Java 21")
            .sourceDrawer("drawer_proj_tech_abc123")
            .build();

        kg.addTriple(triple);
        List<KgTriple> results = kg.query("project", null, null, 10);
        assertEquals("drawer_proj_tech_abc123", results.getFirst().sourceDrawer());
    }

    private long addTriple(String subject, String predicate, String object) throws IOException {
        KgTriple triple = KgTriple.builder()
            .subject(subject)
            .predicate(predicate)
            .object(object)
            .build();
        return kg.addTriple(triple);
    }
}

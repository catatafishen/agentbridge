package com.github.catatafishen.agentbridge.memory.kg;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TripleExtractor} — pattern-based triple extraction from conversation text.
 */
class TripleExtractorTest {

    private static final String WING = "test-project";
    private static final String DRAWER_ID = "drawer_test_001";

    @Test
    void decisionPattern() {
        String text = "We decided to use JWT tokens for authentication.\nThis keeps it stateless.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "decided", "use JWT tokens");
    }

    @Test
    void chosePattern() {
        String text = "I chose PostgreSQL instead of MySQL for the database.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "decided", "PostgreSQL");
    }

    @Test
    void usagePattern() {
        String text = "The project uses Gradle for building.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "uses", "Gradle");
    }

    @Test
    void preferencePattern() {
        String text = "We always use conventional commits for this project.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "prefers", "conventional commits");
    }

    @Test
    void dependencyPattern() {
        String text = "The plugin depends on ONNX Runtime for inference.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "depends-on", "ONNX Runtime");
    }

    @Test
    void implementationPattern() {
        String text = "We implemented a write-ahead log for crash recovery.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "implemented", "write-ahead log");
    }

    @Test
    void resolutionPattern() {
        String text = "I fixed the classloader issue by loading the driver explicitly.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "resolved", "classloader issue");
    }

    @Test
    void rootCausePattern() {
        String text = "The root cause was the plugin classloader not being visible to DriverManager.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "caused-by", "the plugin classloader not being visible to DriverManager");
    }

    @Test
    void builtWithPattern() {
        String text = "This plugin is written in Java 21.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "built-with", "Java 21");
    }

    @Test
    void multipleTriples() {
        String text = "We use Lucene for vector search. The project depends on ONNX Runtime for embeddings. "
            + "We decided to use SQLite for the knowledge graph.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertTrue(triples.size() >= 3, "Expected at least 3 triples, got " + triples.size());
    }

    @Test
    void noTriplesFromGenericText() {
        String text = "Hello, how are you? I'm fine thanks.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertTrue(triples.isEmpty());
    }

    @Test
    void subjectDefaultsToWing() {
        String text = "We use Gradle for building.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertFalse(triples.isEmpty());
        assertEquals(WING, triples.getFirst().subject());
    }

    @Test
    void sourceDrawerIdIsPreserved() {
        String text = "We decided to use Java 21.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertFalse(triples.isEmpty());
        assertEquals(DRAWER_ID, triples.getFirst().sourceDrawerId());
    }

    @Test
    void longObjectIsTruncated() {
        String text = "We implemented " + "a very long feature name that goes on and on ".repeat(10) + ".";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        for (TripleExtractor.ExtractedTriple triple : triples) {
            assertTrue(triple.object().length() <= 120,
                "Object too long: " + triple.object().length());
        }
    }

    @Test
    void maxTriplesPerTextRespected() {
        // Build text with many extractable patterns
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("We use tool-").append(i).append(" for task-").append(i).append(".\n");
        }
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(sb.toString(), WING, DRAWER_ID);

        assertTrue(triples.size() <= 8, "Should cap at 8 triples, got " + triples.size());
    }

    @Test
    void shortObjectsAreFiltered() {
        String text = "We use it.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        // "it" is only 2 chars, should be filtered out
        assertTrue(triples.isEmpty());
    }

    private static void assertContainsTriple(List<TripleExtractor.ExtractedTriple> triples,
                                              String predicate, String objectSubstring) {
        boolean found = triples.stream().anyMatch(t ->
            t.predicate().equals(predicate) && t.object().contains(objectSubstring));
        assertTrue(found, "Expected triple with predicate='" + predicate
            + "' containing '" + objectSubstring + "' in: " + triples);
    }
}

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

    // ── Pattern matching tests ────────────────────────────────────────────

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
        String text = "The plugin depends on Lucene for vector search.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "depends-on", "Lucene");
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
        String text = "We use Lucene for vector search. The project depends on Gradle for builds. "
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

        assertTrue(triples.isEmpty());
    }

    // ── Markdown stripping tests ──────────────────────────────────────────

    @Test
    void markdownBoldIsUnwrapped() {
        String text = "We use **Gradle** for building.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "uses", "Gradle");
    }

    @Test
    void markdownBoldDoesNotProduceArtifacts() {
        // Regression: previously extracted "**lazy" as a triple object
        String text = "The system uses **lazy initialization** for startup performance.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        for (TripleExtractor.ExtractedTriple triple : triples) {
            assertFalse(triple.object().contains("**"),
                "Object contains markdown artifact: " + triple.object());
        }
    }

    @Test
    void fencedCodeBlockIsRemoved() {
        String text = "We use Gradle for building.\n```groovy\nplugins {\n  id 'java'\n}\n```\nThis works well.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "uses", "Gradle");
        // Should not extract patterns from inside code blocks
        for (TripleExtractor.ExtractedTriple triple : triples) {
            assertFalse(triple.object().contains("plugins"),
                "Extracted from code block: " + triple.object());
        }
    }

    @Test
    void inlineCodeIsRemoved() {
        String text = "We use `HashMap<String, Object>` for caching.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        for (TripleExtractor.ExtractedTriple triple : triples) {
            assertFalse(triple.object().contains("HashMap"),
                "Extracted inline code content: " + triple.object());
        }
    }

    @Test
    void markdownLinksAreUnwrapped() {
        String result = TripleExtractor.stripMarkdown("See [Lucene docs](https://lucene.apache.org) for details.");

        assertTrue(result.contains("Lucene docs"));
        assertFalse(result.contains("https://"));
    }

    @Test
    void headersAreStripped() {
        String result = TripleExtractor.stripMarkdown("## Architecture\nWe use microservices.");

        assertFalse(result.contains("##"));
        assertTrue(result.contains("Architecture"));
    }

    // ── Quality filtering tests ───────────────────────────────────────────

    @Test
    void rejectsAllStopwordObjects() {
        // "the memory" — both words are stopwords → rejected
        assertFalse(TripleExtractor.isQualityObject("the memory"));
        assertFalse(TripleExtractor.isQualityObject("a new method"));
        assertFalse(TripleExtractor.isQualityObject("the old system"));
    }

    @Test
    void acceptsObjectsWithSpecificTerms() {
        assertTrue(TripleExtractor.isQualityObject("Gradle"));
        assertTrue(TripleExtractor.isQualityObject("safetensors model"));
        assertTrue(TripleExtractor.isQualityObject("write-ahead log"));
        assertTrue(TripleExtractor.isQualityObject("the plugin classloader"));
    }

    @Test
    void rejectsTooManyWords() {
        String longObject = "this is a very long object phrase that contains way too many words for a triple";
        assertFalse(TripleExtractor.isQualityObject(longObject));
    }

    @Test
    void rejectsTooShort() {
        assertFalse(TripleExtractor.isQualityObject("it"));
        assertFalse(TripleExtractor.isQualityObject("ab"));
    }

    // ── Sentence isolation tests ──────────────────────────────────────────

    @Test
    void patternDoesNotCrossNewlines() {
        // Regression: "implemented" pattern captured across sentence boundary
        // producing "Restart the IDE and the agent should now recall memories"
        String text = "We implemented the wake-up fix.\nRestart the IDE and the agent should now recall memories.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        for (TripleExtractor.ExtractedTriple triple : triples) {
            assertFalse(triple.object().contains("Restart"),
                "Pattern crossed sentence boundary: " + triple.object());
        }
    }

    @Test
    void patternDoesNotCrossPeriodBoundary() {
        String text = "We implemented the memory fix. Restart the IDE now.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        for (TripleExtractor.ExtractedTriple triple : triples) {
            assertFalse(triple.object().contains("Restart"),
                "Pattern crossed period boundary: " + triple.object());
        }
    }

    @Test
    void sentenceSplittingPreservesMultiplePatterns() {
        String text = "We use Lucene for search.\nThe project depends on Gradle for builds.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        assertContainsTriple(triples, "uses", "Lucene");
        assertContainsTriple(triples, "depends-on", "Gradle");
    }

    // ── Deduplication tests ───────────────────────────────────────────────

    @Test
    void duplicatePredicateObjectIsSkipped() {
        String text = "We use Gradle for building.\nThe project uses Gradle for compilation.";
        List<TripleExtractor.ExtractedTriple> triples = TripleExtractor.extract(text, WING, DRAWER_ID);

        long gradleCount = triples.stream()
            .filter(t -> t.predicate().equals("uses") && t.object().equals("Gradle"))
            .count();
        assertEquals(1, gradleCount, "Duplicate Gradle triple should be deduplicated");
    }

    // ── Sentence splitting unit tests ─────────────────────────────────────

    @Test
    void splitSentencesOnNewlines() {
        List<String> sentences = TripleExtractor.splitSentences("First line.\nSecond line.");

        assertEquals(2, sentences.size());
        assertEquals("First line.", sentences.get(0));
        assertEquals("Second line.", sentences.get(1));
    }

    @Test
    void splitSentencesOnPeriodUppercase() {
        List<String> sentences = TripleExtractor.splitSentences("First sentence. Second sentence.");

        assertEquals(2, sentences.size());
    }

    @Test
    void splitSentencesSkipsEmptyLines() {
        List<String> sentences = TripleExtractor.splitSentences("First.\n\n\nSecond.");

        assertEquals(2, sentences.size());
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private static void assertContainsTriple(List<TripleExtractor.ExtractedTriple> triples,
                                             String predicate, String objectSubstring) {
        boolean found = triples.stream().anyMatch(t ->
            t.predicate().equals(predicate) && t.object().contains(objectSubstring));
        assertTrue(found, "Expected triple with predicate='" + predicate
            + "' containing '" + objectSubstring + "' in: " + triples);
    }
}

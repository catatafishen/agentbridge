package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemoryClassifier} — regex-based memory type classification.
 */
class MemoryClassifierTest {

    @Test
    void classifiesDecision() {
        assertEquals(DrawerDocument.TYPE_DECISION,
            MemoryClassifier.classify("We decided to use Gradle instead of Maven"));
    }

    @Test
    void classifiesDecisionWithAlternativeMarkers() {
        assertEquals(DrawerDocument.TYPE_DECISION,
            MemoryClassifier.classify("We went with PostgreSQL. Going with this tradeoff was worth it."));
    }

    @Test
    void classifiesProblem() {
        assertEquals(DrawerDocument.TYPE_PROBLEM,
            MemoryClassifier.classify("There's a bug causing the app to crash with a regression"));
    }

    @Test
    void classifiesSolution() {
        assertEquals(DrawerDocument.TYPE_SOLUTION,
            MemoryClassifier.classify("We fixed the bug and resolved the issue. The solution was to reset the cache."));
    }

    @Test
    void classifiesSolutionWithTurnsOut() {
        assertEquals(DrawerDocument.TYPE_SOLUTION,
            MemoryClassifier.classify("Turns out the fix was simple. By changing the timeout, it's working now."));
    }

    @Test
    void classifiesContext() {
        assertEquals(DrawerDocument.TYPE_CONTEXT,
            MemoryClassifier.classify("The architecture uses a clean pattern with interface abstraction"));
    }

    @Test
    void classifiesContextWithStructure() {
        assertEquals(DrawerDocument.TYPE_CONTEXT,
            MemoryClassifier.classify("The module structure consists of three layers responsible for different concerns"));
    }

    @Test
    void fallsBackToGeneral() {
        assertEquals(DrawerDocument.TYPE_GENERAL,
            MemoryClassifier.classify("Hello, how are you today?"));
    }

    @Test
    void problemWithoutResolutionStaysProblem() {
        assertEquals(DrawerDocument.TYPE_PROBLEM,
            MemoryClassifier.classify("There is a bug causing crashes, a regression from the last release"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(DrawerDocument.TYPE_DECISION,
            MemoryClassifier.classify("WE DECIDED TO use the NEW FRAMEWORK"));
    }

    @Test
    void multipleTypesHighestScoreWins() {
        // More decision markers than context markers
        String text = "We decided to go with this instead of that, we chose the tradeoff. " +
            "The architecture is clean.";
        assertEquals(DrawerDocument.TYPE_DECISION, MemoryClassifier.classify(text));
    }

    @Test
    void emptyTextReturnsGeneral() {
        assertEquals(DrawerDocument.TYPE_GENERAL, MemoryClassifier.classify(""));
    }
}

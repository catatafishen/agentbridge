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
    void classifiesPreference() {
        assertEquals(DrawerDocument.TYPE_PREFERENCE,
            MemoryClassifier.classify("I prefer tabs over spaces, always use 4-space indentation"));
    }

    @Test
    void classifiesMilestone() {
        assertEquals(DrawerDocument.TYPE_MILESTONE,
            MemoryClassifier.classify("It works! We finally shipped the feature and released v2.0"));
    }

    @Test
    void classifiesProblem() {
        assertEquals(DrawerDocument.TYPE_PROBLEM,
            MemoryClassifier.classify("There's a bug causing the app to crash with a regression"));
    }

    @Test
    void classifiesTechnical() {
        assertEquals(DrawerDocument.TYPE_TECHNICAL,
            MemoryClassifier.classify("The architecture uses a clean pattern with API interface abstraction"));
    }

    @Test
    void fallsBackToGeneral() {
        assertEquals(DrawerDocument.TYPE_GENERAL,
            MemoryClassifier.classify("Hello, how are you today?"));
    }

    @Test
    void disambiguatesResolvedProblemAsMilestone() {
        // Text with problem markers AND resolution markers → milestone
        assertEquals(DrawerDocument.TYPE_MILESTONE,
            MemoryClassifier.classify("The bug in the login module was fixed and is working now"));
    }

    @Test
    void problemWithFixedBecomeMilestone() {
        assertEquals(DrawerDocument.TYPE_MILESTONE,
            MemoryClassifier.classify("The crash was a regression. We fixed the root cause and resolved the issue"));
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
        // More decision markers than technical markers
        String text = "We decided to go with this instead of that, we chose the tradeoff. " +
            "The architecture is clean.";
        assertEquals(DrawerDocument.TYPE_DECISION, MemoryClassifier.classify(text));
    }

    @Test
    void emptyTextReturnsGeneral() {
        assertEquals(DrawerDocument.TYPE_GENERAL, MemoryClassifier.classify(""));
    }
}

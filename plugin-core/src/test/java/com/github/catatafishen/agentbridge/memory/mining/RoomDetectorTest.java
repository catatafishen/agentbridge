package com.github.catatafishen.agentbridge.memory.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RoomDetector} — keyword-based room/topic detection.
 */
class RoomDetectorTest {

    @Test
    void detectsTechnicalRoom() {
        assertEquals("technical",
            RoomDetector.detect("We need to debug the thread pool and fix the async exception"));
    }

    @Test
    void detectsArchitectureRoom() {
        assertEquals("architecture",
            RoomDetector.detect("The design pattern uses a service layer with clean separation of concerns"));
    }

    @Test
    void detectsPlanningRoom() {
        assertEquals("planning",
            RoomDetector.detect("The roadmap for the next sprint includes a milestone for the feature deadline"));
    }

    @Test
    void detectsDecisionsRoom() {
        assertEquals("decisions",
            RoomDetector.detect("The decision was based on trade-off analysis. We chose this instead of the alternative"));
    }

    @Test
    void detectsProblemsRoom() {
        assertEquals("problems",
            RoomDetector.detect("The bug causes a crash. We need to investigate and reproduce the stack trace"));
    }

    @Test
    void fallsBackToGeneral() {
        assertEquals("general",
            RoomDetector.detect("Hello, let me help you today with your question"));
    }

    @Test
    void caseInsensitive() {
        assertEquals("technical",
            RoomDetector.detect("We need to COMPILE and BUILD the CODE with the new DEPENDENCY"));
    }

    @Test
    void emptyTextReturnsGeneral() {
        assertEquals("general", RoomDetector.detect(""));
    }

    @Test
    void mixedContentHighestScoreWins() {
        // More technical keywords than architecture keywords
        String text = "The code uses a function with method and variable. " +
            "Need to compile and build the test. " +
            "The architecture is modular.";
        assertEquals("technical", RoomDetector.detect(text));
    }

    @Test
    void shortTextWithOneKeyword() {
        assertEquals("technical", RoomDetector.detect("fix the build"));
    }

    @Test
    void problemsWithMultipleKeywords() {
        assertEquals("problems",
            RoomDetector.detect("There is a bug issue causing the error to fail with a regression"));
    }
}

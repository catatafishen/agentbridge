package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link RoomDetector} — keyword-based room/topic detection.
 */
class RoomDetectorTest {

    @Test
    void detectsCodebaseRoom() {
        assertEquals(DrawerDocument.ROOM_CODEBASE,
            RoomDetector.detect("The class uses a service interface with a method that overrides the base implementation"));
    }

    @Test
    void detectsCodebaseRoomForDependencies() {
        assertEquals(DrawerDocument.ROOM_CODEBASE,
            RoomDetector.detect("Added a new dependency library version for the configuration module component"));
    }

    @Test
    void detectsDebuggingRoom() {
        assertEquals(DrawerDocument.ROOM_DEBUGGING,
            RoomDetector.detect("The bug causes a crash. We need to investigate and reproduce the stack trace"));
    }

    @Test
    void detectsDebuggingRoomForErrors() {
        assertEquals(DrawerDocument.ROOM_DEBUGGING,
            RoomDetector.detect("There's a null pointer exception causing the error to fail with a regression"));
    }

    @Test
    void detectsWorkflowRoom() {
        assertEquals(DrawerDocument.ROOM_WORKFLOW,
            RoomDetector.detect("The gradle build pipeline deploys to staging via docker automation"));
    }

    @Test
    void detectsWorkflowRoomForGit() {
        assertEquals(DrawerDocument.ROOM_WORKFLOW,
            RoomDetector.detect("Create a branch, merge after lint and format pass in the CI pipeline"));
    }

    @Test
    void detectsDecisionsRoom() {
        assertEquals(DrawerDocument.ROOM_DECISIONS,
            RoomDetector.detect("The decision was based on trade-off analysis. We chose this instead of the alternative"));
    }

    @Test
    void detectsPreferencesRoom() {
        assertEquals(DrawerDocument.ROOM_PREFERENCES,
            RoomDetector.detect("I prefer this naming convention, please always use consistent formatting style"));
    }

    @Test
    void fallsBackToGeneral() {
        assertEquals(DrawerDocument.ROOM_GENERAL,
            RoomDetector.detect("Hello, let me help you today with your question"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(DrawerDocument.ROOM_CODEBASE,
            RoomDetector.detect("The CLASS has a METHOD and FIELD with INTERFACE"));
    }

    @Test
    void emptyTextReturnsGeneral() {
        assertEquals(DrawerDocument.ROOM_GENERAL, RoomDetector.detect(""));
    }

    @Test
    void mixedContentHighestScoreWins() {
        // More debugging keywords than codebase keywords
        String text = "The bug causes a crash with an error exception. " +
            "We need to investigate the stack trace and debug. " +
            "The class uses an interface.";
        assertEquals(DrawerDocument.ROOM_DEBUGGING, RoomDetector.detect(text));
    }

    @Test
    void shortTextWithOneKeyword() {
        assertEquals(DrawerDocument.ROOM_WORKFLOW, RoomDetector.detect("run the gradle build"));
    }

    @Test
    void debuggingWithMultipleKeywords() {
        assertEquals(DrawerDocument.ROOM_DEBUGGING,
            RoomDetector.detect("There is a bug causing the error to fail with a regression and crash"));
    }
}

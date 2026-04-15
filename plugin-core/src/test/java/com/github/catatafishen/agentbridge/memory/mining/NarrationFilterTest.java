package com.github.catatafishen.agentbridge.memory.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrationFilterTest {

    @Test
    void isNarration_detectsToolNarration() {
        assertTrue(NarrationFilter.isNarration("I'll read the file to understand the issue"));
        assertTrue(NarrationFilter.isNarration("Let me search for the implementation"));
        assertTrue(NarrationFilter.isNarration("Now I'll check the tests"));
        assertTrue(NarrationFilter.isNarration("I need to check the build output"));
        assertTrue(NarrationFilter.isNarration("I'm going to fix the failing test"));
    }

    @Test
    void isNarration_detectsObservationNarration() {
        assertTrue(NarrationFilter.isNarration("Looking at the code, it seems"));
        assertTrue(NarrationFilter.isNarration("Checking the test results"));
        assertTrue(NarrationFilter.isNarration("Reading the error output"));
        assertTrue(NarrationFilter.isNarration("Searching for references"));
        assertTrue(NarrationFilter.isNarration("Examining the stack trace"));
    }

    @Test
    void isNarration_detectsOutputCommentary() {
        assertTrue(NarrationFilter.isNarration("The output shows that the test passed"));
        assertTrue(NarrationFilter.isNarration("I can see that the fix works"));
        assertTrue(NarrationFilter.isNarration("Here's what I found in the codebase"));
    }

    @Test
    void isNarration_detectsBuildStatusNarration() {
        assertTrue(NarrationFilter.isNarration("Good — no compilation errors"));
        assertTrue(NarrationFilter.isNarration("Clean build. Let me continue"));
        assertTrue(NarrationFilter.isNarration("All 47 tests pass"));
        assertTrue(NarrationFilter.isNarration("Tests pass. Moving on"));
    }

    @Test
    void isNarration_preservesSubstantiveContent() {
        assertFalse(NarrationFilter.isNarration("The root cause was a race condition"));
        assertFalse(NarrationFilter.isNarration("We use JWT for authentication"));
        assertFalse(NarrationFilter.isNarration("The fix replaces async with sync"));
        assertFalse(NarrationFilter.isNarration("This class handles binary detection"));
        assertFalse(NarrationFilter.isNarration("Fixed the encoding issue on Windows"));
    }

    @Test
    void filter_removesNarrationLines() {
        String input = """
            The root cause was encoding mismatch.
            Let me search for the implementation.
            On Windows, cmd.exe uses OEM code page.
            I'll read the file to verify.""";

        String filtered = NarrationFilter.filter(input);

        assertTrue(filtered.contains("root cause was encoding mismatch"));
        assertTrue(filtered.contains("Windows, cmd.exe uses OEM code page"));
        assertFalse(filtered.contains("Let me search"));
        assertFalse(filtered.contains("I'll read"));
    }

    @Test
    void filter_preservesAllSubstantiveContent() {
        String input = "We decided to use Gradle 8.x with the IntelliJ Platform Plugin 2.x.";
        assertEquals(input, NarrationFilter.filter(input));
    }

    @Test
    void filter_collapsesConsecutiveBlanks() {
        String input = """
            First line of substance.
            Let me check the output.
            
            I'll read the tests.
            Second line of substance.""";

        String filtered = NarrationFilter.filter(input);

        assertTrue(filtered.contains("First line of substance."));
        assertTrue(filtered.contains("Second line of substance."));
        assertFalse(filtered.contains("\n\n\n"));
    }

    @Test
    void filter_emptyInput() {
        assertEquals("", NarrationFilter.filter(""));
        assertEquals("", NarrationFilter.filter("   "));
    }
}

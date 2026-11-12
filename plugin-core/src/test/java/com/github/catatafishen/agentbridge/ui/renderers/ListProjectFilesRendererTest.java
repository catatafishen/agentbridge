package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListProjectFilesRendererTest {

    @Test
    void excludedSectionAfterBlankLineIsTextNotFileEntries() {
        var parts = ListProjectFilesRenderer.splitBody(List.of(
            "src/A.java [sources Java, 1 KB, 2026-01-01]",
            "",
            "Excluded directories (not indexed, contents not listed):",
            "build/ [excluded]",
            "Directories marked Excluded are not indexed..."));
        assertEquals(List.of("src/A.java [sources Java, 1 KB, 2026-01-01]"), parts.getFirst());
        assertEquals(3, parts.getSecond().size());
        assertEquals("build/ [excluded]", parts.getSecond().get(1));
    }

    @Test
    void listingWithoutTrailerKeepsAllLinesAsEntries() {
        var parts = ListProjectFilesRenderer.splitBody(List.of("a.txt [other Text, 1 B, x]", "b.txt [other Text, 1 B, x]"));
        assertEquals(2, parts.getFirst().size());
        assertEquals(List.of(), parts.getSecond());
    }
}

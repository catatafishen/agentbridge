package com.github.catatafishen.agentbridge.psi.tools.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextToolPathFilterTest {

    private static SearchTextTool.FilePattern rel(String pattern) {
        return SearchTextTool.relativizeFilePattern(pattern, "/p/proj", true);
    }

    @Test
    void absoluteFilePatternInsideProjectBecomesAnchoredRelative() {
        assertEquals(new SearchTextTool.FilePattern("AGENTS.md", true), rel("/p/proj/AGENTS.md"));
    }

    @Test
    void anchoredRootFileDoesNotMatchSameNameInSubdirectory() {
        SearchTextTool.FilePattern p = rel("/p/proj/AGENTS.md");
        assertFalse(p.rejects("AGENTS.md", null));
        assertTrue(p.rejects("sub/AGENTS.md", null));
    }

    @Test
    void relativeFilenamePatternStillMatchesAtAnyDepth() {
        SearchTextTool.FilePattern p = rel("AGENTS.md");
        assertFalse(p.anchored());
        assertFalse(p.rejects("sub/AGENTS.md", null));
    }

    @Test
    void foreignAbsolutePatternIsUnchanged() {
        assertEquals(new SearchTextTool.FilePattern("/p/other/AGENTS.md", false), rel("/p/other/AGENTS.md"));
    }

    @Test
    void driveLetterCaseIgnoredOnCaseInsensitiveFileSystem() {
        SearchTextTool.FilePattern p = SearchTextTool.relativizeFilePattern("c:\\work\\proj\\AGENTS.md", "C:/work/proj", false);
        assertEquals(new SearchTextTool.FilePattern("AGENTS.md", true), p);
    }

    @Test
    void caseMismatchNotStrippedOnCaseSensitiveFileSystem() {
        assertFalse(rel("/P/proj/AGENTS.md").anchored());
    }

    @Test
    void skippedLargeNoteListsFilesAndMarksMore() {
        assertEquals("", SearchTextTool.formatSkippedLarge(0, List.of()));
        assertEquals("\n(1 file(s) >1 MB skipped: a.log)", SearchTextTool.formatSkippedLarge(1, List.of("a.log")));
        assertEquals("\n(3 file(s) >1 MB skipped: a, b, ...)", SearchTextTool.formatSkippedLarge(3, List.of("a", "b")));
    }
}

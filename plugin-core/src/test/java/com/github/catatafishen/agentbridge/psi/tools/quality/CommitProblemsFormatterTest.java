package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.tools.quality.CommitProblemsFormatter.Entry;
import com.github.catatafishen.agentbridge.psi.tools.quality.CommitProblemsFormatter.Page;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitProblemsFormatterTest {

    private static Entry entry(String path, int line, String severity, String message) {
        return new Entry(path, line, severity, message, List.of());
    }

    private static String render(List<Entry> all, int fileCount, Page page) {
        List<Entry> sorted = CommitProblemsFormatter.filterAndSort(all, page.errorsOnly());
        List<Entry> pageEntries = page.summaryOnly()
            ? List.of() : CommitProblemsFormatter.pageOf(sorted, page.offset(), page.limit());
        return CommitProblemsFormatter.format(sorted, fileCount, pageEntries, page);
    }

    @Test
    void errorsAreListedBeforeWarningsRegardlessOfFile() {
        var sorted = CommitProblemsFormatter.filterAndSort(List.of(
            entry("a.java", 1, "WARNING", "w"),
            entry("b.java", 9, "ERROR", "e")), false);
        assertEquals("ERROR", sorted.get(0).severity());
        assertEquals("WARNING", sorted.get(1).severity());
    }

    @Test
    void problemsOfTheSameSeverityAreOrderedByPathThenLine() {
        var sorted = CommitProblemsFormatter.filterAndSort(List.of(
            entry("b.java", 1, "WARNING", "x"),
            entry("a.java", 20, "WARNING", "x"),
            entry("a.java", 3, "WARNING", "x")), false);
        assertEquals("a.java", sorted.getFirst().path());
        assertEquals(3, sorted.getFirst().line());
        assertEquals(20, sorted.get(1).line());
        assertEquals("b.java", sorted.get(2).path());
    }

    @Test
    void errorsOnlyDropsEverythingElse() {
        var sorted = CommitProblemsFormatter.filterAndSort(List.of(
            entry("a.java", 1, "WARNING", "w"),
            entry("a.java", 2, "ERROR", "e")), true);
        assertEquals(1, sorted.size());
        assertEquals("ERROR", sorted.get(0).severity());
    }

    @Test
    void unknownSeveritiesSortAfterWarnings() {
        var sorted = CommitProblemsFormatter.filterAndSort(List.of(
            entry("a.java", 1, "WEAK WARNING", "x"),
            entry("a.java", 2, "WARNING", "x")), false);
        assertEquals("WARNING", sorted.getFirst().severity());
    }

    @Test
    void pageOfReturnsRequestedWindow() {
        List<Entry> all = new ArrayList<>();
        for (int i = 1; i <= 5; i++) all.add(entry("a.java", i, "WARNING", "m" + i));
        var page = CommitProblemsFormatter.pageOf(all, 1, 2);
        assertEquals(2, page.size());
        assertEquals(2, page.get(0).line());
        assertEquals(3, page.get(1).line());
    }

    @Test
    void pageOfClampsAtTheEndAndHandlesOffsetBeyondSize() {
        List<Entry> all = List.of(entry("a.java", 1, "WARNING", "m"), entry("a.java", 2, "WARNING", "m"));
        assertEquals(1, CommitProblemsFormatter.pageOf(all, 1, 10).size());
        assertTrue(CommitProblemsFormatter.pageOf(all, 5, 10).isEmpty());
    }

    @Test
    void cleanResultSaysSoAndMentionsFileCount() {
        String out = render(List.of(), 3, new Page(false, false, 0, 50));
        assertEquals("No problems found in 3 changed file(s).", out);
    }

    @Test
    void cleanResultWithErrorsOnlyTalksAboutErrors() {
        String out = render(List.of(entry("a.java", 1, "WARNING", "w")), 2, new Page(true, false, 0, 50));
        assertEquals("No errors found in 2 changed file(s).", out);
    }

    @Test
    void summaryContainsTotalsAndPerFileCountsWithWorstFileFirst() {
        String out = render(List.of(
            entry("many-warnings.java", 1, "WARNING", "w"),
            entry("many-warnings.java", 2, "WARNING", "w"),
            entry("has-error.java", 1, "ERROR", "e")), 4, new Page(false, true, 0, 50));
        assertTrue(out.startsWith("3 problems in 2 of 4 changed file(s): 1 ERROR, 2 WARNING"), out);
        assertTrue(out.indexOf("has-error.java") < out.indexOf("many-warnings.java"), out);
        assertTrue(out.contains("has-error.java (1 ERROR)"), out);
        assertTrue(out.contains("many-warnings.java (2 other)"), out);
    }

    @Test
    void summaryOnlyOmitsIndividualProblems() {
        String out = render(List.of(entry("a.java", 7, "ERROR", "boom")), 1, new Page(false, true, 0, 50));
        assertFalse(out.contains("a.java:7"), out);
        assertFalse(out.contains("boom"), out);
        assertTrue(out.contains("Summary only"), out);
    }

    @Test
    void detailLinesUseTheSameFormatAsGetProblems() {
        String out = render(List.of(entry("a.java", 7, "ERROR", "boom")), 1, new Page(false, false, 0, 50));
        assertTrue(out.contains("a.java:7 [ERROR] boom"), out);
        assertTrue(out.contains("Showing 1-1 of 1"), out);
    }

    @Test
    void truncatedPageAnnouncesTheNextOffset() {
        List<Entry> all = new ArrayList<>();
        for (int i = 1; i <= 5; i++) all.add(entry("a.java", i, "WARNING", "m" + i));
        String out = render(all, 1, new Page(false, false, 0, 2));
        assertTrue(out.contains("Showing 1-2 of 5"), out);
        assertTrue(out.contains("3 more. Next page: offset=2"), out);
        assertTrue(out.contains("a.java:2 [WARNING] m2"), out);
        assertFalse(out.contains("a.java:3 "), out);
    }

    @Test
    void lastPageHasNoNextPageHint() {
        List<Entry> all = new ArrayList<>();
        for (int i = 1; i <= 3; i++) all.add(entry("a.java", i, "WARNING", "m" + i));
        String out = render(all, 1, new Page(false, false, 2, 5));
        assertTrue(out.contains("Showing 3-3 of 3"), out);
        assertFalse(out.contains("Next page"), out);
    }

    @Test
    void offsetBeyondTheEndReportsAnEmptyPageButKeepsTheSummary() {
        String out = render(List.of(entry("a.java", 1, "WARNING", "m")), 1, new Page(false, false, 10, 5));
        assertTrue(out.contains("1 problems in 1 of 1"), out);
        assertTrue(out.contains("No problems at offset 10 (total 1)"), out);
    }

    @Test
    void noNoteWhenFixesWereDeterminedForAllFiles() {
        assertEquals("", CommitProblemsFormatter.fixesUnavailableNote(List.of(), null));
    }

    @Test
    void noteNamesTheFilesAndTheReason() {
        String note = CommitProblemsFormatter.fixesUnavailableNote(List.of("a.java", "b.java"), "analysis failed");
        assertTrue(note.contains("Quick-fixes not determined for 2 file(s): a.java, b.java"), note);
        assertTrue(note.contains("Reason: analysis failed"), note);
        assertTrue(note.startsWith("\n\n"), "Must be separated from the listing: " + note);
    }

    @Test
    void noteAdmitsAnUnknownReasonInsteadOfOmittingIt() {
        String note = CommitProblemsFormatter.fixesUnavailableNote(List.of("a.java"), null);
        assertTrue(note.contains("Reason: unknown"), note);
    }

    @Test
    void fixesAreRenderedUnderTheirProblem() {
        var withFixes = entry("a.java", 4, "ERROR", "boom").withFixes(List.of("Add import", "Create class"));
        var sorted = List.of(withFixes);
        String out = CommitProblemsFormatter.format(sorted, 1, sorted, new Page(false, false, 0, 50));
        assertTrue(out.contains("a.java:4 [ERROR] boom\n    Fix: Add import\n    Fix: Create class"), out);
    }

    @Test
    void problemsWithoutFixesGetNoFixLines() {
        var sorted = List.of(entry("a.java", 4, "ERROR", "boom"));
        String out = CommitProblemsFormatter.format(sorted, 1, sorted, new Page(false, false, 0, 50));
        assertFalse(out.contains("Fix:"), out);
    }
}

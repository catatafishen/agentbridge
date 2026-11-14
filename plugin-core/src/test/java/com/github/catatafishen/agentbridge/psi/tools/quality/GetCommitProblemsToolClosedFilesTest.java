package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.intellij.ide.util.PropertiesComponent;

import java.util.List;

/**
 * {@code get_commit_problems} when the changed files are NOT open in an editor, which is the normal case for an
 * agent that has just edited files on disk. Both the findings and the quick-fixes are computed in the background
 * ({@code CodeSmellDetector} and {@code MainPassesRunner}); no editor is opened and nothing waits for the editor
 * daemon, so the "Follow Agent Files" settings must not influence the result.
 */
public class GetCommitProblemsToolClosedFilesTest extends CommitProblemsToolTestCase {

    // String overload: the boolean overload removes the key when value == default, which would leave the
    // setting at its default instead of the value we want.
    private void configure(boolean followAgentFiles, boolean allowTransientOpens) {
        PropertiesComponent props = PropertiesComponent.getInstance(getProject());
        props.setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, String.valueOf(followAgentFiles));
        props.setValue(ToolLayerSettings.ALLOW_TRANSIENT_FILE_OPENS_KEY, String.valueOf(allowTransientOpens));
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        assertEquals("Precondition: all changed files start closed", List.of(), openChangedFileNames());
    }

    public void testFindingsAreReportedForClosedFilesWithoutOpeningThem() throws Exception {
        configure(false, false);

        String result = run(json("summary_only", true));

        assertTrue("All findings must be found without any editor: " + result,
            totalFindings(result) >= EXPECTED_FINDINGS);
        assertEquals(result, String.valueOf(FILE_COUNT), totals(result).group(2));
        assertEquals("Analysis must not leave editors behind", List.of(), openChangedFileNames());
    }

    public void testListedProblemsForClosedFilesCarryNoFixesUnlessRequested() throws Exception {
        configure(false, false);

        String result = run(json("max_results", 4));

        assertEquals(result, 4, problemLines(result).size());
        assertFalse(result, result.contains("Fix:"));
        assertFalse("No note when fixes were not requested: " + result,
            result.contains("Quick-fixes not determined"));
        assertEquals(List.of(), openChangedFileNames());
    }

    public void testIncludeFixesWorksOnClosedFilesEvenWhenOpeningEditorsIsForbidden() throws Exception {
        configure(false, false);

        String result = run(json("max_results", 4, "include_fixes", true));

        assertEquals(result, 4, problemLines(result).size());
        assertTrue("Fixes must be found without any editor: " + result,
            result.contains("Fix: " + REDUNDANT_CAST_FIX));
        assertEquals("Every listed problem must carry its fix", 4, countOccurrences(result, "Fix: " + REDUNDANT_CAST_FIX));
        assertFalse("Nothing to apologize for: " + result, result.contains("Quick-fixes not determined"));
        assertEquals("No editor may be opened", List.of(), openChangedFileNames());
    }

    public void testIncludeFixesNeverOpensEditorsRegardlessOfSettings() throws Exception {
        for (boolean follow : new boolean[]{false, true}) {
            for (boolean transientOpens : new boolean[]{false, true}) {
                configure(follow, transientOpens);

                String result = run(json("max_results", 2, "include_fixes", true));

                String settings = "follow=" + follow + " transient=" + transientOpens + ": " + result;
                assertTrue(settings, result.contains("Fix: " + REDUNDANT_CAST_FIX));
                assertEquals(settings, List.of(), openChangedFileNames());
            }
        }
    }

    public void testFixesAreOnlyComputedForTheReturnedPage() throws Exception {
        configure(false, false);

        String result = run(json("max_results", 3, "include_fixes", true));

        assertEquals(result, 3, problemLines(result).size());
        assertEquals("One fix per listed problem, none for the other findings", 3,
            countOccurrences(result, "Fix: "));
    }

    public void testFixesOnALaterPageAreFoundToo() throws Exception {
        configure(false, false);

        String result = run(json("max_results", 2, "offset", 10, "include_fixes", true));

        assertTrue(result, result.contains("Showing 11-12 of "));
        assertEquals(result, 2, countOccurrences(result, "Fix: " + REDUNDANT_CAST_FIX));
    }

    public void testIncludeFixesIsFastBecauseNothingWaitsForAnEditorDaemon() throws Exception {
        configure(false, true);
        run(json("summary_only", true)); // warm up the analysis infrastructure

        long start = System.nanoTime();
        String result = run(json("max_results", 6, "include_fixes", true));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(result, 6, countOccurrences(result, "Fix: " + REDUNDANT_CAST_FIX));
        // The old implementation waited for an editor daemon event with a 15 s timeout per file.
        assertTrue("include_fixes took " + millis + " ms; it must not wait for editor events", millis < 5_000);
    }

    /**
     * A problem whose fixes are lazy (the import fixes of an unresolved symbol) cannot be listed without an editor.
     * The response must say that fixes may be missing and name a way forward, never imply that none exist, while the
     * problems with eager fixes in the same listing keep theirs.
     */
    public void testLazyFixesAreFlaggedByANoteInTheListing() throws Exception {
        configure(false, true);
        changedFiles.add(myFixture.addFileToProject("Lazy.java",
            "public class Lazy {\n    Missing broken;\n}\n").getVirtualFile());

        String result = run(json("max_results", 100, "include_fixes", true));

        assertTrue("The unresolved symbol must be listed: " + result, result.contains("Cannot resolve symbol 'Missing'"));
        assertTrue("The response must say fixes may be missing: " + result,
            result.contains("Note: " + QualityTool.EDITOR_ONLY_FIXES_NOTE));
        assertEquals("The note appears once per response", 1,
            countOccurrences(result, QualityTool.EDITOR_ONLY_FIXES_NOTE));
        assertEquals("The eager fixes of the other problems are unaffected", EXPECTED_FINDINGS,
            countOccurrences(result, "Fix: " + REDUNDANT_CAST_FIX));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) count++;
        return count;
    }
}

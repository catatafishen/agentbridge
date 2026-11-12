package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end test of {@code get_commit_problems} where every changed file is already open and analyzed, the way
 * it is after the user has worked in them. Verifies the workflow an agent follows: summary, pagination, fix
 * discovery with {@code include_fixes}, then applying a listed fix and seeing the finding disappear.
 *
 * <p>Closed files are covered by {@link GetCommitProblemsToolClosedFilesTest}.</p>
 */
public class GetCommitProblemsToolFixWorkflowTest extends CommitProblemsToolTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // apply_action needs to open editors for the files it works on.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "true");
        // The daemon does not run on its own in headless tests. Open each file in the real FileEditorManager and run
        // the highlighting passes once so that the highlights (and their quick-fixes) are cached on the document,
        // exactly as they are after a user has had the file open. Files that are already open are not re-analyzed
        // by the tool, which keeps the test fast and deterministic.
        for (VirtualFile vf : changedFiles) {
            myFixture.openFileInEditor(vf);
            myFixture.doHighlighting();
        }
    }

    public void testSummaryCountsAllFindingsAcrossAllChangedFiles() throws Exception {
        String result = run(json("summary_only", true));

        Matcher total = totals(result);
        assertTrue(result, Integer.parseInt(total.group(1)) >= EXPECTED_FINDINGS);
        assertEquals(result, String.valueOf(FILE_COUNT), total.group(2));
        assertEquals(result, String.valueOf(FILE_COUNT), total.group(3));
        for (int i = 0; i < FILE_COUNT; i++) {
            assertTrue("File " + i + " missing from the per-file summary: " + result,
                result.contains("Changed" + i + ".java"));
        }
        assertFalse("summary_only must not list individual problems: " + result,
            result.contains(".java:1 ["));
    }

    public void testManyFindingsArePagedWithANextOffsetHint() throws Exception {
        String firstPage = run(json("max_results", 5));
        assertTrue(firstPage, firstPage.contains("Showing 1-5 of "));
        assertTrue(firstPage, firstPage.contains("Next page: offset=5"));
        assertEquals(firstPage, 5, problemLines(firstPage).size());

        String secondPage = run(json("max_results", 5, "offset", 5));
        assertTrue(secondPage, secondPage.contains("Showing 6-10 of "));
        assertEquals(secondPage, 5, problemLines(secondPage).size());
        assertTrue("Pages must not overlap",
            java.util.Collections.disjoint(problemLines(firstPage), problemLines(secondPage)));
    }

    public void testIncludeFixesListsTheRealQuickFixOnlyForTheReturnedPage() throws Exception {
        String result = run(json("max_results", 4, "include_fixes", true));

        assertEquals(result, 4, problemLines(result).size());
        assertTrue("Expected the redundant-cast quick-fix, got: " + result,
            result.contains("Fix: " + REDUNDANT_CAST_FIX));
        assertFalse("Open files need no note about unavailable fixes: " + result,
            result.contains("Quick-fixes not determined"));
    }

    public void testWithoutIncludeFixesNoFixesAreListed() throws Exception {
        String result = run(json("max_results", 4));
        assertFalse(result, result.contains("Fix:"));
    }

    public void testListedFixCanBeAppliedAndTheFindingDisappears() throws Exception {
        int before = totalFindings(run(json("summary_only", true)));
        assertTrue("Need findings to fix", before >= EXPECTED_FINDINGS);

        String listing = run(json("max_results", 1, "include_fixes", true));
        Matcher problem = firstProblem(listing);
        String fileName = problem.group(1);
        int line = Integer.parseInt(problem.group(2));
        String fixName = firstFixName(listing);
        VirtualFile target = fileNamed(fileName);
        Document doc = FileDocumentManager.getInstance().getDocument(target);
        assertNotNull(doc);
        String textBefore = doc.getText();
        String castText = "(" + target.getNameWithoutExtension() + ") x";
        assertTrue("Precondition: cast present", textBefore.contains(castText));

        JsonObject applyArgs = json("line", line);
        applyArgs.addProperty("path", target.getPath());
        applyArgs.addProperty("action_name", fixName);
        applyArgs.addProperty("symbol", "(" + target.getNameWithoutExtension() + ")");
        String applied = executeSync(() -> applyActionTool.execute(applyArgs));

        assertFalse("apply_action failed: " + applied, applied.startsWith("Error"));
        String textAfter = doc.getText();
        assertFalse("The quick-fix must change the file", textBefore.equals(textAfter));
        assertEquals("Exactly one cast must be removed",
            countOccurrences(textBefore, castText) - 1, countOccurrences(textAfter, castText));

        int after = totalFindings(run(json("summary_only", true)));
        assertEquals("One finding must be gone after applying its fix", before - 1, after);
    }

    public void testErrorsOnlyReportsNothingWhenThereAreOnlyWarnings() throws Exception {
        String result = run(json("errors_only", true));
        assertEquals("No errors found in " + FILE_COUNT + " changed file(s).", result);
    }

    private static int countOccurrences(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }
}

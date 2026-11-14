package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.regex.Pattern;

/**
 * {@code apply_action} for a file that is NOT open in an editor, which is the normal case for an agent that just
 * read the findings with {@code get_commit_problems} or {@code get_highlights}. Those tools analyze closed files
 * on demand; {@code apply_action} must find the very same quick-fix by name. Before it did, it read the editor
 * daemon's cache, which is empty for a closed file, and answered "Action 'Remove redundant cast' not found ...
 * Available: [Introduce local variable]".
 */
public class ApplyActionClosedFileTest extends CommitProblemsToolTestCase {

    private void configure(boolean followAgentFiles, boolean allowTransientOpens) {
        PropertiesComponent props = PropertiesComponent.getInstance(getProject());
        props.setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, String.valueOf(followAgentFiles));
        props.setValue(ToolLayerSettings.ALLOW_TRANSIENT_FILE_OPENS_KEY, String.valueOf(allowTransientOpens));
    }

    private String applyFix(VirtualFile vf, int line, String actionName) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("line", line);
        args.addProperty("action_name", actionName);
        args.addProperty("symbol", "(" + vf.getNameWithoutExtension() + ")");
        return executeSync(() -> applyActionTool.execute(args));
    }

    private static int casts(VirtualFile vf) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        assertNotNull(doc);
        String cast = "(" + vf.getNameWithoutExtension() + ") x";
        return doc.getText().split(Pattern.quote(cast), -1).length - 1;
    }

    private boolean isOpen(VirtualFile vf) {
        return FileEditorManager.getInstance(getProject()).isFileOpen(vf);
    }

    public void testFixListedForAClosedFileCanBeApplied() throws Exception {
        configure(false, true);
        VirtualFile vf = changedFiles.get(0);
        // The workflow the agent follows: list the findings and fixes, then apply a listed fix.
        String listing = run(json("max_results", 1, "include_fixes", true));
        assertTrue(listing, listing.contains("Fix: " + REDUNDANT_CAST_FIX));
        assertFalse("Precondition: file is closed", isOpen(vf));
        int before = casts(vf);

        String result = applyFix(vf, 2, REDUNDANT_CAST_FIX);

        assertFalse("apply_action must find the fix that was listed: " + result, result.startsWith("Error"));
        assertFalse(result, result.contains("not found"));
        assertEquals("Exactly one cast must be removed", before - 1, casts(vf));
    }

    public void testClosedFileIsClosedAgainAfterApplyingWhenNotFollowingAgentFiles() throws Exception {
        configure(false, true);
        VirtualFile vf = changedFiles.get(1);

        applyFix(vf, 2, REDUNDANT_CAST_FIX);

        assertFalse("No editor tab may be left behind", isOpen(vf));
    }

    public void testEveryFindingOfAClosedFileCanBeFixedOneAfterTheOther() throws Exception {
        configure(false, true);
        VirtualFile vf = changedFiles.get(2);
        int initial = casts(vf);
        assertEquals(CASTS_PER_FILE, initial);

        for (int line = 2; line < 2 + CASTS_PER_FILE; line++) {
            String result = applyFix(vf, line, REDUNDANT_CAST_FIX);
            assertFalse("line " + line + ": " + result, result.startsWith("Error") || result.contains("not found"));
        }

        assertEquals("All casts must be gone", 0, casts(vf));
        String after = run(json("summary_only", true));
        assertEquals("The fixed file must no longer be reported", FILE_COUNT - 1,
            Integer.parseInt(totals(after).group(2)));
    }

    public void testApplyingDoesNotWaitForAnEditorDaemon() throws Exception {
        configure(false, true);
        applyFix(changedFiles.get(3), 2, REDUNDANT_CAST_FIX); // warm up

        long start = System.nanoTime();
        String result = applyFix(changedFiles.get(4), 2, REDUNDANT_CAST_FIX);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result, result.startsWith("Error") || result.contains("not found"));
        assertTrue("apply_action took " + millis + " ms on a closed file", millis < 5_000);
    }

    public void testAMissingActionOnAClosedFileListsTheRealQuickFixesNotJustIntentions() throws Exception {
        configure(false, true);
        VirtualFile vf = changedFiles.get(5);

        String result = applyFix(vf, 2, "No such action");

        assertTrue(result, result.contains("'No such action' not found"));
        assertTrue("The available quick-fix must be offered, not only generic intentions: " + result,
            result.contains(REDUNDANT_CAST_FIX));
        assertEquals("Nothing may be changed", CASTS_PER_FILE, casts(vf));
    }

    /**
     * A fix that exists but is lazy ("Import class" for an unresolved symbol) is not computed without an editor.
     * The answer must say so instead of only listing unrelated intentions, otherwise the agent concludes that no such
     * fix exists.
     */
    public void testLazyFixOnAClosedFileIsExplainedInsteadOfJustNotFound() throws Exception {
        configure(false, true);
        VirtualFile vf = myFixture.addFileToProject("Lazy.java",
            "public class Lazy {\n    Missing broken;\n}\n").getVirtualFile();
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("line", 2);
        args.addProperty("action_name", "Import class");
        args.addProperty("symbol", "Missing");

        String result = executeSync(() -> applyActionTool.execute(args));

        assertTrue(result, result.contains("'Import class' not found"));
        assertTrue("The reason must be given: " + result, result.contains(QualityTool.EDITOR_ONLY_FIXES_NOTE));
        assertTrue("A way forward must be named: " + result, result.contains("optimize_imports"));
        assertFalse("No editor may be left behind", isOpen(vf));
    }

    public void testNoLazyFixNoteWhenTheMissingActionIsSimplyUnknown() throws Exception {
        configure(false, true);

        String result = applyFix(changedFiles.get(5), 2, "No such action");

        assertTrue(result, result.contains("'No such action' not found"));
        assertFalse("Every problem here has its fix listed, so no note: " + result,
            result.contains(QualityTool.EDITOR_ONLY_FIXES_NOTE));
    }

    public void testNoLazyFixNoteForAnOpenFile() throws Exception {
        configure(true, true);
        VirtualFile vf = myFixture.addFileToProject("LazyOpen.java",
            "public class LazyOpen {\n    Missing broken;\n}\n").getVirtualFile();
        myFixture.openFileInEditor(vf);
        myFixture.doHighlighting();
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("line", 2);
        args.addProperty("action_name", "No such action");
        args.addProperty("symbol", "Missing");

        String result = executeSync(() -> applyActionTool.execute(args));

        assertTrue(result, result.contains("'No such action' not found"));
        assertFalse("The editor computes lazy fixes itself, so there is nothing to explain: " + result,
            result.contains("were not computed"));
    }

    public void testFileThatIsAlreadyOpenStillUsesTheEditorCache() throws Exception {
        configure(true, true);
        VirtualFile vf = changedFiles.get(0);
        myFixture.openFileInEditor(vf);
        myFixture.doHighlighting();
        int before = casts(vf);

        String result = applyFix(vf, 2, REDUNDANT_CAST_FIX);

        assertFalse(result, result.startsWith("Error") || result.contains("not found"));
        assertEquals(before - 1, casts(vf));
        assertTrue("A file the user had open must stay open", isOpen(vf));
    }

    public void testWhenOpeningEditorsIsForbiddenTheToolSaysSoInsteadOfFailingObscurely() throws Exception {
        configure(false, false);
        VirtualFile vf = changedFiles.get(0);
        int before = casts(vf);

        String result = applyFix(vf, 2, REDUNDANT_CAST_FIX);

        assertTrue(result, result.startsWith("Error: This tool needs to temporarily open the file"));
        assertEquals("Nothing may be changed", before, casts(vf));
        assertFalse(isOpen(vf));
    }
}

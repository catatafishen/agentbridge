package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * {@code get_available_actions} for a file that is NOT open in an editor. The editor daemon only analyzes open
 * files, so the tool used to answer "No highlights found ... The daemon may not have analyzed this file yet" for a
 * closed file, although {@code get_commit_problems} had just listed problems and fixes for it. It must analyze the
 * file on demand and say so when a fix exists but cannot be computed without an editor.
 */
public class GetAvailableActionsClosedFileTest extends CommitProblemsToolTestCase {

    private GetAvailableActionsTool actionsTool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        actionsTool = new GetAvailableActionsTool(getProject());
        configure(false, true);
    }

    private void configure(boolean followAgentFiles, boolean allowTransientOpens) {
        PropertiesComponent props = PropertiesComponent.getInstance(getProject());
        props.setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, String.valueOf(followAgentFiles));
        props.setValue(ToolLayerSettings.ALLOW_TRANSIENT_FILE_OPENS_KEY, String.valueOf(allowTransientOpens));
    }

    private String available(VirtualFile vf, int line, String symbol) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("line", line);
        if (symbol != null) args.addProperty("symbol", symbol);
        return executeSync(() -> actionsTool.execute(args));
    }

    private boolean isOpen(VirtualFile vf) {
        return FileEditorManager.getInstance(getProject()).isFileOpen(vf);
    }

    private VirtualFile unresolvedSymbolFile(String name) {
        return myFixture.addFileToProject(name + ".java",
            "public class " + name + " {\n    Missing broken;\n}\n").getVirtualFile();
    }

    public void testQuickFixOfAClosedFileIsListedWithoutAnEditor() throws Exception {
        VirtualFile vf = changedFiles.get(0);

        String result = available(vf, 2, null);

        assertTrue("The redundant-cast fix must be found: " + result, result.contains("Fix: " + REDUNDANT_CAST_FIX));
        assertTrue(result, result.contains("Copy the action name exactly as shown"));
        assertFalse("The old, wrong advice must not be given for a file that was analyzed: " + result,
            result.contains("The daemon may not have analyzed this file yet"));
        assertFalse("No editor may be opened for the quick-fix-only path", isOpen(vf));
    }

    public void testClosedFileListsTheSameFixesAsGetHighlights() throws Exception {
        VirtualFile vf = changedFiles.get(1);
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("include_fixes", true);
        String highlights = executeSync(() -> new GetHighlightsTool(getProject()).execute(args));

        String result = available(vf, 2, null);

        assertTrue(highlights, highlights.contains("Fix: " + REDUNDANT_CAST_FIX));
        assertTrue(result, result.contains("Fix: " + REDUNDANT_CAST_FIX));
    }

    public void testQuickFixesAreListedWhenOpeningEditorsIsForbiddenBecauseNoEditorIsNeeded() throws Exception {
        configure(false, false);
        VirtualFile vf = changedFiles.get(2);

        String result = available(vf, 2, null);

        assertTrue(result, result.contains("Fix: " + REDUNDANT_CAST_FIX));
        assertFalse(isOpen(vf));
    }

    public void testLineWithoutProblemsSaysTheFileWasAnalyzedNotThatTheDaemonIsMissing() throws Exception {
        VirtualFile vf = changedFiles.get(3);

        String result = available(vf, 1, null);

        assertTrue(result, result.startsWith("No problems with quick-fixes found"));
        assertTrue("It must say that the analysis did run: " + result, result.contains("analyzed on demand"));
        assertFalse(result, result.contains("The daemon may not have analyzed"));
    }

    public void testLazyFixOnAClosedFileIsExplainedInsteadOfBeingOmitted() throws Exception {
        VirtualFile vf = unresolvedSymbolFile("LazyNoSymbol");

        String result = available(vf, 2, null);

        assertTrue("The problem must be listed: " + result, result.contains("Cannot resolve symbol 'Missing'"));
        assertTrue("Its fixes must be marked as not computed: " + result,
            result.contains("Fix: " + QualityTool.FIXES_NOT_COMPUTED));
        assertTrue("The reason must be given: " + result,
            result.contains("quick-fixes that were not computed because the file is not open in an editor"));
        assertTrue("A way forward must be named: " + result, result.contains("optimize_imports"));
        assertFalse("A placeholder is not an action name to copy: " + result,
            result.contains("Copy the action name exactly as shown (after 'Fix:')"));
        assertFalse(isOpen(vf));
    }

    public void testLazyFixIsExplainedOnTheSymbolPathToo() throws Exception {
        VirtualFile vf = unresolvedSymbolFile("LazyWithSymbol");

        String result = available(vf, 2, "Missing");

        assertTrue(result, result.startsWith("Actions at "));
        assertTrue("The reason must be given on the intention path as well: " + result,
            result.contains("quick-fixes that were not computed because the file is not open in an editor"));
        assertFalse("No editor tab may be left behind", isOpen(vf));
    }

    public void testQuickFixesAreFoundOnTheSymbolPathForAClosedFile() throws Exception {
        VirtualFile vf = changedFiles.get(4);

        String result = available(vf, 2, "(" + vf.getNameWithoutExtension() + ")");

        assertTrue("The quick-fix section must list the redundant-cast fix: " + result,
            result.contains("[QUICK FIX]") && result.contains(REDUNDANT_CAST_FIX));
        assertFalse("No note when nothing is pending: " + result, result.contains("were not computed"));
        assertFalse(isOpen(vf));
    }

    public void testSymbolPathStillReportsTheForbiddenEditorOpenClearly() throws Exception {
        configure(false, false);
        VirtualFile vf = changedFiles.get(5);

        String result = available(vf, 2, "(" + vf.getNameWithoutExtension() + ")");

        assertTrue(result, result.startsWith("Error: This tool needs to temporarily open the file"));
        assertFalse(isOpen(vf));
    }

    public void testOpenFileUsesTheEditorCacheAndNeedsNoExplanation() throws Exception {
        VirtualFile vf = unresolvedSymbolFile("LazyOpen");
        myFixture.openFileInEditor(vf);
        myFixture.doHighlighting();

        String result = available(vf, 2, null);

        assertTrue(result, result.contains("Fix: Import class"));
        assertFalse("The editor computes lazy fixes itself: " + result, result.contains("were not computed"));
        assertTrue("A file the user had open must stay open", isOpen(vf));
    }

    public void testUnknownFileStillReportsAnError() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("path", "/does/not/exist/Nope.java");
        args.addProperty("line", 1);

        String result = executeSync(() -> actionsTool.execute(args));

        assertTrue(result, result.startsWith("Error: File not found"));
    }
}

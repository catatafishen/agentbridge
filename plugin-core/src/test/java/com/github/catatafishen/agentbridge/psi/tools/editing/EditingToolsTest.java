package com.github.catatafishen.agentbridge.psi.tools.editing;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Platform tests for {@link ReplaceSymbolBodyTool}, {@link InsertAfterSymbolTool},
 * and {@link InsertBeforeSymbolTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>Threading:</b> all three editing tools use {@code EdtUtil.invokeLater}
 * internally and complete a {@code CompletableFuture} on the EDT. Because
 * {@code BasePlatformTestCase} methods already run on the EDT, calling
 * {@code execute()} directly would deadlock. {@link #executeSync} runs the tool
 * on a pooled thread while pumping the EDT event queue, mirroring the pattern
 * from {@code WriteFileToolTest}.
 *
 * <p><b>File creation:</b> {@code myFixture.addFileToProject()} creates in-memory
 * (TempFS) files that are invisible to {@code LocalFileSystem#findFileByPath}. The
 * editing tools call {@code resolveVirtualFile} which uses that API. Tests therefore
 * create real disk files under a temp directory and register them in the VFS via
 * {@code LocalFileSystem#refreshAndFindFileByPath}, following the same pattern as
 * {@code EditTextToolTest}.
 *
 * <p><b>File names:</b> each test method uses a unique temp file name to avoid any
 * caching collisions.
 */
public class EditingToolsTest extends BasePlatformTestCase {

    private ReplaceSymbolBodyTool replaceSymbolBodyTool;
    private InsertAfterSymbolTool insertAfterSymbolTool;
    private InsertBeforeSymbolTool insertBeforeSymbolTool;
    private Path tempDir;

    /**
     * Java source with a single {@code hello()} method — no package needed for temp files.
     */
    private static final String SIMPLE_CLASS_TEMPLATE = """
        public class %s {
            public String hello() {
                return "world";
            }
        }
        """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Disable follow-agent UI (editor navigation, status-bar feedback)
        // to avoid spurious editor-lifecycle failures in headless tests.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        replaceSymbolBodyTool = new ReplaceSymbolBodyTool(getProject());
        insertAfterSymbolTool = new InsertAfterSymbolTool(getProject());
        insertBeforeSymbolTool = new InsertBeforeSymbolTool(getProject());
        tempDir = Files.createTempDirectory("editing-tools-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Close any editors opened by the tool to prevent DisposalException.
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
            // Remove temp files created during this test.
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a real file on disk and registers it in the VFS so that
     * {@code LocalFileSystem#findFileByPath} can find it during {@code execute()}.
     *
     * <p>This is required because the editing tools call {@code resolveVirtualFile},
     * which uses {@code LocalFileSystem#findFileByPath} and cannot see TempFS files
     * created by {@code myFixture.addFileToProject()}.
     */
    private String createTestFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString());
        assertNotNull("Failed to register test file in VFS: " + file, vf);
        return file.toString();
    }

    /**
     * Builds a {@link JsonObject} from alternating key/value String pairs.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs {@code tool.execute(argsObj)} on a pooled background thread while
     * pumping the EDT event queue. Required because the editing tools dispatch
     * write-actions back to the EDT via {@code EdtUtil.invokeLater}. Blocking
     * the EDT directly would deadlock; this pattern avoids that cycle.
     */
    private String executeSync(Tool tool, JsonObject argsObj) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(tool.execute(argsObj));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        long deadline = System.currentTimeMillis() + 15_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            if (System.currentTimeMillis() > deadline) {
                fail("tool.execute() timed out after 15 seconds");
            }
        }
        return future.get();
    }

    // ── ReplaceSymbolBodyTool ─────────────────────────────────────────────────

    /**
     * Replacing the body of an existing {@code hello()} method must succeed and
     * return a response that starts with {@code "Replaced lines"}, contains the
     * file path, and ends with the formatted-imports suffix.
     */
    public void testReplaceSymbolBodySuccess() throws Exception {
        String path = createTestFile("ReplaceSuccess.java",
            String.format(SIMPLE_CLASS_TEMPLATE, "ReplaceSuccess"));

        String result = executeSync(replaceSymbolBodyTool, args(
            "file", path,
            "symbol", "hello",
            "new_body", """
                    public String hello() {
                        return "updated";
                    }
                """
        ));

        assertFalse("Expected success (no error prefix), got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertFalse("Expected success (not symbol-not-found), got: " + result,
            result.startsWith(EditingTool.SYMBOL_PREFIX));
        assertTrue("Expected 'Replaced lines' prefix, got: " + result,
            result.startsWith("Replaced lines"));
        assertTrue("Expected file path in result, got: " + result,
            result.contains(path));
        assertTrue("Expected formatted-imports suffix, got: " + result,
            result.contains("formatted & imports queued"));
    }

    /**
     * When the named symbol does not exist in the file, the tool must return a
     * message beginning with {@code "Symbol '"} that names the missing symbol.
     * The file IS present on disk so this exercises the actual symbol-search
     * code path (not the file-not-found fallback).
     */
    public void testReplaceSymbolBodySymbolNotFound() throws Exception {
        String path = createTestFile("ReplaceNotFound.java",
            String.format(SIMPLE_CLASS_TEMPLATE, "ReplaceNotFound"));

        String result = executeSync(replaceSymbolBodyTool, args(
            "file", path,
            "symbol", "nonExistentMethod_XYZ_Replace",
            "new_body", "public void nonExistentMethod_XYZ_Replace() {}\n"
        ));

        assertTrue("Expected symbol-not-found message, got: " + result,
            result.startsWith(EditingTool.SYMBOL_PREFIX));
        assertTrue("Expected missing symbol name in message, got: " + result,
            result.contains("nonExistentMethod_XYZ_Replace"));
    }

    /**
     * When the required {@code symbol} argument is omitted, the tool must
     * return the standard {@code "Missing required parameter: symbol"} error
     * immediately (before any async dispatch).
     */
    public void testReplaceSymbolBodyMissingSymbol() throws Exception {
        String path = createTestFile("ReplaceMissingSymbol.java",
            String.format(SIMPLE_CLASS_TEMPLATE, "ReplaceMissingSymbol"));

        JsonObject a = new JsonObject();
        a.addProperty("file", path);
        a.addProperty("new_body", "public void foo() {}\n");
        // "symbol" intentionally omitted

        String result = executeSync(replaceSymbolBodyTool, a);

        assertEquals("Missing required parameter: symbol", result);
    }

    // ── InsertAfterSymbolTool ─────────────────────────────────────────────────

    /**
     * Inserting content after an existing method must succeed and return a
     * response that starts with {@code "Inserted"}, contains {@code "after"},
     * and contains the file path.
     */
    public void testInsertAfterSymbolSuccess() throws Exception {
        String path = createTestFile("InsertAfterSuccess.java",
            String.format(SIMPLE_CLASS_TEMPLATE, "InsertAfterSuccess"));

        String result = executeSync(insertAfterSymbolTool, args(
            "file", path,
            "symbol", "hello",
            "content", """
                    public String goodbye() {
                        return "bye";
                    }
                """
        ));

        assertFalse("Expected success (no error prefix), got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertFalse("Expected success (not symbol-not-found), got: " + result,
            result.startsWith(EditingTool.SYMBOL_PREFIX));
        assertTrue("Expected 'Inserted' prefix, got: " + result,
            result.startsWith("Inserted"));
        assertTrue("Expected 'after' in result, got: " + result,
            result.contains("after"));
        assertTrue("Expected file path in result, got: " + result,
            result.contains(path));
    }

    /**
     * When the named symbol does not exist in the file, the tool must return a
     * message beginning with {@code "Symbol '"} that names the missing symbol.
     */
    public void testInsertAfterSymbolNotFound() throws Exception {
        String path = createTestFile("InsertAfterNotFound.java",
            String.format(SIMPLE_CLASS_TEMPLATE, "InsertAfterNotFound"));

        String result = executeSync(insertAfterSymbolTool, args(
            "file", path,
            "symbol", "nonExistentSymbol_ABC_After",
            "content", "    public void extra() {}\n"
        ));

        assertTrue("Expected symbol-not-found message, got: " + result,
            result.startsWith(EditingTool.SYMBOL_PREFIX));
        assertTrue("Expected missing symbol name in message, got: " + result,
            result.contains("nonExistentSymbol_ABC_After"));
    }

    // ── InsertBeforeSymbolTool ────────────────────────────────────────────────

    /**
     * Inserting content before an existing method must succeed and return a
     * response that starts with {@code "Inserted"}, contains {@code "before"},
     * and contains the file path.
     */
    public void testInsertBeforeSymbolSuccess() throws Exception {
        String path = createTestFile("InsertBeforeSuccess.java",
            String.format(SIMPLE_CLASS_TEMPLATE, "InsertBeforeSuccess"));

        String result = executeSync(insertBeforeSymbolTool, args(
            "file", path,
            "symbol", "hello",
            "content", "    // inserted comment\n"
        ));

        assertFalse("Expected success (no error prefix), got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertFalse("Expected success (not symbol-not-found), got: " + result,
            result.startsWith(EditingTool.SYMBOL_PREFIX));
        assertTrue("Expected 'Inserted' prefix, got: " + result,
            result.startsWith("Inserted"));
        assertTrue("Expected 'before' in result, got: " + result,
            result.contains("before"));
        assertTrue("Expected file path in result, got: " + result,
            result.contains(path));
    }

    /**
     * When the named symbol does not exist in the file, the tool must return a
     * message beginning with {@code "Symbol '"} that names the missing symbol.
     */
    public void testInsertBeforeSymbolNotFound() throws Exception {
        String path = createTestFile("InsertBeforeNotFound.java",
            String.format(SIMPLE_CLASS_TEMPLATE, "InsertBeforeNotFound"));

        String result = executeSync(insertBeforeSymbolTool, args(
            "file", path,
            "symbol", "nonExistentSymbol_DEF_Before",
            "content", "    // comment\n"
        ));

        assertTrue("Expected symbol-not-found message, got: " + result,
            result.startsWith(EditingTool.SYMBOL_PREFIX));
        assertTrue("Expected missing symbol name in message, got: " + result,
            result.contains("nonExistentSymbol_DEF_Before"));
    }
}

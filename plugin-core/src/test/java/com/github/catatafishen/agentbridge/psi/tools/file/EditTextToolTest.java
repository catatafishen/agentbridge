package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
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
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Platform tests for {@link EditTextTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be {@code public void testXxx()}.
 * Run via Gradle only: {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>File creation note:</b> {@code myFixture.addFileToProject()} creates in-memory VFS files
 * that are invisible to {@code LocalFileSystem#findFileByPath}. Tests use real disk files
 * created under a temp directory, registered in the VFS via
 * {@code LocalFileSystem#refreshAndFindFileByPath}.
 *
 * <p><b>Editor lifecycle note:</b> EditTextTool (a subclass of WriteFileTool) may open editors
 * when {@code followFileIfEnabled} is active. We disable that setting in setUp and close all
 * editors in tearDown to prevent "Editor hasn't been released" errors.
 */
public class EditTextToolTest extends BasePlatformTestCase {

    private EditTextTool tool;
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening Editors during tests.
        // Use the String overload — the boolean overload removes the key when value==defaultValue,
        // which would leave the setting at its default (true) instead of setting it to false.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new EditTextTool(getProject());
        tempDir = Files.createTempDirectory("edit-text-tool-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Close any editors the tool may have opened to prevent DisposalException.
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
            deleteDir(tempDir);
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Creates a real file on disk and registers it in the VFS so that
     * {@code LocalFileSystem#findFileByPath} can find it during {@code execute()}.
     */
    private VirtualFile createTestFile(String name, String content) {
        try {
            Path file = tempDir.resolve(name);
            Files.writeString(file, content);
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString());
            assertNotNull("Failed to register test file in VFS: " + file, vf);
            return vf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test file: " + name, e);
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Builds a JsonObject from alternating String key/value pairs.
     * Example: {@code args("path", "/tmp/f.txt", "old_str", "foo", "new_str", "bar")}
     */
    private JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs {@code tool.execute(argsObj)} on a pooled background thread while pumping
     * the EDT event queue on the calling thread. This is required because
     * {@code BasePlatformTestCase} methods run on the EDT, and EditTextTool (a subclass
     * of WriteFileTool) uses {@code EdtUtil.invokeLater} to schedule write-actions back
     * onto the EDT. Blocking the EDT directly would deadlock; running execute() off-EDT
     * and pumping the queue resolves that cycle.
     */
    private String executeSync(JsonObject argsObj) throws Exception {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
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

    // ── Tests ─────────────────────────────────────────────────────────────────────

    /**
     * Passing an explicit JSON null for "path" should return the standard error constant.
     */
    public void testNullPathReturnsError() throws Exception {
        JsonObject a = new JsonObject();
        a.add("path", null);
        String result = executeSync(a);
        assertEquals(ToolUtils.ERROR_PATH_REQUIRED, result);
    }

    /**
     * When {@code old_str} is not present in the file the response must contain
     * "old_str not found in".
     */
    public void testOldStrNotFoundReturnsError() throws Exception {
        VirtualFile vf = createTestFile("notfound.txt", "hello world");

        String result = executeSync(
            args("path", vf.getPath(), "old_str", "nonexistent text", "new_str", "replacement"));

        assertTrue("Expected 'old_str not found in' error, got: " + result,
            result.contains("old_str not found in"));
    }

    /**
     * A successful find-and-replace must return a response starting with "Edited:".
     */
    public void testSuccessfulEditReturnsEditedMessage() throws Exception {
        VirtualFile vf = createTestFile("edit.txt", "hello world");

        String result = executeSync(
            args("path", vf.getPath(), "old_str", "hello", "new_str", "goodbye"));

        assertTrue("Expected 'Edited:' prefix, got: " + result, result.startsWith("Edited:"));
    }

    /**
     * After a successful edit the file on disk must reflect the replacement.
     */
    public void testSuccessfulEditUpdatesFileContent() throws Exception {
        VirtualFile vf = createTestFile("update.txt", "The quick brown fox");

        executeSync(args("path", vf.getPath(), "old_str", "quick brown", "new_str", "slow red"));

        // Read from disk directly — bypasses any stale VFS cache
        String actual = Files.readString(Path.of(vf.getPath()));
        assertEquals("The slow red fox", actual);
    }

    /**
     * When {@code old_str} appears more than once without {@code replace_all: true}
     * the response must contain "old_str matches multiple locations in".
     */
    public void testMultipleMatchesReturnsError() throws Exception {
        VirtualFile vf = createTestFile("multi.txt", "foo bar foo bar");

        String result = executeSync(
            args("path", vf.getPath(), "old_str", "foo", "new_str", "baz"));

        assertTrue("Expected multiple-matches error, got: " + result,
            result.contains("old_str matches multiple locations in"));
    }

    /**
     * With {@code replace_all: true} every occurrence of {@code old_str} must be replaced
     * and the response must start with "Edited:".
     */
    public void testReplaceAllReplacesAllOccurrences() throws Exception {
        VirtualFile vf = createTestFile("replaceall.txt", "cat and cat and cat");

        JsonObject a = new JsonObject();
        a.addProperty("path", vf.getPath());
        a.addProperty("old_str", "cat");
        a.addProperty("new_str", "dog");
        a.addProperty("replace_all", true);
        String result = executeSync(a);

        assertTrue("Expected 'Edited:' prefix, got: " + result, result.startsWith("Edited:"));
        String actual = Files.readString(Path.of(vf.getPath()));
        assertEquals("dog and dog and dog", actual);
    }

    /**
     * With {@code case_sensitive: false} the match must be case-insensitive while the
     * replacement is inserted exactly as supplied. The response must start with "Edited:".
     */
    public void testCaseInsensitiveMatch() throws Exception {
        VirtualFile vf = createTestFile("case.txt", "Hello World");

        JsonObject a = new JsonObject();
        a.addProperty("path", vf.getPath());
        a.addProperty("old_str", "hello world");
        a.addProperty("new_str", "Goodbye");
        a.addProperty("case_sensitive", false);
        String result = executeSync(a);

        assertTrue("Expected 'Edited:' prefix for case-insensitive match, got: " + result,
            result.startsWith("Edited:"));
        String actual = Files.readString(Path.of(vf.getPath()));
        assertEquals("Goodbye", actual);
    }
}

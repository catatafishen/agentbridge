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
 * Platform tests for {@link WriteFileTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be {@code public void testXxx()}.
 * Run via Gradle only: {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>File creation note:</b> {@code myFixture.addFileToProject()} creates in-memory VFS files
 * that are invisible to {@code LocalFileSystem#findFileByPath}. Tests use real disk files
 * created under a temp directory, registered in the VFS via
 * {@code LocalFileSystem#refreshAndFindFileByPath}.
 *
 * <p><b>Editor lifecycle note:</b> WriteFileTool may open editors when
 * {@code followFileIfEnabled} is active. We disable that setting in setUp and
 * close all editors in tearDown to prevent "Editor hasn't been released" errors.
 */
public class WriteFileToolTest extends BasePlatformTestCase {

    private WriteFileTool tool;
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening Editors during tests.
        // Use the String overload — the boolean overload removes the key when value==defaultValue,
        // which would leave the setting at its default (true) instead of setting it to false.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new WriteFileTool(getProject());
        tempDir = Files.createTempDirectory("write-file-tool-test");
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
     * Builds a JsonObject from alternating key/value pairs.
     * Example: {@code args("path", "/tmp/f.txt", "content", "hello")}
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
     * {@code BasePlatformTestCase} methods run on the EDT, and WriteFileTool uses
     * {@code EdtUtil.invokeLater} to schedule write-actions back onto the EDT.
     * Blocking the EDT directly would deadlock; running execute() off-EDT and pumping
     * the queue resolves that cycle.
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
     * Passing an explicit JSON null for "path" should return the standard error.
     */
    public void testNullPathReturnsError() throws Exception {
        JsonObject a = new JsonObject();
        a.add("path", null);
        String result = executeSync(a);
        assertEquals(ToolUtils.ERROR_PATH_REQUIRED, result);
    }

    /**
     * Omitting the "path" key entirely should return the standard error.
     */
    public void testMissingPathReturnsError() throws Exception {
        String result = executeSync(new JsonObject());
        assertEquals(ToolUtils.ERROR_PATH_REQUIRED, result);
    }

    /**
     * Writing to a path that does not yet exist should create the file
     * and return a response that starts with "Created:".
     * <p>
     * New-file creation uses direct disk I/O + {@code WriteAction.run()} (no
     * {@code EdtUtil.invokeLater}), so it is safe to call {@code execute()} directly
     * from the EDT test thread.
     */
    public void testWriteNewFile() throws Exception {
        Path newFile = tempDir.resolve("newfile.txt");
        assertFalse("File must not exist before the test", Files.exists(newFile));

        String result = tool.execute(args("path", newFile.toString(), "content", "hello world"));

        assertTrue("Expected 'Created:' prefix, got: " + result, result.startsWith("Created:"));
    }

    /**
     * After creating a new file the content on disk must match what was passed.
     * <p>
     * New-file creation uses direct disk I/O + {@code WriteAction.run()} (no
     * {@code EdtUtil.invokeLater}), so it is safe to call {@code execute()} directly
     * from the EDT test thread.
     */
    public void testWriteNewFileCreatesContent() throws Exception {
        Path newFile = tempDir.resolve("created.txt");
        String expected = "line1\nline2\nline3";

        tool.execute(args("path", newFile.toString(), "content", expected));

        assertTrue("File should exist on disk after creation", Files.exists(newFile));
        assertEquals(expected, Files.readString(newFile));
    }

    /**
     * Writing to an already-existing file should return a response that starts with "Written:".
     */
    public void testWriteExistingFile() throws Exception {
        VirtualFile vf = createTestFile("existing.txt", "original content");

        String result = executeSync(args("path", vf.getPath(), "content", "updated content"));

        assertTrue("Expected 'Written:' prefix, got: " + result, result.startsWith("Written:"));
    }

    /**
     * After writing to an existing file, the content on disk must match the new content.
     */
    public void testWriteExistingFileUpdatesContent() throws Exception {
        VirtualFile vf = createTestFile("update.txt", "old content");
        String newContent = "brand new content";

        executeSync(args("path", vf.getPath(), "content", newContent));

        // Read from disk directly — bypasses any stale VFS cache
        String actual = Files.readString(Path.of(vf.getPath()));
        assertEquals(newContent, actual);
    }

    /**
     * Writing an empty string to an existing file should succeed and the response
     * should start with "Written:".
     */
    public void testWriteEmptyContent() throws Exception {
        VirtualFile vf = createTestFile("empty.txt", "some content");

        String result = executeSync(args("path", vf.getPath(), "content", ""));

        assertTrue("Expected 'Written:' prefix for empty content, got: " + result,
            result.startsWith("Written:"));
        String actual = Files.readString(Path.of(vf.getPath()));
        assertEquals("", actual);
    }

    /**
     * Writing multi-line content should succeed and produce the correct file on disk.
     */
    public void testWriteMultilineContent() throws Exception {
        VirtualFile vf = createTestFile("multiline.txt", "initial");
        String multiline = "line one\nline two\nline three\nline four";

        String result = executeSync(args("path", vf.getPath(), "content", multiline));

        assertTrue("Expected 'Written:' prefix, got: " + result, result.startsWith("Written:"));
        String actual = Files.readString(Path.of(vf.getPath()));
        assertEquals(multiline, actual);
    }
}

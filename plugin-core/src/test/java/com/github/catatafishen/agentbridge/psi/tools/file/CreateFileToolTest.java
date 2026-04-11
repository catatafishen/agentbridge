package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Platform tests for {@link CreateFileTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be {@code public void testXxx()}.
 * Run via Gradle only: {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>Async execution note:</b> {@code CreateFileTool} uses {@code EdtUtil.invokeLater} to perform
 * the VFS refresh after writing to disk, then blocks on a {@code CompletableFuture}. Calling
 * {@code execute()} directly from the EDT test thread would deadlock (EDT is blocked by
 * {@code future.get()}, so the {@code invokeLater} callback can never run). The {@code executeSync}
 * helper runs {@code execute()} on a pooled thread and pumps the EDT queue until the future
 * resolves, breaking the cycle.
 *
 * <p><b>Exception:</b> Error paths that return before reaching {@code invokeLater} (e.g. the
 * "file already exists" guard) are safe to call directly from the EDT test thread; they are
 * marked with an explicit comment explaining the synchronous return.
 */
public class CreateFileToolTest extends BasePlatformTestCase {

    private CreateFileTool tool;
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening editors during tests — the default is true
        // and editors opened by the tool would not be released, causing DisposalException in tearDown.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new CreateFileTool(getProject());
        tempDir = Files.createTempDirectory("create-file-tool-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
            deleteDir(tempDir);
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
     * Builds a {@link JsonObject} from alternating key/value string pairs.
     * Example: {@code args("path", "/tmp/f.txt", "content", "hello")}
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs {@code tool.execute(argsObj)} on a pooled background thread while pumping the EDT
     * event queue on the calling thread. This is required because {@code CreateFileTool} uses
     * {@code EdtUtil.invokeLater} to schedule VFS refresh and result completion back onto the EDT.
     * Blocking the EDT directly would deadlock; running execute() off-EDT and pumping the queue
     * resolves that cycle.
     */
    private String executeSync(JsonObject argsObj) throws Exception {
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

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Creating a file at a new path should return a response that starts with "✓ Created file:".
     */
    public void testCreateNewFile() throws Exception {
        Path newFile = tempDir.resolve("newfile.txt");
        assertFalse("File must not exist before the test", Files.exists(newFile));

        String result = executeSync(args("path", newFile.toString(), "content", "hello world"));

        assertTrue("Expected '✓ Created file:' prefix, got: " + result,
            result.startsWith("✓ Created file:"));
    }

    /**
     * After creation, the file on disk must contain exactly the content that was passed in.
     */
    public void testCreateNewFileHasContent() throws Exception {
        Path newFile = tempDir.resolve("created.txt");
        String expected = "line1\nline2\nline3";

        executeSync(args("path", newFile.toString(), "content", expected));

        assertTrue("File should exist on disk after creation", Files.exists(newFile));
        assertEquals(expected, Files.readString(newFile));
    }

    /**
     * Attempting to create a file that already exists should return an error that mentions
     * "File already exists". The second call returns synchronously (before invokeLater), so
     * it is safe to invoke directly from the EDT test thread.
     */
    public void testCreateFileFailsIfExists() throws Exception {
        Path file = tempDir.resolve("exists.txt");
        JsonObject createArgs = args("path", file.toString(), "content", "initial content");

        // First creation — should succeed
        String firstResult = executeSync(createArgs);
        assertTrue("First create should succeed, got: " + firstResult,
            firstResult.startsWith("✓ Created file:"));

        // Second creation — file already exists; tool returns before reaching invokeLater,
        // so calling execute() directly from the EDT is safe here.
        String secondResult = tool.execute(createArgs);
        assertTrue("Expected file-already-exists error, got: " + secondResult,
            secondResult.contains("Error: File already exists:"));
        assertTrue("Error should reference the path, got: " + secondResult,
            secondResult.contains(file.toString()));
    }

    /**
     * Omitting both the "path" and "content" parameters should return an error about the
     * missing required parameters. The tool returns synchronously before any async code, so
     * calling execute() directly from the EDT test thread is safe.
     */
    public void testCreateFileWithoutPath() throws Exception {
        // Neither path nor content present — returns before invokeLater.
        String result = tool.execute(new JsonObject());

        assertEquals("Error: 'path' and 'content' parameters are required", result);
    }

    /**
     * Creating a file inside a subdirectory that does not yet exist should succeed: the tool
     * must create the parent directories automatically.
     */
    public void testCreateFileInSubdirectory() throws Exception {
        Path subFile = tempDir.resolve("subdir/newfile.txt");
        assertFalse("Subdirectory must not exist before the test",
            Files.exists(subFile.getParent()));

        String result = executeSync(args("path", subFile.toString(), "content", "content in subdir"));

        assertTrue("Expected '✓ Created file:' prefix, got: " + result,
            result.startsWith("✓ Created file:"));
        assertTrue("File should exist in the newly created subdirectory", Files.exists(subFile));
        assertEquals("content in subdir", Files.readString(subFile));
    }
}

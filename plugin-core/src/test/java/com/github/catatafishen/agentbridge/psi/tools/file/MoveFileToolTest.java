package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.ToolError;
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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Platform tests for {@link MoveFileTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be {@code public void testXxx()}.
 * Run via Gradle only: {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>File creation note:</b> Tests use real disk files and directories created under a temp
 * directory, registered in the VFS via {@code LocalFileSystem#refreshAndFindFileByPath} so that
 * {@code MoveFileTool}'s {@code resolveVirtualFile} / {@code refreshAndFindVirtualFile} calls
 * can locate both the source file and the destination directory.
 *
 * <p><b>Async execution note:</b> {@code MoveFileTool} uses {@code EdtUtil.invokeLater} to
 * perform the actual move on the EDT, then blocks on a {@code CompletableFuture}. The
 * {@code executeSync} helper runs {@code execute()} on a pooled thread and pumps the EDT queue
 * until the future resolves.
 */
public class MoveFileToolTest extends BasePlatformTestCase {

    private MoveFileTool tool;
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening editors during tests.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new MoveFileTool(getProject());
        tempDir = Files.createTempDirectory("move-file-tool-test");
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

    /**
     * Creates a real directory on disk and registers it in the VFS.
     * The returned {@link VirtualFile} has {@code isDirectory() == true}.
     */
    private VirtualFile createTestDirectory(String name) {
        try {
            Path dir = tempDir.resolve(name);
            Files.createDirectories(dir);
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.toString());
            assertNotNull("Failed to register test directory in VFS: " + dir, vf);
            assertTrue("Registered VirtualFile should be a directory", vf.isDirectory());
            return vf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test directory: " + name, e);
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
     * Builds a {@link JsonObject} from alternating key/value string pairs.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs {@code tool.execute(argsObj)} on a pooled thread while pumping the EDT queue.
     * Required because {@code MoveFileTool} schedules its write action back onto the EDT
     * via {@code EdtUtil.invokeLater}; blocking the EDT directly would deadlock.
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
     * Moving an existing file to a valid destination directory should return a response of
     * the form "Moved &lt;srcPath&gt; to &lt;destDir&gt;/&lt;fileName&gt;".
     */
    public void testMoveFile() throws Exception {
        VirtualFile srcVf = createTestFile("tomove.txt", "move content");
        VirtualFile destDirVf = createTestDirectory("dest");

        String result = executeSync(
            args("path", srcVf.getPath(), "destination", destDirVf.getPath()));

        assertTrue("Expected 'Moved' message, got: " + result,
            result.startsWith("Moved "));
        assertTrue("Result should contain the source path, got: " + result,
            result.contains(srcVf.getPath()));
        assertTrue("Result should contain the destination directory path, got: " + result,
            result.contains(destDirVf.getPath()));
        assertTrue("Result should contain the filename, got: " + result,
            result.contains("tomove.txt"));
    }

    public void testMoveDirectoryFallsBackToPlainVfs() throws Exception {
        VirtualFile srcDir = createTestDirectory("source-dir");
        VirtualFile destDir = createTestDirectory("parent-dest");

        String result = executeSync(
            args("path", srcDir.getPath(), "destination", destDir.getPath()));

        assertTrue("Expected plain VFS fallback message, got: " + result,
            result.contains("using plain VFS move"));
        assertNotNull("Moved directory should exist under destination",
            destDir.findChild("source-dir"));
    }

    public void testMoveWithMissingDestination() throws Exception {
        VirtualFile srcVf = createTestFile("missing-dest-source.txt", "content");
        String missingDestination = tempDir.resolve("missing-dest").toString();

        String result = tool.execute(args("path", srcVf.getPath(), "destination", missingDestination));

        assertTrue("Expected error, got: " + result,
            ToolError.isError(result));
        assertTrue("Expected destination error, got: " + result,
            result.contains("Destination directory not found"));
    }

    /**
     * Omitting the "path" parameter should return an error about the missing required parameters.
     * The tool returns synchronously before any VFS access, so direct EDT invocation is safe.
     */
    public void testMoveWithoutPath() throws Exception {
        // Missing path — returns before resolveVirtualFile / invokeLater.
        String result = tool.execute(new JsonObject());

        assertTrue("Expected error, got: " + result,
            ToolError.isError(result));
        assertTrue("Expected missing-params message, got: " + result,
            result.contains("'path' and 'destination' parameters are required"));
    }

    /**
     * Omitting the "destination" parameter should return an error about the missing required
     * parameters. The tool returns synchronously before any VFS access, so direct EDT
     * invocation is safe.
     */
    public void testMoveWithoutDestination() throws Exception {
        // Missing destination — returns before resolveVirtualFile / invokeLater.
        String result = tool.execute(args("path", tempDir.resolve("dummy.txt").toString()));

        assertTrue("Expected error, got: " + result,
            ToolError.isError(result));
        assertTrue("Expected missing-params message, got: " + result,
            result.contains("'path' and 'destination' parameters are required"));
    }

    /**
     * Attempting to move a source path that does not exist (neither on disk nor in the VFS)
     * should return an error containing the standard "File not found:" message.
     *
     * <p><b>Threading note:</b> this test intentionally calls {@code tool.execute()} <em>directly
     * from the EDT</em> rather than via {@code executeSync}. When
     * {@code LocalFileSystem.refreshAndFindFileByPath} is invoked from a pooled thread (the
     * {@code executeSync} pattern), the VFS creates a <em>non-blocking</em> refresh session whose
     * "Final block" is dispatched back to EDT via {@code NonBlockingFlushQueue.runNextEvent →
     * computePrioritized}. If any other refresh session happens to be in-flight on the EDT at the
     * same time (e.g. the file-watcher deletion event from the preceding test's teardown), the two
     * sessions deadlock: the pooled thread waits for the Final block, while the Final block's
     * {@code computePrioritized} waits for the pooled thread's VFS read-action to finish.
     *
     * <p>When called from the EDT with {@code async=false}, {@code RefreshQueueImpl.launch}
     * detects {@code isDispatchThread()==true} and runs the session <em>synchronously inline</em>
     * — no non-blocking session is created, no deadlock is possible.
     *
     * <p>{@code MoveFileTool.execute()} returns synchronously at the "File not found" guard (before
     * reaching {@code performMoveOnEdt} / {@code CompletableFuture.get()}), so calling from EDT
     * does not block the event queue.
     *
     * <p>No {@code createTestDirectory} call is needed: the source check fires first and returns
     * early, making the destination value irrelevant.
     */
    public void testMoveNonExistentFile() throws Exception {
        String nonExistentPath = tempDir.resolve("does-not-exist.txt").toString();

        // Called from EDT: refreshAndFindFileByPath runs inline (no non-blocking session).
        // execute() returns before CompletableFuture.get() so the EDT is not blocked.
        String result = tool.execute(
            args("path", nonExistentPath, "destination", tempDir.toString()));

        assertTrue("Expected error, got: " + result,
            ToolError.isError(result));
        assertTrue("Expected 'File not found' in error, got: " + result,
            result.contains("FILE_NOT_FOUND") || result.contains(ToolUtils.ERROR_FILE_NOT_FOUND));
    }
}

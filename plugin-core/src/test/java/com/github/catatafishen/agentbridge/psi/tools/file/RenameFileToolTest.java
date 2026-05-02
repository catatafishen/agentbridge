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
 * Platform tests for {@link RenameFileTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be {@code public void testXxx()}.
 * Run via Gradle only: {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>File creation note:</b> Tests use real disk files created under a temp directory,
 * registered in the VFS via {@code LocalFileSystem#refreshAndFindFileByPath} so that
 * {@code RenameFileTool}'s {@code resolveVirtualFile} / {@code refreshAndFindVirtualFile}
 * calls can locate them.
 *
 * <p><b>Async execution note:</b> {@code RenameFileTool} uses {@code EdtUtil.invokeLater}
 * to perform the actual {@code VirtualFile#rename} inside a write action on the EDT, then
 * blocks on a {@code CompletableFuture}. The {@code executeSync} helper runs {@code execute()}
 * on a pooled thread and pumps the EDT queue until the future resolves.
 */
public class RenameFileToolTest extends BasePlatformTestCase {

    private RenameFileTool tool;
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening editors during tests.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new RenameFileTool(getProject());
        tempDir = Files.createTempDirectory("rename-file-tool-test");
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
     * Required because {@code RenameFileTool} schedules its write action back onto the EDT
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
     * Renaming an existing file should return a response of the form
     * "Renamed &lt;oldName&gt; to &lt;newName&gt;".
     */
    public void testRenameFile() throws Exception {
        VirtualFile vf = createTestFile("original.txt", "some content");

        String result = executeSync(args("path", vf.getPath(), "new_name", "renamed.txt"));

        assertTrue("Expected 'Renamed' message, got: " + result,
            result.startsWith("Renamed "));
        assertTrue("Result should reference the old filename, got: " + result,
            result.contains("original.txt"));
        assertTrue("Result should reference the new filename, got: " + result,
            result.contains("renamed.txt"));
    }

    /**
     * Omitting the "path" parameter should return an error about the missing required parameters.
     * The tool returns synchronously before any VFS access, so direct EDT invocation is safe.
     */
    public void testRenameWithoutPath() throws Exception {
        // Missing path — returns before resolveVirtualFile / invokeLater.
        String result = tool.execute(new JsonObject());

        assertTrue("Expected error, got: " + result,
            ToolError.isError(result));
        assertTrue("Expected missing-params message, got: " + result,
            result.contains("'path' and 'new_name' parameters are required"));
    }

    /**
     * Omitting the "new_name" parameter should return an error about the missing required
     * parameters. The tool returns synchronously before any VFS access, so direct EDT
     * invocation is safe.
     */
    public void testRenameWithoutNewName() throws Exception {
        // Missing new_name — returns before resolveVirtualFile / invokeLater.
        String result = tool.execute(args("path", tempDir.resolve("dummy.txt").toString()));

        assertTrue("Expected error, got: " + result,
            ToolError.isError(result));
        assertTrue("Expected missing-params message, got: " + result,
            result.contains("'path' and 'new_name' parameters are required"));
    }

    /**
     * Attempting to rename a path that does not exist (neither on disk nor in the VFS) should
     * return an error containing the standard "File not found:" message.
     */
    public void testRenameNonExistentFile() throws Exception {
        String nonExistentPath = tempDir.resolve("does-not-exist.txt").toString();

        String result = executeSync(args("path", nonExistentPath, "new_name", "other.txt"));

        assertTrue("Expected error, got: " + result,
            ToolError.isError(result));
        assertTrue("Expected 'File not found' in error, got: " + result,
            result.contains("FILE_NOT_FOUND") || result.contains(ToolUtils.ERROR_FILE_NOT_FOUND));
    }
}

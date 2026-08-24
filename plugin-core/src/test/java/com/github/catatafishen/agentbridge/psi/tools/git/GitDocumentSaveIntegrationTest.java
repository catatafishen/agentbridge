package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Threading integration tests for document saves initiated by Git tools.
 */
public class GitDocumentSaveIntegrationTest extends BasePlatformTestCase {

    @Override
    protected boolean runInDispatchThread() {
        return false;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            ConversationDatabase.getInstance(getProject()).dispose();
        } finally {
            super.tearDown();
        }
    }

    /**
     * MCP tools (including all git tools, via {@link GitTool#flushAndSave}) execute on a
     * background thread, not the EDT. IntelliJ's threading model only allows writing to the
     * document/VFS model from the EDT (see jb.gg/ij-platform-threading, "Writing data is only
     * allowed on EDT"); calling {@code FileDocumentManager.saveAllDocuments()} directly from a
     * background thread throws "Access is allowed from write thread only" on IntelliJ 2025.3+
     * (reported in production on Windows via {@code git_commit}). Verify that
     * {@link GitTool#saveAllDocuments} — invoked here from a background pooled thread, just
     * like real tool execution — completes without that error and actually persists the
     * document to disk.
     */
    public void testBackgroundThreadSaveDispatchesThroughEdtWithoutError() throws Exception {
        TestFile testFile = createUnsavedTestFile();

        CompletableFuture<Void> saveFuture = runOnPooledThread(GitTool::saveAllDocuments);
        saveFuture.get(10, TimeUnit.SECONDS);

        assertFalse("Document must be saved when saveAllDocuments() returns",
            FileDocumentManager.getInstance().isDocumentUnsaved(testFile.document()));
        assertEquals("changed", awaitDiskContent(testFile.path(), "changed"));
    }

    public void testReadOnlyGitStatusDoesNotSaveEditorDocuments() throws Exception {
        TestFile testFile = createUnsavedTestFile();

        try {
            new GitStatusTool(getProject()).execute(new JsonObject());

            assertTrue("Read-only git_status must not save editor documents",
                FileDocumentManager.getInstance().isDocumentUnsaved(testFile.document()));
        } finally {
            EdtTestUtil.runInEdtAndWait(() ->
                FileDocumentManager.getInstance().reloadFromDisk(testFile.document()));
        }
    }

    public void testGitWriteAbortsWhenDeferredFormatCannotComplete() {
        Project disposedProject = mock(Project.class);
        when(disposedProject.isDisposed()).thenReturn(true);
        GitStatusTool tool = new GitStatusTool(disposedProject);

        try {
            tool.flushAndSave();
            fail("Git write must abort when deferred formatting cannot complete");
        } catch (IllegalStateException e) {
            assertEquals(
                "Git operation aborted: deferred auto-format did not complete. Retry the operation.",
                e.getMessage());
        }
    }

    private TestFile createUnsavedTestFile() throws Exception {
        String basePath = getProject().getBasePath();
        assertNotNull("Light project must have a base path", basePath);
        Path projectDir = Path.of(basePath);
        Files.createDirectories(projectDir);
        Path path = Files.createTempFile(projectDir, "git-document-save-", ".txt");
        Files.writeString(path, "original");

        VirtualFile file =
            LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString());
        assertNotNull("Failed to register test file in VFS", file);
        Document document = ReadAction.nonBlocking(
                () -> FileDocumentManager.getInstance().getDocument(file))
            .expireWith(getProject())
            .executeSynchronously();
        assertNotNull("Expected a document for the test file", document);

        EdtTestUtil.runInEdtAndWait(() ->
            WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.setText("changed")));
        assertTrue("Test precondition: document must be unsaved",
            FileDocumentManager.getInstance().isDocumentUnsaved(document));
        return new TestFile(path, document);
    }

    private static CompletableFuture<Void> runOnPooledThread(Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Polls the file on disk for up to 5 seconds until it matches {@code expected}. Physical
     * disk persistence after {@code FileDocumentManager.saveAllDocuments()} returns is not
     * guaranteed to be instantaneous (the VFS may flush asynchronously), so a direct read
     * immediately after the save call can be flaky. Returns the last content read (which may
     * still differ from {@code expected} if the timeout is reached, so the caller's assertion
     * produces a useful diff).
     */
    private static String awaitDiskContent(Path path, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String content;
        do {
            content = Files.readString(path);
            if (expected.equals(content)) return content;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            if (Thread.interrupted()) throw new InterruptedException();
        } while (System.nanoTime() < deadline);
        return content;
    }

    private record TestFile(Path path, Document document) {
    }
}

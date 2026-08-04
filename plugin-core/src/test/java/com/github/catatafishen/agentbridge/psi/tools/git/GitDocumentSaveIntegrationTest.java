package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.impl.ApplicationImpl;
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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

    public void testBackgroundSaveKeepsGitWriteWaitOffEdt() throws Exception {
        TestFile testFile = createUnsavedTestFile();
        ApplicationImpl application = (ApplicationImpl) ApplicationManager.getApplication();
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);

        CompletableFuture<Void> readFuture = runOnPooledThread(() ->
            application.runReadAction(() -> {
                readStarted.countDown();
                awaitRelease(releaseRead, "read action");
            }));
        assertTrue("Background read action did not start",
            readStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> saveFuture =
            runOnPooledThread(GitTool::saveAllDocuments);

        EdtSnapshot edtSnapshot;
        try {
            assertTrue("Document save never requested a write action",
                waitForPendingWriteAction(application, saveFuture));
            edtSnapshot = captureEdtSnapshot();
        } finally {
            releaseRead.countDown();
        }

        readFuture.get(10, TimeUnit.SECONDS);
        saveFuture.get(10, TimeUnit.SECONDS);
        assertFalse("Git save was waiting for the write lock on EDT: " + edtSnapshot,
            edtSnapshot.containsClass(GitTool.class.getName()));
        assertFalse("Document must be saved when saveAllDocuments() returns",
            FileDocumentManager.getInstance().isDocumentUnsaved(testFile.document()));
        assertEquals("changed", Files.readString(testFile.path()));
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

    private static boolean waitForPendingWriteAction(
        ApplicationImpl application,
        CompletableFuture<?> saveFuture
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!application.isWriteActionPending()
            && !saveFuture.isDone()
            && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return application.isWriteActionPending();
    }

    private static EdtSnapshot captureEdtSnapshot() {
        for (Map.Entry<Thread, StackTraceElement[]> entry
            : Thread.getAllStackTraces().entrySet()) {
            if (entry.getKey().getName().startsWith("AWT-EventQueue")) {
                return new EdtSnapshot(entry.getKey().getState(), entry.getValue());
            }
        }
        throw new AssertionError("AWT event dispatch thread was not found");
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

    private static void awaitRelease(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release " + operation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(operation + " was interrupted", e);
        }
    }

    private record EdtSnapshot(Thread.State state, StackTraceElement[] stack) {
        private boolean containsClass(String className) {
            return Arrays.stream(stack)
                .anyMatch(frame -> frame.getClassName().startsWith(className));
        }

        @Override
        public String toString() {
            String separator = System.lineSeparator();
            return state + separator + String.join(separator,
                Arrays.stream(stack).map(StackTraceElement::toString).toList());
        }
    }

    private record TestFile(Path path, Document document) {
    }
}

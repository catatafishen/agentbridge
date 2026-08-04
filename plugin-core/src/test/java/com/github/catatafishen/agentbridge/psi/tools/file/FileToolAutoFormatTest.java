package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Platform integration tests for the deferred auto-format pipeline.
 */
public class FileToolAutoFormatTest extends BasePlatformTestCase {

    private Path sourceDir;

    @Override
    protected boolean runInDispatchThread() {
        return false;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        String projectBasePath = getProject().getBasePath();
        assertNotNull("Light project must have a base path", projectBasePath);
        Path projectBaseDir = Files.createDirectories(Path.of(projectBasePath));
        sourceDir = Files.createTempDirectory(projectBaseDir, "file-tool-auto-format-test");

        VirtualFile sourceRoot =
            LocalFileSystem.getInstance().refreshAndFindFileByPath(sourceDir.toString());
        assertNotNull("Failed to register test source root in VFS", sourceRoot);
        EdtTestUtil.runInEdtAndWait(() -> PsiTestUtil.addSourceRoot(getModule(), sourceRoot));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            ConversationDatabase.getInstance(getProject()).dispose();
        } finally {
            super.tearDown();
        }
    }

    public void testFlushReturnsAfterFormattingHasCompletedAndSaved() throws Exception {
        TestFile testFile = createTestFile("Sample.java", "class Sample{int value=1;}");

        FileTool.queueAutoFormat(getProject(), testFile.file().getPath());

        assertTrue("Deferred auto-format should complete successfully",
            FileTool.flushPendingAutoFormat(getProject()));
        assertFalse("The formatted document must be saved before flush returns",
            FileDocumentManager.getInstance().isDocumentUnsaved(testFile.document()));

        String formatted = Files.readString(Path.of(testFile.file().getPath()));
        assertTrue("Expected class declaration to be reformatted: " + formatted,
            formatted.contains("class Sample {"));
        assertTrue("Expected field declaration to be reformatted: " + formatted,
            formatted.contains("int value = 1;"));
    }

    public void testEdtFlushSchedulesBackgroundFormattingAndSave() throws Exception {
        TestFile testFile = createTestFile(
            "AsyncSample.java", "class AsyncSample{int value=1;}");
        FileTool.queueAutoFormat(getProject(), testFile.file().getPath());
        AtomicBoolean flushResult = new AtomicBoolean(true);

        EdtTestUtil.runInEdtAndWait(() ->
            flushResult.set(FileTool.flushPendingAutoFormat(getProject())));

        assertFalse("EDT flush must hand work to a background thread", flushResult.get());
        assertTrue("Background flush did not format and save the file",
            waitForFormattedAndSaved(testFile));
    }

    public void testDisposedProjectRejectsQueueAndFlush() {
        Project disposedProject = mock(Project.class);
        when(disposedProject.isDisposed()).thenReturn(true);

        FileTool.queueAutoFormat(disposedProject, "ignored.java");

        assertFalse("Disposed projects cannot flush deferred formatting",
            FileTool.flushPendingAutoFormat(disposedProject));
    }

    public void testInterruptedFlushRetainsWorkForRetry() throws Exception {
        TestFile testFile = createTestFile(
            "InterruptedSample.java", "class InterruptedSample{int value=1;}");
        FileTool.queueAutoFormat(getProject(), testFile.file().getPath());

        boolean flushResult;
        boolean interruptRestored;
        Thread.currentThread().interrupt();
        try {
            flushResult = FileTool.flushPendingAutoFormat(getProject());
            interruptRestored = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }

        assertFalse("Interrupted flush must fail visibly", flushResult);
        assertTrue("Interrupted status must be restored", interruptRestored);
        assertTrue("Queued work must remain available for retry",
            FileTool.flushPendingAutoFormat(getProject()));
    }

    public void testInterruptionAfterDrainRequeuesPendingPath() {
        Project mockProject = mock(Project.class);
        AtomicInteger disposalChecks = new AtomicInteger();
        AtomicBoolean disposed = new AtomicBoolean(false);
        when(mockProject.isDisposed()).thenAnswer(ignored -> {
            if (disposed.get()) return true;
            if (disposalChecks.incrementAndGet() == 3) {
                Thread.currentThread().interrupt();
            }
            return false;
        });
        FileTool.queueAutoFormat(mockProject, "retained.java");

        boolean flushResult;
        try {
            flushResult = FileTool.flushPendingAutoFormat(mockProject);
        } finally {
            Thread.interrupted();
        }

        assertFalse("Interrupted batch must fail visibly", flushResult);
        disposed.set(true);
        assertFalse("Cleanup flush must observe project disposal",
            FileTool.flushPendingAutoFormat(mockProject));
    }

    public void testExpiredBatchRequeuesPathsInOriginalOrder() {
        Project activeProject = mock(Project.class);
        when(activeProject.isDisposed()).thenReturn(false);
        FileTool.AutoFormatState state = new FileTool.AutoFormatState();
        state.add("first.java");
        state.add("second.java");

        assertFalse("Expired batch must fail",
            FileTool.flushWhileLocked(activeProject, state, System.nanoTime() - 1));
        assertEquals("Expired work must be retained in order",
            List.of("first.java", "second.java"), state.drain());
    }

    public void testProjectDisposalBeforeBatchRetainsQueue() {
        Project disposedProject = mock(Project.class);
        when(disposedProject.isDisposed()).thenReturn(true);
        FileTool.AutoFormatState state = new FileTool.AutoFormatState();
        state.add("retained.java");

        assertFalse("Disposed project must stop a locked flush",
            FileTool.flushWhileLocked(disposedProject, state, Long.MAX_VALUE));
        assertEquals(List.of("retained.java"), state.drain());
    }

    public void testCancellationAndRuntimeFailureRetainQueue() {
        Project cancelledProject = mock(Project.class);
        when(cancelledProject.isDisposed()).thenThrow(new ProcessCanceledException());
        FileTool.AutoFormatState cancelledState = new FileTool.AutoFormatState();
        cancelledState.add("cancelled.java");

        assertFalse("Cancellation must be reported as an incomplete flush",
            FileTool.flushWhileLocked(cancelledProject, cancelledState, Long.MAX_VALUE));
        assertEquals(List.of("cancelled.java"), cancelledState.drain());

        Project failedProject = mock(Project.class);
        when(failedProject.isDisposed()).thenThrow(new IllegalStateException("boom"));
        FileTool.AutoFormatState failedState = new FileTool.AutoFormatState();
        failedState.add("failed.java");

        assertFalse("Runtime failure must be reported as an incomplete flush",
            FileTool.flushWhileLocked(failedProject, failedState, Long.MAX_VALUE));
        assertEquals(List.of("failed.java"), failedState.drain());
    }

    public void testRequeueFirstKeepsFailedBatchAheadOfNewPaths() {
        FileTool.AutoFormatState state = new FileTool.AutoFormatState();
        state.add("new.java");

        state.requeueFirst(List.of("first.java", "new.java"));

        assertEquals(List.of("first.java", "new.java"), state.drain());
        state.requeueFirst(List.of());
        assertTrue(state.drain().isEmpty());
    }

    public void testSuccessfulChunksAreNotRetriedWhenLaterChunkFails() {
        Project activeProject = mock(Project.class);
        when(activeProject.isDisposed()).thenReturn(false);
        FileTool.AutoFormatState state = new FileTool.AutoFormatState();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < FileTool.AUTO_FORMAT_BATCH_SIZE + 2; i++) {
            String path = "File" + i + ".java";
            paths.add(path);
            state.add(path);
        }

        List<List<String>> attemptedChunks = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        assertFalse(FileTool.flushWhileLocked(
            activeProject,
            state,
            Long.MAX_VALUE,
            (chunk, ignoredDeadline) -> {
                attemptedChunks.add(List.copyOf(chunk));
                return calls.getAndIncrement() == 0;
            }));

        assertEquals(2, attemptedChunks.size());
        assertEquals(paths.subList(0, FileTool.AUTO_FORMAT_BATCH_SIZE),
            attemptedChunks.get(0));
        assertEquals(paths.subList(FileTool.AUTO_FORMAT_BATCH_SIZE, paths.size()),
            attemptedChunks.get(1));
        assertEquals(
            "Only the failed chunk must be retained after earlier chunks succeeded",
            paths.subList(FileTool.AUTO_FORMAT_BATCH_SIZE, paths.size()),
            state.drain());
    }

    public void testLargeQueueCompletesInBoundedChunks() {
        Project activeProject = mock(Project.class);
        when(activeProject.isDisposed()).thenReturn(false);
        FileTool.AutoFormatState state = new FileTool.AutoFormatState();
        int pathCount = FileTool.AUTO_FORMAT_BATCH_SIZE * 2 + 1;
        for (int i = 0; i < pathCount; i++) {
            state.add("File" + i + ".java");
        }

        List<Integer> chunkSizes = new ArrayList<>();
        assertTrue(FileTool.flushWhileLocked(
            activeProject,
            state,
            Long.MAX_VALUE,
            (chunk, ignoredDeadline) -> {
                chunkSizes.add(chunk.size());
                return true;
            }));

        assertEquals(
            List.of(FileTool.AUTO_FORMAT_BATCH_SIZE, FileTool.AUTO_FORMAT_BATCH_SIZE, 1),
            chunkSizes);
        assertTrue(state.drain().isEmpty());
    }

    public void testEmptyAndIdleQueuesReturnImmediately() {
        assertTrue("A project without a queue must be a no-op",
            FileTool.flushPendingAutoFormat(getProject()));

        FileTool.queueAutoFormat(getProject(), sourceDir.resolve("MissingIdle.java").toString());
        assertTrue(FileTool.flushPendingAutoFormat(getProject()));
        assertTrue("A drained queue must remain a no-op",
            FileTool.flushPendingAutoFormat(getProject()));
    }

    public void testExpiredAndContendedFlushLockRetainQueuedWork() throws Exception {
        Project activeProject = mock(Project.class);
        when(activeProject.isDisposed()).thenReturn(false);

        FileTool.AutoFormatState expiredState = new FileTool.AutoFormatState();
        expiredState.add("expired.java");
        assertFalse("A zero lock budget must time out immediately",
            FileTool.flushOnBackgroundThread(activeProject, expiredState, 0));
        assertEquals(List.of("expired.java"), expiredState.drain());
        assertTrue(expiredState.isIdle());

        FileTool.AutoFormatState contendedState = new FileTool.AutoFormatState();
        contendedState.add("contended.java");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            contendedState.flushLock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                contendedState.flushLock.unlock();
            }
        }, "file-tool-format-lock-holder");
        holder.start();
        assertTrue("Lock holder did not start", locked.await(5, TimeUnit.SECONDS));

        try {
            assertFalse("A locked state is not idle", contendedState.isIdle());
            assertFalse("A contended lock must respect the supplied timeout",
                FileTool.flushOnBackgroundThread(
                    activeProject, contendedState, TimeUnit.MILLISECONDS.toNanos(20)));
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse("Lock holder did not stop", holder.isAlive());
        assertEquals("Timed-out work must remain queued",
            List.of("contended.java"), contendedState.drain());
        assertTrue(contendedState.isIdle());
    }

    public void testDeadlineCancellationAndRunnerResultAreReported() {
        long generousDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        assertTrue(FileTool.runAutoFormatWithDeadline(generousDeadline, ignored -> true));
        assertFalse(FileTool.runAutoFormatWithDeadline(generousDeadline, ignored -> false));

        AtomicBoolean cancellationObserved = new AtomicBoolean(false);
        long cancellationDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
        boolean result = FileTool.runAutoFormatWithDeadline(cancellationDeadline, indicator -> {
            long guardDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!indicator.isCanceled() && System.nanoTime() < guardDeadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            cancellationObserved.set(indicator.isCanceled());
            return true;
        });

        assertTrue("Scheduled deadline did not cancel the indicator", cancellationObserved.get());
        assertFalse("A canceled formatting run must fail visibly", result);
    }

    public void testProcessorAndDocumentFailuresAbortPipeline() {
        Project activeProject = mock(Project.class);
        when(activeProject.isDisposed()).thenReturn(false);
        ProgressIndicatorBase indicator = new ProgressIndicatorBase();
        FileDocumentManager failedManager = mock(FileDocumentManager.class);

        assertFalse("A formatter failure must abort before saving",
            FileTool.runAutoFormatBatch(
                activeProject, List.of(), indicator, (ignored, optimize) -> false, failedManager));

        FileDocumentManager savedManager = mock(FileDocumentManager.class);
        assertTrue("Successful empty processor groups must complete",
            FileTool.runAutoFormatBatch(
                activeProject, List.of(), new ProgressIndicatorBase(),
                (ignored, optimize) -> true, savedManager));

        List<PsiFile> files = List.of(mock(PsiFile.class));
        AtomicInteger calls = new AtomicInteger();
        assertFalse(FileTool.runFormatProcessors(files, files, (ignored, optimize) -> {
            calls.incrementAndGet();
            return false;
        }));
        assertEquals("Second formatter must not run after first-stage failure", 1, calls.get());

        calls.set(0);
        assertFalse(FileTool.runFormatProcessors(files, files, (ignored, optimize) -> {
            calls.incrementAndGet();
            return optimize;
        }));
        assertEquals("Both formatter stages must run before second-stage failure", 2, calls.get());
        assertTrue(FileTool.runFormatProcessors(files, files, (ignored, optimize) -> true));

        Document document = mock(Document.class);
        FileDocumentManager unsavedManager = mock(FileDocumentManager.class);
        when(unsavedManager.isDocumentUnsaved(document)).thenReturn(true);
        assertFalse("An unsaved processed document must fail the flush",
            FileTool.saveProcessedDocuments(unsavedManager, List.of(document), 1));

        FileDocumentManager cleanManager = mock(FileDocumentManager.class);
        when(cleanManager.isDocumentUnsaved(document)).thenReturn(false);
        assertTrue(FileTool.saveProcessedDocuments(cleanManager, List.of(document), 1));
        assertTrue("An empty document batch is already saved",
            FileTool.saveProcessedDocuments(mock(FileDocumentManager.class), List.of(), 0));
    }

    public void testFormatPlanAndVirtualFileBoundaryDecisions() {
        VirtualFile invalidFile = mock(VirtualFile.class);
        when(invalidFile.isValid()).thenReturn(false);
        VirtualFile readOnlyFile = mock(VirtualFile.class);
        when(readOnlyFile.isValid()).thenReturn(true);
        when(readOnlyFile.isWritable()).thenReturn(false);
        VirtualFile writableFile = mock(VirtualFile.class);
        when(writableFile.isValid()).thenReturn(true);
        when(writableFile.isWritable()).thenReturn(true);

        assertFalse(FileTool.isUsableFormatFile(null));
        assertFalse(FileTool.isUsableFormatFile(invalidFile));
        assertFalse(FileTool.isUsableFormatFile(readOnlyFile));
        assertTrue(FileTool.isUsableFormatFile(writableFile));

        PsiFile psiFile = mock(PsiFile.class);
        Document document = mock(Document.class);
        List<PsiFile> optimize = new ArrayList<>();
        List<PsiFile> reformat = new ArrayList<>();
        List<Document> documents = new ArrayList<>();

        FileTool.addToFormatPlan(
            new FileTool.ResolvedFormatFile(psiFile, null, 1),
            optimize, reformat, documents);
        FileTool.addToFormatPlan(
            new FileTool.ResolvedFormatFile(
                psiFile, document, FileTool.MAX_BYTES_FOR_OPTIMIZE_IMPORTS + 1),
            optimize, reformat, documents);

        assertEquals(List.of(psiFile), optimize);
        assertEquals(List.of(psiFile), reformat);
        assertEquals(List.of(document), documents);
    }

    public void testDirectoryWithoutPsiFileIsIgnored() {
        FileTool.queueAutoFormat(getProject(), sourceDir.toString());

        assertTrue("A directory cannot produce a PsiFile and must be ignored",
            FileTool.flushPendingAutoFormat(getProject()));
    }

    public void testMediumFileSkipsImportOptimizationButStillReformats() throws Exception {
        String content = "class MediumSample{int value=1;"
            + " ".repeat((int) FileTool.MAX_BYTES_FOR_OPTIMIZE_IMPORTS + 1024)
            + "}";
        TestFile testFile = createTestFile("MediumSample.java", content);
        assertTrue(testFile.file().getLength() > FileTool.MAX_BYTES_FOR_OPTIMIZE_IMPORTS);
        assertTrue(testFile.file().getLength() <= FileTool.MAX_BYTES_FOR_REFORMAT);
        FileTool.queueAutoFormat(getProject(), testFile.file().getPath());

        assertTrue(FileTool.flushPendingAutoFormat(getProject()));

        String formatted = Files.readString(Path.of(testFile.file().getPath()));
        assertTrue(formatted.contains("class MediumSample {"));
        assertTrue(formatted.contains("int value = 1;"));
    }

    public void testTooLargeFileIsSkippedWithoutModification() throws Exception {
        String content = "class TooLargeSample{"
            + " ".repeat((int) FileTool.MAX_BYTES_FOR_REFORMAT + 1024)
            + "}";
        TestFile testFile = createTestFile("TooLargeSample.java", content);
        assertTrue(testFile.file().getLength() > FileTool.MAX_BYTES_FOR_REFORMAT);
        FileTool.queueAutoFormat(getProject(), testFile.file().getPath());

        assertTrue(FileTool.flushPendingAutoFormat(getProject()));
        assertEquals(content, Files.readString(Path.of(testFile.file().getPath())));
    }

    public void testMissingPathDoesNotFailFlush() {
        Path missingPath = sourceDir.resolve("Missing.java");
        FileTool.queueAutoFormat(getProject(), missingPath.toString());

        assertTrue("Missing queued files should be ignored",
            FileTool.flushPendingAutoFormat(getProject()));
    }

    private boolean waitForFormattedAndSaved(TestFile testFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Path path = Path.of(testFile.file().getPath());
        while (System.nanoTime() < deadline) {
            String content = Files.readString(path);
            boolean saved = !FileDocumentManager.getInstance()
                .isDocumentUnsaved(testFile.document());
            if (saved
                && content.contains("class AsyncSample {")
                && content.contains("int value = 1;")) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return false;
    }

    private TestFile createTestFile(String name, String content) throws IOException {
        Path filePath = sourceDir.resolve(name);
        Files.writeString(filePath, content);
        VirtualFile file =
            LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString());
        assertNotNull("Failed to register test file in VFS: " + filePath, file);

        Document document = ReadAction.nonBlocking(
                () -> FileDocumentManager.getInstance().getDocument(file))
            .expireWith(getProject())
            .executeSynchronously();
        assertNotNull("Expected a document for the test file", document);
        return new TestFile(file, document);
    }

    private record TestFile(VirtualFile file, Document document) {
    }
}

package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

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

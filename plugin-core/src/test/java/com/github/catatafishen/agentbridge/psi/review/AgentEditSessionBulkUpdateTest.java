package com.github.catatafishen.agentbridge.psi.review;

import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.DocumentUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regression for issue #1238: a bulk document update (such as trailing-space stripping on save) makes
 * one small edit per line. The session must keep the baseline from before the batch and produce
 * correct line counts once the batch ends, without re-diffing the whole file for each edit.
 */
public class AgentEditSessionBulkUpdateTest extends BasePlatformTestCase {

    private AgentEditSession session;
    private boolean previousAutoApprove;
    private VirtualFile contentRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        McpServerSettings settings = McpServerSettings.getInstance(getProject());
        previousAutoApprove = settings.isAutoApproveAgentEdits();
        settings.setAutoApproveAgentEdits(false);
        session = AgentEditSession.getInstance(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            AgentEditSession.markAgentEditEnd();
            session.acceptAll();
            session.removeAllApproved();
            McpServerSettings.getInstance(getProject()).setAutoApproveAgentEdits(previousAutoApprove);
            if (contentRoot != null) {
                PsiTestUtil.removeContentEntry(getModule(), contentRoot);
                WriteAction.runAndWait(() -> contentRoot.delete(this));
            }
        } finally {
            super.tearDown();
        }
    }

    public void testBulkEditsKeepOriginalBaselineAndCountOnceAtEnd() throws Exception {
        StringBuilder original = new StringBuilder();
        for (int i = 0; i < 50; i++) original.append("line").append(i).append("   \n");
        VirtualFile vf = createProjectFile("bulk.txt", original.toString());
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        assertNotNull(doc);

        AgentEditSession.markAgentEditStart();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> DocumentUtil.executeInBulk(doc, () -> {
            // Strip the trailing spaces line by line, back to front, like TrailingSpacesStripper.
            for (int line = doc.getLineCount() - 1; line >= 0; line--) {
                int end = doc.getLineEndOffset(line);
                int start = end;
                while (start > doc.getLineStartOffset(line) && doc.getCharsSequence().charAt(start - 1) == ' ') {
                    start--;
                }
                if (start < end) doc.deleteString(start, end);
            }
        }));
        AgentEditSession.markAgentEditEnd();

        ReviewItem item = session.getReviewItems().stream()
            .filter(i -> i.path().equals(vf.getPath()))
            .findFirst()
            .orElse(null);
        assertNotNull("the bulk-edited file must be tracked for review", item);
        assertEquals("baseline must be the content from before the batch", original.toString(), item.beforeContent());
        assertEquals("every line changed once, counted after the batch", 50, item.linesAdded());
        assertEquals(50, item.linesRemoved());
    }

    /**
     * The session only tracks files under the project base path that are in project content. Light
     * fixture files live in an in-memory file system, so this creates a real directory under the base
     * path and registers it as a content root for the duration of the test.
     */
    private VirtualFile createProjectFile(String name, String content) throws IOException {
        String basePath = getProject().getBasePath();
        assertNotNull("test project must have a base path", basePath);
        Path dir = Files.createDirectories(Path.of(basePath, "bulk-update-test"));
        Files.writeString(dir.resolve(name), content);
        VirtualFile dirVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
        assertNotNull(dirVf);
        contentRoot = dirVf;
        PsiTestUtil.addContentRoot(getModule(), dirVf);
        VirtualFile vf = dirVf.findChild(name);
        assertNotNull(vf);
        return vf;
    }
}

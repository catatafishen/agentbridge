package com.github.catatafishen.agentbridge.attribution;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the real document listener: an edit made between {@code agentEditStarted} and
 * {@code agentEditFinished} while a turn is active belongs to that turn; anything else only shifts
 * or splits existing ownership.
 */
public class PromptAttributionServiceTest extends BasePlatformTestCase {

    private PromptAttributionService service;
    private VirtualFile file;
    private Document doc;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = PromptAttributionService.getInstance(getProject());
        service.reset(); // the light test project is shared between tests
        service.ensureStarted();
        PsiFile psi = myFixture.configureByText("Sample.txt", "one\ntwo\nthree\n");
        file = psi.getVirtualFile();
        doc = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(doc);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            service.endTurn();
        } finally {
            super.tearDown();
        }
    }

    private void agentReplace(int start, int end, String text) {
        PromptAttributionService.agentEditStarted(getProject());
        try {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.replaceString(start, end, text));
        } finally {
            PromptAttributionService.agentEditFinished();
        }
    }

    private void userReplace(int start, int end, String text) {
        WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.replaceString(start, end, text));
    }

    private List<String> owners() {
        LineOwnership model = service.ownershipFor(file);
        List<String> result = new ArrayList<>();
        if (model == null) return result;
        for (int i = 0; i < model.getLineCount(); i++) {
            LineOwner o = model.ownerAt(i);
            result.add(o == null ? null : o.getTurnId());
        }
        return result;
    }

    public void testUserEditsWithoutAnActiveTurnAreNotTracked() {
        userReplace(0, 3, "ONE");

        assertNull(service.ownershipFor(file));
    }

    public void testAgentEditIsAttributedToTheActiveTurn() {
        service.beginTurn("turn-1");

        agentReplace(4, 7, "TWO");

        List<String> owners = owners();
        assertEquals(4, owners.size()); // "one", "TWO", "three", ""
        assertNull(owners.get(0));
        assertEquals("turn-1", owners.get(1));
        assertNull(owners.get(2));
    }

    public void testAgentEditWithoutActiveTurnIsNotAttributed() {
        agentReplace(4, 7, "TWO");

        assertNull(service.ownershipFor(file));
    }

    public void testEditOutsideAnAgentEditBracketIsNotAttributedEvenDuringATurn() {
        service.beginTurn("turn-1");

        userReplace(4, 7, "TWO");

        assertNull(service.ownershipFor(file));
    }

    public void testLaterAgentEditTakesOverOnlyItsLines() {
        service.beginTurn("turn-1");
        agentReplace(4, 7, "TWO");
        service.beginTurn("turn-2");

        agentReplace(8, 13, "THREE");

        List<String> owners = owners();
        assertEquals("turn-1", owners.get(1));
        assertEquals("turn-2", owners.get(2));
    }

    public void testInsertedLinesShiftLaterOwnership() {
        service.beginTurn("turn-1");
        agentReplace(8, 13, "THREE");

        userReplace(0, 0, "zero\n"); // user inserts a new first line

        List<String> owners = owners();
        assertNull(owners.get(0));
        assertEquals("turn-1", owners.get(3)); // THREE moved from line 2 to line 3
    }

    public void testUserTypingInsideAnOwnedLineKeepsTheOwner() {
        service.beginTurn("turn-1");
        agentReplace(4, 7, "TWO");

        userReplace(7, 7, "!!"); // "TWO!!"

        assertEquals("turn-1", owners().get(1));
    }

    public void testNestedAgentEditBracketsStayActiveUntilTheOuterOneEnds() {
        service.beginTurn("turn-1");
        PromptAttributionService.agentEditStarted(getProject());
        PromptAttributionService.agentEditStarted(getProject());
        PromptAttributionService.agentEditFinished();
        try {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.replaceString(4, 7, "TWO"));
        } finally {
            PromptAttributionService.agentEditFinished();
        }

        assertEquals("turn-1", owners().get(1));
    }

    public void testChangeListenerIsNotifiedOnEdits() {
        int[] calls = {0};
        Runnable listener = () -> calls[0]++;
        service.addChangeListener(listener);
        try {
            service.beginTurn("turn-1");
            agentReplace(4, 7, "TWO");
        } finally {
            service.removeChangeListener(listener);
        }

        assertTrue(calls[0] > 0);
    }
}

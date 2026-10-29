package com.github.catatafishen.agentbridge.attribution;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.github.catatafishen.agentbridge.session.db.ConversationWriter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A file edited before live capture existed gets best-effort attribution rebuilt from the recorded tool
 * calls, flagged inferred, and live data is never overridden.
 */
public class PromptAttributionBackfillTest extends BasePlatformTestCase {

    private static final String BODY = "    int a = 1;\n    int b = 2;\n";

    private Connection conn;
    private PromptAttributionService service;
    private VirtualFile file;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        ConversationDatabase.getInstance(getProject()).dispose();
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        ConversationDatabase.getInstance(getProject()).initializeWithConnection(conn);

        service = PromptAttributionService.getInstance(getProject());
        service.reset();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            service.reset();
            ConversationDatabase.getInstance(getProject()).dispose();
            if (conn != null) conn.close();
        } finally {
            super.tearDown();
        }
    }

    private void recordEdit(String turnId, String time, String newText) {
        String path = file.getPath();
        String args = "{\"path\":" + quote(path) + ",\"old_str\":\"x\",\"new_str\":" + quote(newText) + "}";
        new ConversationWriter(ConversationDatabase.getInstance(getProject())).recordEntries(
            "s-" + turnId, "Copilot", "copilot", List.of(
                new EntryData.Prompt("Prompt " + turnId, time, null, turnId, turnId),
                new EntryData.ToolCall("edit_text", args, "edit", "Edited", "completed", null,
                    path, false, null, null, time, "Copilot", "m", "ev-" + turnId)));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private List<String> owners(LineOwnership m) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < m.getLineCount(); i++) {
            LineOwner o = m.ownerAt(i);
            r.add(o == null ? null : o.getTurnId());
        }
        return r;
    }

    private void open(String text) {
        PsiFile psi = myFixture.configureByText("Backfill.txt", text);
        file = psi.getVirtualFile();
        assertNotNull(FileDocumentManager.getInstance().getDocument(file));
    }

    public void testFileEditedBeforeLiveCaptureIsRebuiltFromRecordedToolCalls() {
        open("class A {\n" + BODY + "}\n");
        recordEdit("t1", "2026-01-01T10:00:00Z", BODY.stripTrailing());

        LineOwnership model = service.ownershipFor(file);

        assertNotNull(model);
        assertEquals("t1", owners(model).get(1));
        assertEquals("t1", owners(model).get(2));
        assertNull(owners(model).get(0));
        assertTrue("backfilled owners must be flagged inferred", model.ownerAt(1).getInferred());
    }

    public void testNothingIsInventedWhenTheRecordedTextIsGone() {
        open("class A {\n" + BODY + "}\n");
        recordEdit("t1", "2026-01-01T10:00:00Z", "    int gone = 9;\n    int missing = 8;");

        assertNull(service.ownershipFor(file));
    }

    public void testFileWithoutRecordedCallsHasNoAttribution() {
        open("class A {\n" + BODY + "}\n");

        assertNull(service.ownershipFor(file));
    }

    public void testLiveAttributionIsNeverReplacedByBackfill() {
        open("class A {\n" + BODY + "}\n");
        recordEdit("t-old", "2026-01-01T10:00:00Z", BODY.stripTrailing());
        // live capture: a newer turn rewrites the first body line
        service.beginTurn("t-live");
        PromptAttributionService.agentEditStarted(getProject());
        try {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
                FileDocumentManager.getInstance().getDocument(file).replaceString(10, 24, "    int a = 11;"));
        } finally {
            PromptAttributionService.agentEditFinished();
        }

        LineOwnership model = service.ownershipFor(file);

        assertNotNull(model);
        assertEquals("t-live", owners(model).get(1));
        assertFalse(model.ownerAt(1).getInferred());
    }
}

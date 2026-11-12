package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests all 8 regex patterns in {@link RefactorRenderer}.
 * Does not construct any Swing components.
 */
class RefactorRendererTest {

    private final Regex rename = RefactorRenderer.INSTANCE.getRENAME();
    private final Regex deleteOk = RefactorRenderer.INSTANCE.getDELETE_OK();
    private final Regex extract = RefactorRenderer.INSTANCE.getEXTRACT();
    private final Regex inline = RefactorRenderer.INSTANCE.getINLINE();
    private final Regex deleteFail = RefactorRenderer.INSTANCE.getDELETE_FAIL();
    private final Regex refCount = RefactorRenderer.INSTANCE.getREF_COUNT();
    private final Regex fileLine = RefactorRenderer.INSTANCE.getFILE_LINE();
    private final Regex usageLine = RefactorRenderer.INSTANCE.getUSAGE_LINE();

    // ── RENAME ──────────────────────────────────────────────

    @Test
    void rename_matchesAndCapturesGroups() {
        MatchResult m = rename.find("Renamed 'oldName' to 'newName'", 0);
        assertNotNull(m);
        assertEquals("oldName", m.getGroupValues().get(1));
        assertEquals("newName", m.getGroupValues().get(2));
    }

    @Test
    void rename_doesNotMatchPlainText() {
        assertNull(rename.find("Nothing renamed here", 0));
    }

    // ── DELETE_OK ───────────────────────────────────────────

    @Test
    void deleteOk_matchesAndCapturesName() {
        MatchResult m = deleteOk.find("Safely deleted 'MyClass'", 0);
        assertNotNull(m);
        assertEquals("MyClass", m.getGroupValues().get(1));
    }

    @Test
    void deleteOk_doesNotMatchDeleteFail() {
        assertNull(deleteOk.find("Cannot safely delete 'MyClass'", 0));
    }

    // ── EXTRACT ─────────────────────────────────────────────

    @Test
    void extract_matchesAndCapturesMethodName() {
        MatchResult m = extract.find("Extracted method 'doWork'", 0);
        assertNotNull(m);
        assertEquals("doWork", m.getGroupValues().get(1));
    }

    @Test
    void extract_doesNotMatchUnrelated() {
        assertNull(extract.find("Inlined 'doWork'", 0));
    }

    // ── INLINE ──────────────────────────────────────────────

    @Test
    void inline_matchesAndCapturesMethodName() {
        MatchResult m = inline.find("Inlined 'helperMethod'", 0);
        assertNotNull(m);
        assertEquals("helperMethod", m.getGroupValues().get(1));
    }

    @Test
    void inline_doesNotMatchExtract() {
        assertNull(inline.find("Extracted method 'helperMethod'", 0));
    }

    // ── DELETE_FAIL ─────────────────────────────────────────

    @Test
    void deleteFail_matchesAndCapturesNameAndCount() {
        MatchResult m = deleteFail.find("Cannot safely delete 'MyClass' — it has 3 usages:", 0);
        assertNotNull(m);
        assertEquals("MyClass", m.getGroupValues().get(1));
        assertEquals("3", m.getGroupValues().get(2));
    }

    @Test
    void deleteFail_matchesSingularUsage() {
        MatchResult m = deleteFail.find("Cannot safely delete 'Foo' — it has 1 usage:", 0);
        assertNotNull(m);
        assertEquals("Foo", m.getGroupValues().get(1));
        assertEquals("1", m.getGroupValues().get(2));
    }

    @Test
    void deleteFail_doesNotMatchSafeDelete() {
        assertNull(deleteFail.find("Safely deleted 'MyClass'", 0));
    }

    // ── REF_COUNT ───────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "'Updated 5 references', 5",
        "'Updated 1 reference', 1",
        "'  Updated 3 usages in 2 file(s).', 3",
        "'  Updated 1 usage in 1 file(s).', 1",
    })
    void refCount_capturesCountForEveryWording(String text, String expected) {
        MatchResult m = refCount.find(text, 0);
        assertNotNull(m);
        assertEquals(expected, m.getGroupValues().get(1));
    }

    @Test
    void refCount_doesNotMatchNoNumber() {
        assertNull(refCount.find("No references updated", 0));
    }

    // ── AFFECTED_FILE / NOTE_LINE ───────────────────────────

    @Test
    void affectedFile_matchesListEntry() {
        MatchResult m = RefactorRenderer.INSTANCE.getAFFECTED_FILE().find("  - src/Main.java", 0);
        assertNotNull(m);
        assertEquals("src/Main.java", m.getGroupValues().get(1));
    }

    @Test
    void affectedFile_doesNotMatchFileHeader() {
        assertNull(RefactorRenderer.INSTANCE.getAFFECTED_FILE().find("  File: src/Main.java", 0));
    }

    @Test
    void noteLine_capturesText() {
        MatchResult m = RefactorRenderer.INSTANCE.getNOTE_LINE()
            .find("  Note: search_text_occurrences ignored", 0);
        assertNotNull(m);
        assertEquals("search_text_occurrences ignored", m.getGroupValues().get(1));
    }

    // ── FILE_LINE ───────────────────────────────────────────

    @Test
    void fileLine_matchesAndCapturesPath() {
        MatchResult m = fileLine.find("  File:   src/Main.java", 0);
        assertNotNull(m);
        // Group may include trailing spaces — test trimmed value
        assertEquals("src/Main.java", m.getGroupValues().get(1).trim());
    }

    @Test
    void fileLine_doesNotMatchNoFilePrefix() {
        assertNull(fileLine.find("src/Main.java", 0));
    }

    // ── USAGE_LINE ──────────────────────────────────────────

    @Test
    void usageLine_matchesAndCapturesFileAndLineNumber() {
        MatchResult m = usageLine.find("  src/Caller.java:42", 0);
        assertNotNull(m);
        assertEquals("src/Caller.java", m.getGroupValues().get(1));
        assertEquals("42", m.getGroupValues().get(2));
    }

    @Test
    void usageLine_doesNotMatchNoIndent() {
        assertNull(usageLine.find("src/Caller.java:42", 0));
    }
}

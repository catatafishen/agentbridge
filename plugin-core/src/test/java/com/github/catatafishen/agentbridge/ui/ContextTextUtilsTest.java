package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ContextTextUtils")
class ContextTextUtilsTest {

    /** Unicode Object Replacement Character — same constant used in the production code. */
    private static final char ORC = '\uFFFC';

    private static ContextItemData item(String name) {
        return new ContextItemData("/path/" + name, name, 1, 10, "java", false);
    }

    // ---------------------------------------------------------------
    // replaceOrcsWithTextRefs
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("replaceOrcsWithTextRefs")
    class ReplaceOrcsWithTextRefs {

        @Test
        @DisplayName("text without ORC is returned as-is (trimmed)")
        void noOrcReturnsTextAsIs() {
            String text = "Hello world";
            List<ContextItemData> items = List.of(item("ignored.kt"));
            assertEquals("Hello world",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, items));
        }

        @Test
        @DisplayName("single ORC replaced with backtick-wrapped item name")
        void singleOrcReplacedWithItemName() {
            String text = "Check " + ORC + " for details";
            List<ContextItemData> items = List.of(item("AuthService.kt"));
            assertEquals("Check `AuthService.kt` for details",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, items));
        }

        @Test
        @DisplayName("multiple ORCs replaced in order with corresponding items")
        void multipleOrcsReplacedInOrder() {
            String text = "" + ORC + " and " + ORC + " are related";
            List<ContextItemData> items = Arrays.asList(item("Foo.kt"), item("Bar.kt"));
            assertEquals("`Foo.kt` and `Bar.kt` are related",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, items));
        }

        @Test
        @DisplayName("more ORCs than items — extra ORCs kept as literal characters")
        void moreOrcsThanItemsKeepsExtraOrcs() {
            String text = "" + ORC + " " + ORC + " " + ORC;
            List<ContextItemData> items = List.of(item("Only.kt"));
            // First ORC replaced; remaining two stay as ORC chars
            String result = ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, items);
            assertEquals("`Only.kt` " + ORC + " " + ORC, result);
        }

        @Test
        @DisplayName("fewer ORCs than items — extra items ignored")
        void fewerOrcsThanItemsIgnoresExtraItems() {
            String text = "See " + ORC;
            List<ContextItemData> items = Arrays.asList(item("A.kt"), item("B.kt"), item("C.kt"));
            assertEquals("See `A.kt`",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, items));
        }

        @Test
        @DisplayName("empty items list — ORCs are removed and result is trimmed")
        void emptyItemsRemovesOrcs() {
            String text = "Before " + ORC + " after";
            assertEquals("Before  after",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, Collections.emptyList()));
        }

        @Test
        @DisplayName("empty text returns empty string")
        void emptyTextReturnsEmpty() {
            assertEquals("",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs("", List.of(item("x.kt"))));
        }

        @Test
        @DisplayName("empty text with empty items returns empty string")
        void emptyTextAndEmptyItemsReturnsEmpty() {
            assertEquals("",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs("", Collections.emptyList()));
        }

        @Test
        @DisplayName("result is trimmed — leading/trailing whitespace removed")
        void resultIsTrimmed() {
            String text = "  " + ORC + "  ";
            List<ContextItemData> items = List.of(item("File.kt"));
            assertEquals("`File.kt`",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, items));
        }

        @Test
        @DisplayName("ORC-only text with empty items returns empty string")
        void orcOnlyTextEmptyItemsReturnsEmpty() {
            String text = "" + ORC + ORC + ORC;
            assertEquals("",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, Collections.emptyList()));
        }

        @Test
        @DisplayName("item name with special characters is wrapped correctly")
        void itemNameWithSpecialChars() {
            String text = "See " + ORC;
            List<ContextItemData> items = List.of(item("Service.kt:116-170"));
            assertEquals("See `Service.kt:116-170`",
                    ContextTextUtils.INSTANCE.replaceOrcsWithTextRefs(text, items));
        }
    }

    // ---------------------------------------------------------------
    // normalizedEquals
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("normalizedEquals")
    class NormalizedEquals {

        @Test
        @DisplayName("identical strings return true")
        void identicalStringsReturnTrue() {
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("hello", "hello", 4));
        }

        @Test
        @DisplayName("both empty strings return true")
        void bothEmptyReturnTrue() {
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("", "", 4));
        }

        @Test
        @DisplayName("tab vs equivalent spaces returns true")
        void tabVsSpacesReturnsTrue() {
            // tabSize=4: one tab should equal four spaces
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("\tHello", "    Hello", 4));
        }

        @Test
        @DisplayName("tab vs spaces with tabSize=2")
        void tabVsSpacesTabSizeTwo() {
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("\tX", "  X", 2));
        }

        @Test
        @DisplayName("trailing whitespace difference returns true")
        void trailingWhitespaceReturnsTrue() {
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("line1   ", "line1", 4));
        }

        @Test
        @DisplayName("multiline with mixed tabs and trailing spaces")
        void multilineMixedTabsAndTrailing() {
            String a = "\tline1  \n\t\tline2\t";
            String b = "    line1\n        line2";
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals(a, b, 4));
        }

        @Test
        @DisplayName("actually different content returns false")
        void differentContentReturnsFalse() {
            assertFalse(ContextTextUtils.INSTANCE.normalizedEquals("foo", "bar", 4));
        }

        @Test
        @DisplayName("different line count returns false")
        void differentLineCountReturnsFalse() {
            assertFalse(ContextTextUtils.INSTANCE.normalizedEquals("a\nb", "a", 4));
        }

        @Test
        @DisplayName("tabSize zero is coerced to 1")
        void tabSizeZeroCoercedToOne() {
            // tabSize=0 coerced to 1: tab becomes single space
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("\tX", " X", 0));
        }

        @Test
        @DisplayName("negative tabSize is coerced to 1")
        void negativeTabSizeCoercedToOne() {
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("\tX", " X", -5));
        }

        @Test
        @DisplayName("tab at column 4 with tabSize 4 maps correctly")
        void tabAtColumnFourMapsCorrectly() {
            // "AB\tC" — the tab is replaced by 4 spaces → "AB    C"
            // "AB    C" — 4 literal spaces
            assertTrue(ContextTextUtils.INSTANCE.normalizedEquals("AB\tC", "AB    C", 4));
        }

        @Test
        @DisplayName("leading whitespace difference (non-tab) returns false")
        void leadingSpaceDifferenceReturnsFalse() {
            // leading spaces are preserved (not trimmed), only trailing is stripped
            assertFalse(ContextTextUtils.INSTANCE.normalizedEquals("  X", " X", 4));
        }
    }

    // ---------------------------------------------------------------
    // getMimeTypeForFileType
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("getMimeTypeForFileType")
    class GetMimeTypeForFileType {

        @Test
        @DisplayName("java → text/x-java")
        void javaType() {
            assertEquals("text/x-java",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("java"));
        }

        @Test
        @DisplayName("kotlin → text/x-kotlin")
        void kotlinType() {
            assertEquals("text/x-kotlin",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("kotlin"));
        }

        @Test
        @DisplayName("python → text/x-python")
        void pythonType() {
            assertEquals("text/x-python",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("python"));
        }

        @Test
        @DisplayName("javascript → text/javascript")
        void javascriptType() {
            assertEquals("text/javascript",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("javascript"));
        }

        @Test
        @DisplayName("typescript → text/typescript")
        void typescriptType() {
            assertEquals("text/typescript",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("typescript"));
        }

        @Test
        @DisplayName("xml → text/xml")
        void xmlType() {
            assertEquals("text/xml",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("xml"));
        }

        @Test
        @DisplayName("html → text/html")
        void htmlType() {
            assertEquals("text/html",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("html"));
        }

        @Test
        @DisplayName("unknown file type → text/plain")
        void unknownTypeReturnsPlain() {
            assertEquals("text/plain",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("rust"));
        }

        @Test
        @DisplayName("null → text/plain")
        void nullReturnsPlain() {
            assertEquals("text/plain",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType(null));
        }

        @Test
        @DisplayName("empty string → text/plain")
        void emptyStringReturnsPlain() {
            assertEquals("text/plain",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType(""));
        }

        @Test
        @DisplayName("case-sensitive: JAVA does not match java branch → text/plain")
        void caseSensitiveJava() {
            // The when-expression matches lowercase only
            assertEquals("text/plain",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("JAVA"));
        }

        @Test
        @DisplayName("case-sensitive: Kotlin does not match kotlin branch → text/plain")
        void caseSensitiveKotlin() {
            assertEquals("text/plain",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("Kotlin"));
        }

        @Test
        @DisplayName("case-sensitive: Python does not match python branch → text/plain")
        void caseSensitivePython() {
            assertEquals("text/plain",
                    ContextTextUtils.INSTANCE.getMimeTypeForFileType("Python"));
        }
    }
}

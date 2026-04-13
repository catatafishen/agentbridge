package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PermissionTemplateUtil")
class PermissionTemplateUtilTest {

    // ── substituteArgs ────────────────────────────────────────────────────────

    @Test
    @DisplayName("substitutes a single named placeholder")
    void substituteSingle() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "/foo/bar.kt");
        String result = PermissionTemplateUtil.substituteArgs("Read {path}?", args);
        assertEquals("Read /foo/bar.kt?", result);
    }

    @Test
    @DisplayName("substitutes multiple placeholders")
    void substituteMultiple() {
        JsonObject args = new JsonObject();
        args.addProperty("file", "Main.java");
        args.addProperty("line", "42");
        String result = PermissionTemplateUtil.substituteArgs("Edit {file} at {line}", args);
        assertEquals("Edit Main.java at 42", result);
    }

    @Test
    @DisplayName("leaves unknown placeholders untouched")
    void unknownPlaceholdersUntouched() {
        JsonObject args = new JsonObject();
        args.addProperty("x", "hello");
        String result = PermissionTemplateUtil.substituteArgs("{x} and {y}", args);
        assertEquals("hello and {y}", result);
    }

    @Test
    @DisplayName("null JSON value substitutes as empty string")
    void nullValueSubstitutesEmpty() {
        JsonObject args = new JsonObject();
        args.add("key", com.google.gson.JsonNull.INSTANCE);
        String result = PermissionTemplateUtil.substituteArgs("val={key}", args);
        assertEquals("val=", result);
    }

    @Test
    @DisplayName("long string values are truncated to 60 chars with ellipsis")
    void longValueTruncated() {
        JsonObject args = new JsonObject();
        String longVal = "a".repeat(100);
        args.addProperty("v", longVal);
        String result = PermissionTemplateUtil.substituteArgs("{v}", args);
        assertEquals(58, result.length(), "should be 57 chars + ellipsis character");
        assertTrue(result.endsWith("…"), "should end with ellipsis");
    }

    @Test
    @DisplayName("60-char value is NOT truncated")
    void exactBoundaryNotTruncated() {
        JsonObject args = new JsonObject();
        String exact = "b".repeat(60);
        args.addProperty("v", exact);
        String result = PermissionTemplateUtil.substituteArgs("{v}", args);
        assertEquals(exact, result);
    }

    @Test
    @DisplayName("array values are joined with comma-space")
    void arrayValueJoined() {
        JsonObject args = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add("alpha");
        arr.add("beta");
        args.add("list", arr);
        String result = PermissionTemplateUtil.substituteArgs("{list}", args);
        assertEquals("alpha, beta", result);
    }

    @Test
    @DisplayName("empty template returns empty string")
    void emptyTemplate() {
        String result = PermissionTemplateUtil.substituteArgs("", new JsonObject());
        assertEquals("", result);
    }

    // ── stripPlaceholders ────────────────────────────────────────────────────

    @Test
    @DisplayName("removes placeholder tokens")
    void stripsPlaceholders() {
        String result = PermissionTemplateUtil.stripPlaceholders("Delete {path}?");
        assertEquals("Delete ?", result);
    }

    @Test
    @DisplayName("removes empty parentheses left after strip")
    void stripsEmptyParens() {
        String result = PermissionTemplateUtil.stripPlaceholders("Run ({cmd})?");
        assertEquals("Run ?", result);
    }

    @Test
    @DisplayName("collapses multiple spaces after stripping")
    void collapsesSpaces() {
        String result = PermissionTemplateUtil.stripPlaceholders("a {b}  c");
        assertEquals("a c", result);
    }

    @Test
    @DisplayName("returns null when result is empty after stripping")
    void returnsNullWhenEmpty() {
        assertNull(PermissionTemplateUtil.stripPlaceholders("{placeholder}"));
    }

    @Test
    @DisplayName("returns null for all-whitespace input")
    void returnsNullForWhitespace() {
        assertNull(PermissionTemplateUtil.stripPlaceholders("   "));
    }

    @Test
    @DisplayName("preserves plain text with no placeholders")
    void preservesPlainText() {
        assertEquals("Just text", PermissionTemplateUtil.stripPlaceholders("Just text"));
    }

    // ── substituteArgs — additional edge cases ───────────────────────────────

    @Test
    @DisplayName("JSON object value formatted as toString()")
    void jsonObjectValueFormattedAsToString() {
        JsonObject args = new JsonObject();
        JsonObject nested = new JsonObject();
        nested.addProperty("key", "value");
        args.add("obj", nested);
        String result = PermissionTemplateUtil.substituteArgs("data={obj}", args);
        assertEquals("data={\"key\":\"value\"}", result);
    }

    @Test
    @DisplayName("empty JSON array value substitutes as empty string")
    void emptyArraySubstitutesEmpty() {
        JsonObject args = new JsonObject();
        args.add("list", new com.google.gson.JsonArray());
        String result = PermissionTemplateUtil.substituteArgs("items={list}", args);
        assertEquals("items=", result);
    }

    @Test
    @DisplayName("array with non-primitive elements uses toString()")
    void arrayWithNonPrimitiveUsesToString() {
        JsonObject args = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        JsonObject obj = new JsonObject();
        obj.addProperty("id", 1);
        arr.add(obj);
        arr.add("plain");
        args.add("items", arr);
        String result = PermissionTemplateUtil.substituteArgs("{items}", args);
        assertEquals("{\"id\":1}, plain", result);
    }

    @Test
    @DisplayName("no args provided — template returned as-is")
    void noArgsReturnsTemplateAsIs() {
        String result = PermissionTemplateUtil.substituteArgs("Hello {world}", new JsonObject());
        assertEquals("Hello {world}", result);
    }

    @Test
    @DisplayName("61-char value is truncated")
    void oneCharOverBoundaryTruncated() {
        JsonObject args = new JsonObject();
        String over = "c".repeat(61);
        args.addProperty("v", over);
        String result = PermissionTemplateUtil.substituteArgs("{v}", args);
        assertEquals(58, result.length(), "should be 57 chars + ellipsis character");
        assertTrue(result.endsWith("…"));
        assertTrue(result.startsWith("ccc"));
    }
}

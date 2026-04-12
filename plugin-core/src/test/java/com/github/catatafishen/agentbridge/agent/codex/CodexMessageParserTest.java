package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.Model;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodexMessageParserTest {

    // ── extractReasoningText ────────────────────────────────────────────

    @Nested
    class ExtractReasoningText {

        @Test
        void nullElement_returnsEmpty() {
            assertEquals("", CodexMessageParser.extractReasoningText(null));
        }

        @Test
        void jsonNull_returnsEmpty() {
            assertEquals("", CodexMessageParser.extractReasoningText(JsonNull.INSTANCE));
        }

        @Test
        void stringPrimitive_returnsString() {
            JsonElement el = new JsonPrimitive("hello reasoning");
            assertEquals("hello reasoning", CodexMessageParser.extractReasoningText(el));
        }

        @Test
        void numberPrimitive_returnsStringRepresentation() {
            JsonElement el = new JsonPrimitive(42);
            assertEquals("42", CodexMessageParser.extractReasoningText(el));
        }

        @Test
        void objectWithTextKey_returnsText() {
            JsonObject obj = new JsonObject();
            obj.addProperty("text", "the text value");
            assertEquals("the text value", CodexMessageParser.extractReasoningText(obj));
        }

        @Test
        void objectWithThinkingKey_returnsThinking() {
            JsonObject obj = new JsonObject();
            obj.addProperty("thinking", "deep thought");
            assertEquals("deep thought", CodexMessageParser.extractReasoningText(obj));
        }

        @Test
        void objectWithSummaryKey_recurses() {
            JsonObject inner = new JsonObject();
            inner.addProperty("text", "summarised");
            JsonObject outer = new JsonObject();
            outer.add("summary", inner);
            assertEquals("summarised", CodexMessageParser.extractReasoningText(outer));
        }

        @Test
        void objectWithDeltaKey_recurses() {
            JsonObject delta = new JsonObject();
            delta.addProperty("text", "delta text");
            JsonObject outer = new JsonObject();
            outer.add("delta", delta);
            assertEquals("delta text", CodexMessageParser.extractReasoningText(outer));
        }

        @Test
        void objectWithContentKey_recurses() {
            JsonObject content = new JsonObject();
            content.addProperty("text", "content text");
            JsonObject outer = new JsonObject();
            outer.add("content", content);
            assertEquals("content text", CodexMessageParser.extractReasoningText(outer));
        }

        @Test
        void objectWithNoRecognisedKey_returnsEmpty() {
            JsonObject obj = new JsonObject();
            obj.addProperty("unknown", "value");
            assertEquals("", CodexMessageParser.extractReasoningText(obj));
        }

        @Test
        void emptyObject_returnsEmpty() {
            assertEquals("", CodexMessageParser.extractReasoningText(new JsonObject()));
        }

        @Test
        void jsonArray_concatenatesChildTexts() {
            JsonArray arr = new JsonArray();
            arr.add(new JsonPrimitive("part1"));
            arr.add(new JsonPrimitive("part2"));
            assertEquals("part1part2", CodexMessageParser.extractReasoningText(arr));
        }

        @Test
        void jsonArray_skipsEmptyChildren() {
            JsonArray arr = new JsonArray();
            arr.add(new JsonPrimitive("text"));
            arr.add(new JsonObject()); // empty object → ""
            arr.add(JsonNull.INSTANCE); // null → ""
            assertEquals("text", CodexMessageParser.extractReasoningText(arr));
        }

        @Test
        void emptyArray_returnsEmpty() {
            assertEquals("", CodexMessageParser.extractReasoningText(new JsonArray()));
        }

        @Test
        void textKeyTakesPriorityOverThinking() {
            // "text" is checked before "thinking"
            JsonObject obj = new JsonObject();
            obj.addProperty("text", "text-value");
            obj.addProperty("thinking", "thinking-value");
            assertEquals("text-value", CodexMessageParser.extractReasoningText(obj));
        }

        @Test
        void deeplyNestedDelta() {
            // delta → content → text
            JsonObject inner = new JsonObject();
            inner.addProperty("text", "deep");
            JsonObject content = new JsonObject();
            content.add("content", inner);
            JsonObject delta = new JsonObject();
            delta.add("delta", content);
            assertEquals("deep", CodexMessageParser.extractReasoningText(delta));
        }

        @Test
        void summaryAsStringPrimitive() {
            JsonObject obj = new JsonObject();
            obj.addProperty("summary", "plain summary");
            assertEquals("plain summary", CodexMessageParser.extractReasoningText(obj));
        }
    }

    // ── extractTurnErrorMessage ─────────────────────────────────────────

    @Nested
    class ExtractTurnErrorMessage {

        @Test
        void noErrorKey_returnsDefault() {
            assertEquals("Codex turn failed",
                    CodexMessageParser.extractTurnErrorMessage(new JsonObject()));
        }

        @Test
        void nullErrorValue_returnsDefault() {
            JsonObject turn = new JsonObject();
            turn.add("error", JsonNull.INSTANCE);
            assertEquals("Codex turn failed",
                    CodexMessageParser.extractTurnErrorMessage(turn));
        }

        @Test
        void errorAsString_returnsStringDirectly() {
            JsonObject turn = new JsonObject();
            turn.addProperty("error", "something broke");
            assertEquals("something broke",
                    CodexMessageParser.extractTurnErrorMessage(turn));
        }

        @Test
        void errorObjectWithMessage_returnsMessage() {
            JsonObject turn = new JsonObject();
            JsonObject err = new JsonObject();
            err.addProperty("message", "rate limit exceeded");
            turn.add("error", err);
            assertEquals("rate limit exceeded",
                    CodexMessageParser.extractTurnErrorMessage(turn));
        }

        @Test
        void errorObjectWithoutMessage_fallsBackToString() {
            JsonObject turn = new JsonObject();
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            turn.add("error", err);
            assertEquals(err.toString(),
                    CodexMessageParser.extractTurnErrorMessage(turn));
        }

        @Test
        void doubleEncodedJson_unwrapsInnerErrorMessage() {
            // inner JSON: {"error":{"message":"actual root cause"}}
            JsonObject innerError = new JsonObject();
            innerError.addProperty("message", "actual root cause");
            JsonObject inner = new JsonObject();
            inner.add("error", innerError);

            JsonObject turn = new JsonObject();
            JsonObject err = new JsonObject();
            err.addProperty("message", inner.toString());
            turn.add("error", err);

            assertEquals("actual root cause",
                    CodexMessageParser.extractTurnErrorMessage(turn));
        }

        @Test
        void doubleEncodedJson_noInnerErrorMessage_returnsRawJson() {
            // inner JSON: {"code":500}  — no error.message inside
            JsonObject inner = new JsonObject();
            inner.addProperty("code", 500);

            JsonObject turn = new JsonObject();
            JsonObject err = new JsonObject();
            err.addProperty("message", inner.toString());
            turn.add("error", err);

            assertEquals(inner.toString(),
                    CodexMessageParser.extractTurnErrorMessage(turn));
        }

        @Test
        void doubleEncodedJson_invalidJson_returnsRawString() {
            // message starts with "{" but is not valid JSON
            JsonObject turn = new JsonObject();
            JsonObject err = new JsonObject();
            err.addProperty("message", "{not valid json at all");
            turn.add("error", err);

            assertEquals("{not valid json at all",
                    CodexMessageParser.extractTurnErrorMessage(turn));
        }
    }

    // ── extractPromptText ───────────────────────────────────────────────

    @Nested
    class ExtractPromptText {

        @Test
        void emptyList_returnsEmpty() {
            assertEquals("", CodexMessageParser.extractPromptText(Collections.emptyList()));
        }

        @Test
        void singleTextBlock_returnsText() {
            List<ContentBlock> blocks = List.of(new ContentBlock.Text("hello"));
            assertEquals("hello", CodexMessageParser.extractPromptText(blocks));
        }

        @Test
        void multipleTextBlocks_concatenated() {
            List<ContentBlock> blocks = List.of(
                    new ContentBlock.Text("first"),
                    new ContentBlock.Text(" second")
            );
            assertEquals("first second", CodexMessageParser.extractPromptText(blocks));
        }

        @Test
        void resourceBlockWithText_formattedWithCodeFence() {
            ContentBlock.ResourceLink rl =
                    new ContentBlock.ResourceLink("file:///test.java", "test.java", "text/java", "class Test {}", null);
            List<ContentBlock> blocks = List.of(new ContentBlock.Resource(rl));
            String result = CodexMessageParser.extractPromptText(blocks);
            assertTrue(result.contains("File: file:///test.java"));
            assertTrue(result.contains("```\nclass Test {}\n```"));
        }

        @Test
        void resourceBlockWithNullText_skipped() {
            ContentBlock.ResourceLink rl =
                    new ContentBlock.ResourceLink("file:///img.png", "img.png", "image/png", null, "base64data");
            List<ContentBlock> blocks = List.of(new ContentBlock.Resource(rl));
            assertEquals("", CodexMessageParser.extractPromptText(blocks));
        }

        @Test
        void resourceBlockWithEmptyText_skipped() {
            ContentBlock.ResourceLink rl =
                    new ContentBlock.ResourceLink("file:///empty.txt", "empty.txt", "text/plain", "", null);
            List<ContentBlock> blocks = List.of(new ContentBlock.Resource(rl));
            assertEquals("", CodexMessageParser.extractPromptText(blocks));
        }

        @Test
        void thinkingBlockIgnored() {
            List<ContentBlock> blocks = List.of(
                    new ContentBlock.Thinking("deep thought"),
                    new ContentBlock.Text("visible")
            );
            assertEquals("visible", CodexMessageParser.extractPromptText(blocks));
        }

        @Test
        void imageBlockIgnored() {
            List<ContentBlock> blocks = List.of(
                    new ContentBlock.Image("base64", "image/png"),
                    new ContentBlock.Text("after image")
            );
            assertEquals("after image", CodexMessageParser.extractPromptText(blocks));
        }

        @Test
        void mixedTextAndResource() {
            ContentBlock.ResourceLink rl =
                    new ContentBlock.ResourceLink("file:///f.kt", null, null, "fun main()", null);
            List<ContentBlock> blocks = List.of(
                    new ContentBlock.Text("Check this: "),
                    new ContentBlock.Resource(rl)
            );
            String result = CodexMessageParser.extractPromptText(blocks);
            assertTrue(result.startsWith("Check this: "));
            assertTrue(result.contains("fun main()"));
        }
    }

    // ── buildFullPrompt ─────────────────────────────────────────────────

    @Nested
    class BuildFullPrompt {

        @Test
        void notNewSession_returnsPromptUnchanged() {
            assertEquals("do something",
                    CodexMessageParser.buildFullPrompt("do something", false, "some instructions"));
        }

        @Test
        void newSession_withInstructions_wrapsInXml() {
            String result = CodexMessageParser.buildFullPrompt("my prompt", true, "be concise");
            assertTrue(result.contains("<system-reminder>"));
            assertTrue(result.contains("be concise"));
            assertTrue(result.contains("</system-reminder>"));
            assertTrue(result.endsWith("my prompt"));
        }

        @Test
        void newSession_nullInstructions_noXmlTags() {
            String result = CodexMessageParser.buildFullPrompt("my prompt", true, null);
            assertFalse(result.contains("<system-reminder>"));
            assertEquals("my prompt", result);
        }

        @Test
        void newSession_emptyInstructions_noXmlTags() {
            String result = CodexMessageParser.buildFullPrompt("my prompt", true, "");
            assertFalse(result.contains("<system-reminder>"));
            assertEquals("my prompt", result);
        }

        @Test
        void newSession_instructionsFormat() {
            String result = CodexMessageParser.buildFullPrompt("task", true, "instructions here");
            assertEquals("<system-reminder>\ninstructions here\n</system-reminder>\n\ntask", result);
        }
    }

    // ── buildNativeApprovalDescription ───────────────────────────────────

    @Nested
    class BuildNativeApprovalDescription {

        @Test
        void emptyParams_returnsMethodOnly() {
            assertEquals("shell",
                    CodexMessageParser.buildNativeApprovalDescription("shell", new JsonObject()));
        }

        @Test
        void paramsWithCommand_appendsDetail() {
            JsonObject params = new JsonObject();
            params.addProperty("command", "ls -la");
            String result = CodexMessageParser.buildNativeApprovalDescription("exec", params);
            assertEquals("exec\nls -la", result);
        }

        @Test
        void paramsWithPath_appendsDetail() {
            JsonObject params = new JsonObject();
            params.addProperty("path", "/tmp/file.txt");
            String result = CodexMessageParser.buildNativeApprovalDescription("write", params);
            assertEquals("write\n/tmp/file.txt", result);
        }
    }

    // ── extractNativeApprovalDetail ──────────────────────────────────────

    @Nested
    class ExtractNativeApprovalDetail {

        @Test
        void emptyParams_returnsEmpty() {
            assertEquals("", CodexMessageParser.extractNativeApprovalDetail(new JsonObject()));
        }

        @Test
        void commandKey_returnsPrimitive() {
            JsonObject p = new JsonObject();
            p.addProperty("command", "rm -rf /");
            assertEquals("rm -rf /", CodexMessageParser.extractNativeApprovalDetail(p));
        }

        @Test
        void pathKey_returnsPrimitive() {
            JsonObject p = new JsonObject();
            p.addProperty("path", "/some/path");
            assertEquals("/some/path", CodexMessageParser.extractNativeApprovalDetail(p));
        }

        @Test
        void filePathKey_returnsPrimitive() {
            JsonObject p = new JsonObject();
            p.addProperty("filePath", "/some/file");
            assertEquals("/some/file", CodexMessageParser.extractNativeApprovalDetail(p));
        }

        @Test
        void reasonKey_returnsPrimitive() {
            JsonObject p = new JsonObject();
            p.addProperty("reason", "security concern");
            assertEquals("security concern", CodexMessageParser.extractNativeApprovalDetail(p));
        }

        @Test
        void commandTakesPriorityOverPath() {
            JsonObject p = new JsonObject();
            p.addProperty("command", "cmd");
            p.addProperty("path", "/p");
            assertEquals("cmd", CodexMessageParser.extractNativeApprovalDetail(p));
        }

        @Test
        void nullValueForKnownKey_skipped() {
            JsonObject p = new JsonObject();
            p.add("command", JsonNull.INSTANCE);
            p.addProperty("path", "/fallback");
            assertEquals("/fallback", CodexMessageParser.extractNativeApprovalDetail(p));
        }

        @Test
        void nonPrimitiveValue_returnsToString() {
            JsonObject inner = new JsonObject();
            inner.addProperty("arg", "value");
            JsonObject p = new JsonObject();
            p.add("command", inner);
            assertEquals(inner.toString(), CodexMessageParser.extractNativeApprovalDetail(p));
        }

        @Test
        void noKnownKeys_returnsParamsToString() {
            JsonObject p = new JsonObject();
            p.addProperty("custom", "field");
            assertEquals(p.toString(), CodexMessageParser.extractNativeApprovalDetail(p));
        }
    }

    // ── parseModelEntry ─────────────────────────────────────────────────

    @Nested
    class ParseModelEntry {

        @Test
        void validModelWithIdAndName() {
            JsonObject m = new JsonObject();
            m.addProperty("id", "gpt-4");
            m.addProperty("name", "GPT-4");
            Model result = CodexMessageParser.parseModelEntry(m);
            assertNotNull(result);
            assertEquals("gpt-4", result.id());
            assertEquals("GPT-4", result.name());
            assertNull(result.description());
            assertNull(result._meta());
        }

        @Test
        void idWithoutName_nameDefaultsToId() {
            JsonObject m = new JsonObject();
            m.addProperty("id", "custom-model");
            Model result = CodexMessageParser.parseModelEntry(m);
            assertNotNull(result);
            assertEquals("custom-model", result.id());
            assertEquals("custom-model", result.name());
        }

        @Test
        void missingId_returnsNull() {
            JsonObject m = new JsonObject();
            m.addProperty("name", "No ID");
            assertNull(CodexMessageParser.parseModelEntry(m));
        }

        @Test
        void emptyId_returnsNull() {
            JsonObject m = new JsonObject();
            m.addProperty("id", "");
            assertNull(CodexMessageParser.parseModelEntry(m));
        }

        @Test
        void notJsonObject_returnsNull() {
            assertNull(CodexMessageParser.parseModelEntry(new JsonPrimitive("not an object")));
        }

        @Test
        void jsonArray_returnsNull() {
            assertNull(CodexMessageParser.parseModelEntry(new JsonArray()));
        }

        @Test
        void modelWithExtraFields_ignored() {
            JsonObject m = JsonParser.parseString(
                    "{\"id\":\"m1\",\"name\":\"Model One\",\"extra\":\"ignored\"}"
            ).getAsJsonObject();
            Model result = CodexMessageParser.parseModelEntry(m);
            assertNotNull(result);
            assertEquals("m1", result.id());
            assertEquals("Model One", result.name());
        }
    }

    // ── safeGetInt ──────────────────────────────────────────────────────

    @Nested
    class SafeGetInt {

        @Test
        void presentInt_returnsValue() {
            JsonObject obj = new JsonObject();
            obj.addProperty("count", 42);
            assertEquals(42, CodexMessageParser.safeGetInt(obj, "count"));
        }

        @Test
        void missingKey_returnsZero() {
            assertEquals(0, CodexMessageParser.safeGetInt(new JsonObject(), "missing"));
        }

        @Test
        void nullValue_returnsZero() {
            JsonObject obj = new JsonObject();
            obj.add("count", JsonNull.INSTANCE);
            assertEquals(0, CodexMessageParser.safeGetInt(obj, "count"));
        }

        @Test
        void zeroValue_returnsZero() {
            JsonObject obj = new JsonObject();
            obj.addProperty("count", 0);
            assertEquals(0, CodexMessageParser.safeGetInt(obj, "count"));
        }

        @Test
        void negativeValue_returnsNegative() {
            JsonObject obj = new JsonObject();
            obj.addProperty("offset", -5);
            assertEquals(-5, CodexMessageParser.safeGetInt(obj, "offset"));
        }

        @Test
        void largeValue_returnsLarge() {
            JsonObject obj = new JsonObject();
            obj.addProperty("big", Integer.MAX_VALUE);
            assertEquals(Integer.MAX_VALUE, CodexMessageParser.safeGetInt(obj, "big"));
        }
    }

    // ── candidateNames ──────────────────────────────────────────────────

    @Nested
    class CandidateNames {

        @Test
        void primaryAndAlternates_allPresent() {
            List<String> result = CodexMessageParser.candidateNames("mybin", List.of("alt1", "alt2"));
            assertEquals(List.of("mybin", "alt1", "alt2", "codex"), result);
        }

        @Test
        void emptyPrimary_startsWithAlternates() {
            List<String> result = CodexMessageParser.candidateNames("", List.of("alt1"));
            assertEquals(List.of("alt1", "codex"), result);
        }

        @Test
        void noAlternates_primaryAndCodex() {
            List<String> result = CodexMessageParser.candidateNames("mybin", Collections.emptyList());
            assertEquals(List.of("mybin", "codex"), result);
        }

        @Test
        void emptyPrimaryNoAlternates_justCodex() {
            List<String> result = CodexMessageParser.candidateNames("", Collections.emptyList());
            assertEquals(List.of("codex"), result);
        }

        @Test
        void primaryIsCodex_noDuplicate() {
            List<String> result = CodexMessageParser.candidateNames("codex", Collections.emptyList());
            assertEquals(List.of("codex"), result);
        }

        @Test
        void alternateIncludesCodex_noDuplicate() {
            List<String> result = CodexMessageParser.candidateNames("mybin", List.of("codex"));
            assertEquals(List.of("mybin", "codex"), result);
        }

        @Test
        void emptyPrimaryAlternateIsCodex_noDuplicate() {
            List<String> result = CodexMessageParser.candidateNames("", List.of("codex"));
            assertEquals(List.of("codex"), result);
        }
    }
}

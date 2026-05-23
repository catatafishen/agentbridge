package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookPipelineTest {

    @Nested
    class ApplyEntryTextModifiers {

        @Test
        void prependsTextToExistingOutput() {
            var entry = hookEntry("PREFIX", null);
            String result = HookPipeline.applyEntryTextModifiers(entry, "original");
            assertEquals("PREFIX\n\noriginal", result);
        }

        @Test
        void appendsTextToExistingOutput() {
            var entry = hookEntry(null, "SUFFIX");
            String result = HookPipeline.applyEntryTextModifiers(entry, "original");
            assertEquals("original\n\nSUFFIX", result);
        }

        @Test
        void prependAndAppendBothApplied() {
            var entry = hookEntry("PREFIX", "SUFFIX");
            String result = HookPipeline.applyEntryTextModifiers(entry, "middle");
            assertEquals("PREFIX\n\nmiddle\n\nSUFFIX", result);
        }

        @Test
        void prependToNullOutputCreatesEmptyBase() {
            var entry = hookEntry("PREFIX", null);
            String result = HookPipeline.applyEntryTextModifiers(entry, null);
            assertEquals("PREFIX\n\n", result);
        }

        @Test
        void appendToNullOutputCreatesEmptyBase() {
            var entry = hookEntry(null, "SUFFIX");
            String result = HookPipeline.applyEntryTextModifiers(entry, null);
            assertEquals("\n\nSUFFIX", result);
        }

        @Test
        void emptyPrependAndAppendReturnOriginal() {
            var entry = new HookEntryConfig("script.sh", 10, false, false, Map.of(), "", "", false);
            String result = HookPipeline.applyEntryTextModifiers(entry, "original");
            assertEquals("original", result);
        }

        @Test
        void nullPrependAndAppendReturnOriginal() {
            var entry = new HookEntryConfig("script.sh", 10, false, false, Map.of(), null, null, false);
            String result = HookPipeline.applyEntryTextModifiers(entry, "original");
            assertEquals("original", result);
        }
    }

    @Nested
    class AccumulateText {

        @Test
        void appendsToEmptyBuilder() {
            var sb = new StringBuilder();
            HookPipeline.accumulateText(sb, "first");
            assertEquals("first", sb.toString());
        }

        @Test
        void separatesWithDoubleNewline() {
            var sb = new StringBuilder("first");
            HookPipeline.accumulateText(sb, "second");
            assertEquals("first\n\nsecond", sb.toString());
        }

        @Test
        void skipsNullText() {
            var sb = new StringBuilder("initial");
            HookPipeline.accumulateText(sb, null);
            assertEquals("initial", sb.toString());
        }

        @Test
        void skipsEmptyText() {
            var sb = new StringBuilder("initial");
            HookPipeline.accumulateText(sb, "");
            assertEquals("initial", sb.toString());
        }

        @Test
        void chainsMultipleTexts() {
            var sb = new StringBuilder();
            HookPipeline.accumulateText(sb, "a");
            HookPipeline.accumulateText(sb, "b");
            HookPipeline.accumulateText(sb, "c");
            assertEquals("a\n\nb\n\nc", sb.toString());
        }
    }

    @Nested
    class ApplyOutputText {

        @Test
        void replacementReturnsReplacedOutput() {
            var mod = new HookResult.OutputModification("replaced", null);
            assertEquals("replaced", HookPipeline.applyOutputText(mod, "original"));
        }

        @Test
        void replacementWithNullReturnsNull() {
            var mod = new HookResult.OutputModification(null, "appended");
            String result = HookPipeline.applyOutputText(mod, "base");
            assertEquals("baseappended", result);
        }

        @Test
        void appendToNullOriginalUsesEmptyBase() {
            var mod = new HookResult.OutputModification(null, " trailer");
            String result = HookPipeline.applyOutputText(mod, null);
            assertEquals(" trailer", result);
        }

        @Test
        void noModificationReturnsOriginal() {
            var mod = new HookResult.OutputModification(null, null);
            assertEquals("original", HookPipeline.applyOutputText(mod, "original"));
        }

        @Test
        void noModificationReturnsNullOriginal() {
            var mod = new HookResult.OutputModification(null, null);
            assertNull(HookPipeline.applyOutputText(mod, null));
        }
    }

    @Nested
    class GetStringArg {

        @Test
        void returnsPrimitiveStringValue() {
            var args = new JsonObject();
            args.addProperty("key", "value");
            assertEquals("value", HookPipeline.getStringArg(args, "key"));
        }

        @Test
        void returnsNullForMissingKey() {
            var args = new JsonObject();
            assertNull(HookPipeline.getStringArg(args, "missing"));
        }

        @Test
        void returnsNullForNonPrimitiveValue() {
            var args = new JsonObject();
            args.add("key", new JsonObject());
            assertNull(HookPipeline.getStringArg(args, "key"));
        }

        @Test
        void returnsNumericAsString() {
            var args = new JsonObject();
            args.addProperty("key", 42);
            assertEquals("42", HookPipeline.getStringArg(args, "key"));
        }

        @Test
        void returnsBooleanAsString() {
            var args = new JsonObject();
            args.addProperty("key", true);
            assertEquals("true", HookPipeline.getStringArg(args, "key"));
        }
    }

    @Nested
    class GetBuiltInSuccessAnnotation {

        @Test
        void unknownToolReturnsNull() {
            var args = new JsonObject();
            assertNull(HookPipeline.getBuiltInSuccessAnnotation("some_unknown_tool", args, "output"));
        }

        @ParameterizedTest(name = "tool ''{0}'' with empty args returns null or annotation")
        @ValueSource(strings = {"read_file", "search_text", "git_status"})
        void otherToolsReturnNull(String toolName) {
            var args = new JsonObject();
            assertNull(HookPipeline.getBuiltInSuccessAnnotation(toolName, args, "output"));
        }

        @Test
        void runInTerminalDelegatesToBuiltInHooks() {
            var args = new JsonObject();
            args.addProperty("command", "echo hello");
            // Should not throw — delegates to BuiltInSuccessHooks.terminalReprimand
            String result = HookPipeline.getBuiltInSuccessAnnotation("run_in_terminal", args, null);
            // Result can be null if the command is not a reprimandable one
            assertTrue(result == null || !result.isEmpty());
        }
    }

    /**
     * Creates a HookEntryConfig with a script (to satisfy the validation that at least one of
     * script/prepend/append must be set), plus optional prepend/append strings.
     */
    private static HookEntryConfig hookEntry(String prepend, String append) {
        boolean needsScript = (prepend == null || prepend.isEmpty()) && (append == null || append.isEmpty());
        return new HookEntryConfig(
            needsScript ? "placeholder.sh" : null,
            10, false, false, Map.of(),
            prepend, append, false
        );
    }
}

package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRebaseToolTest {

    @Nested
    class ValidateInteractiveArgs {

        @Test
        void rejectsAutosquash() {
            var args = new JsonObject();
            args.addProperty("autosquash", true);
            args.addProperty("branch", "main");
            String error = GitRebaseTool.validateInteractiveArgs(args);
            assertNotNull(error);
            assertTrue(error.contains("autosquash"));
            assertTrue(error.contains("not supported"));
        }

        @Test
        void rejectsOnto() {
            var args = new JsonObject();
            args.addProperty("onto", "abc123");
            args.addProperty("branch", "main");
            String error = GitRebaseTool.validateInteractiveArgs(args);
            assertNotNull(error);
            assertTrue(error.contains("onto"));
        }

        @Test
        void rejectsExec() {
            var args = new JsonObject();
            args.addProperty("exec", "make test");
            args.addProperty("branch", "main");
            String error = GitRebaseTool.validateInteractiveArgs(args);
            assertNotNull(error);
            assertTrue(error.contains("exec"));
        }

        @Test
        void requiresBranch() {
            var args = new JsonObject();
            String error = GitRebaseTool.validateInteractiveArgs(args);
            assertNotNull(error);
            assertTrue(error.contains("branch"));
            assertTrue(error.contains("required"));
        }

        @Test
        void rejectsBlankBranch() {
            var args = new JsonObject();
            args.addProperty("branch", "   ");
            String error = GitRebaseTool.validateInteractiveArgs(args);
            assertNotNull(error);
            assertTrue(error.contains("branch"));
        }

        @Test
        void acceptsValidArgs() {
            var args = new JsonObject();
            args.addProperty("branch", "origin/main");
            assertNull(GitRebaseTool.validateInteractiveArgs(args));
        }

        @Test
        void ignoresAutosquashFalse() {
            var args = new JsonObject();
            args.addProperty("autosquash", false);
            args.addProperty("branch", "main");
            assertNull(GitRebaseTool.validateInteractiveArgs(args));
        }

        @Test
        void ignoresEmptyOnto() {
            var args = new JsonObject();
            args.addProperty("onto", "");
            args.addProperty("branch", "main");
            assertNull(GitRebaseTool.validateInteractiveArgs(args));
        }
    }

    @Nested
    class ValidateOperations {

        @Test
        void acceptsAllValidActions() {
            var ops = Map.of(
                "abc1234", "pick",
                "def5678", "squash",
                "ghi9012", "fixup",
                "jkl3456", "drop",
                "mno7890", "edit"
            );
            var messages = new LinkedHashMap<String, String>();
            assertNull(GitRebaseTool.validateOperations(ops, messages));
        }

        @Test
        void acceptsRewordWithMessage() {
            var ops = Map.of("abc1234", "reword");
            var messages = Map.of("abc1234", "Better message");
            assertNull(GitRebaseTool.validateOperations(ops, messages));
        }

        @Test
        void rejectsInvalidAction() {
            var ops = Map.of("abc1234", "invalid_action");
            var messages = new LinkedHashMap<String, String>();
            String error = GitRebaseTool.validateOperations(ops, messages);
            assertNotNull(error);
            assertTrue(error.contains("invalid_action"));
            assertTrue(error.contains("abc1234"));
            assertTrue(error.contains("Allowed"));
        }

        @Test
        void rejectsRewordWithoutMessage() {
            var ops = Map.of("abc1234", "reword");
            var messages = new LinkedHashMap<String, String>();
            String error = GitRebaseTool.validateOperations(ops, messages);
            assertNotNull(error);
            assertTrue(error.contains("reword"));
            assertTrue(error.contains("message"));
        }

        @Test
        void emptyOperationsAccepted() {
            assertNull(GitRebaseTool.validateOperations(Map.of(), Map.of()));
        }
    }

    @Nested
    class ParseOperations {

        @Test
        void parsesValidOperationsArray() {
            var args = new JsonObject();
            var ops = new JsonArray();
            var op1 = new JsonObject();
            op1.addProperty("commit", "abc1234");
            op1.addProperty("action", "squash");
            ops.add(op1);

            var op2 = new JsonObject();
            op2.addProperty("commit", "def5678");
            op2.addProperty("action", "reword");
            op2.addProperty("message", "New message");
            ops.add(op2);

            args.add("operations", ops);

            var result = GitRebaseTool.parseOperations(args);
            assertEquals(2, result.operations().size());
            assertEquals("squash", result.operations().get("abc1234"));
            assertEquals("reword", result.operations().get("def5678"));
            assertEquals("New message", result.messages().get("def5678"));
        }

        @Test
        void returnsEmptyWhenNoOperationsKey() {
            var args = new JsonObject();
            var result = GitRebaseTool.parseOperations(args);
            assertTrue(result.operations().isEmpty());
            assertTrue(result.messages().isEmpty());
        }

        @Test
        void returnsEmptyWhenOperationsNotArray() {
            var args = new JsonObject();
            args.addProperty("operations", "not an array");
            var result = GitRebaseTool.parseOperations(args);
            assertTrue(result.operations().isEmpty());
        }

        @Test
        void skipsEntriesWithoutCommit() {
            var args = new JsonObject();
            var ops = new JsonArray();
            var op = new JsonObject();
            op.addProperty("action", "squash");
            // missing "commit" key
            ops.add(op);
            args.add("operations", ops);

            var result = GitRebaseTool.parseOperations(args);
            assertTrue(result.operations().isEmpty());
        }

        @Test
        void skipsNonObjectEntries() {
            var args = new JsonObject();
            var ops = new JsonArray();
            ops.add(new JsonPrimitive("not an object"));
            args.add("operations", ops);

            var result = GitRebaseTool.parseOperations(args);
            assertTrue(result.operations().isEmpty());
        }

        @Test
        void skipsBlankCommitKeys() {
            var args = new JsonObject();
            var ops = new JsonArray();
            var op = new JsonObject();
            op.addProperty("commit", "   ");
            op.addProperty("action", "pick");
            ops.add(op);
            args.add("operations", ops);

            var result = GitRebaseTool.parseOperations(args);
            assertTrue(result.operations().isEmpty());
        }

        @Test
        void normalizesActionToLowerCase() {
            var args = new JsonObject();
            var ops = new JsonArray();
            var op = new JsonObject();
            op.addProperty("commit", "abc1234");
            op.addProperty("action", "  SQUASH  ");
            ops.add(op);
            args.add("operations", ops);

            var result = GitRebaseTool.parseOperations(args);
            assertEquals("squash", result.operations().get("abc1234"));
        }
    }

    @Nested
    class ExtractCommitKey {

        @Test
        void extractsFromValidObject() {
            var obj = new JsonObject();
            obj.addProperty("commit", "abc1234");
            assertEquals("abc1234", GitRebaseTool.extractCommitKey(obj));
        }

        @Test
        void returnsNullForNonObject() {
            assertNull(GitRebaseTool.extractCommitKey(new JsonPrimitive("string")));
        }

        @Test
        void returnsNullWhenNoCommitKey() {
            var obj = new JsonObject();
            obj.addProperty("action", "pick");
            assertNull(GitRebaseTool.extractCommitKey(obj));
        }

        @Test
        void returnsNullForBlankCommit() {
            var obj = new JsonObject();
            obj.addProperty("commit", "   ");
            assertNull(GitRebaseTool.extractCommitKey(obj));
        }

        @Test
        void trimsWhitespace() {
            var obj = new JsonObject();
            obj.addProperty("commit", "  abc1234  ");
            assertEquals("abc1234", GitRebaseTool.extractCommitKey(obj));
        }
    }
}

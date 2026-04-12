package com.github.catatafishen.agentbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProfileBasedAgentConfig — static parsing helpers")
class ProfileBasedAgentConfigTest {

    // ── parseStandardAuthMethod ───────────────────────────────────────────────

    @Nested
    @DisplayName("parseStandardAuthMethod")
    class ParseStandardAuthMethod {

        @Test
        @DisplayName("null input returns null")
        void null_returnsNull() {
            assertNull(ProfileBasedAgentConfig.parseStandardAuthMethod(null));
        }

        @Test
        @DisplayName("empty JsonArray returns null")
        void emptyArray_returnsNull() {
            assertNull(ProfileBasedAgentConfig.parseStandardAuthMethod(new JsonArray()));
        }

        @Test
        @DisplayName("array with one entry: id, name, description extracted")
        void oneEntry_extractsIdNameDescription() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "terminal");
            entry.addProperty("name", "Terminal Auth");
            entry.addProperty("description", "Authenticate via terminal prompt");
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("terminal", result.getId());
            assertEquals("Terminal Auth", result.getName());
            assertEquals("Authenticate via terminal prompt", result.getDescription());
        }

        @Test
        @DisplayName("only first array entry is used")
        void onlyFirstEntryUsed() {
            JsonArray array = new JsonArray();
            JsonObject first = new JsonObject();
            first.addProperty("id", "first");
            first.addProperty("name", "First Method");
            first.addProperty("description", "");
            JsonObject second = new JsonObject();
            second.addProperty("id", "second");
            second.addProperty("name", "Second Method");
            second.addProperty("description", "");
            array.add(first);
            array.add(second);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("first", result.getId());
            assertEquals("First Method", result.getName());
        }

        @Test
        @DisplayName("missing id field defaults to empty string")
        void missingId_defaultsToEmpty() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("name", "Some Name");
            entry.addProperty("description", "Some desc");
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("", result.getId());
        }

        @Test
        @DisplayName("missing name field defaults to empty string")
        void missingName_defaultsToEmpty() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "my-id");
            entry.addProperty("description", "desc");
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("", result.getName());
        }

        @Test
        @DisplayName("missing description field defaults to empty string")
        void missingDescription_defaultsToEmpty() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "my-id");
            entry.addProperty("name", "My Name");
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("", result.getDescription());
        }

        @Test
        @DisplayName("completely empty entry: all fields default to empty string")
        void emptyEntry_allDefaultToEmpty() {
            JsonArray array = new JsonArray();
            array.add(new JsonObject());

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("", result.getId());
            assertEquals("", result.getName());
            assertEquals("", result.getDescription());
        }

        @Test
        @DisplayName("no _meta field: command and args are null")
        void noMetaField_commandAndArgsNull() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "test");
            entry.addProperty("name", "Test");
            entry.addProperty("description", "");
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertNull(result.getCommand());
            assertNull(result.getArgs());
        }
    }

    // ── parseTerminalAuthFromMeta (tested via parseStandardAuthMethod) ─────────

    @Nested
    @DisplayName("terminal-auth via _meta (tested through parseStandardAuthMethod)")
    class TerminalAuthFromMeta {

        @Test
        @DisplayName("_meta without terminal-auth: method unchanged (command stays null)")
        void metaWithoutTerminalAuth_commandNull() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "test");
            JsonObject meta = new JsonObject();
            meta.addProperty("other-field", "some-value");
            entry.add("_meta", meta);
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertNull(result.getCommand());
            assertNull(result.getArgs());
        }

        @Test
        @DisplayName("terminal-auth with command and args: both set on method")
        void terminalAuth_commandAndArgs_bothSet() {
            JsonArray array = new JsonArray();
            JsonObject entry = buildEntryWithTerminalAuth("my-auth-cmd", List.of("--flag", "value", "--other"));
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("my-auth-cmd", result.getCommand());
            assertNotNull(result.getArgs());
            assertEquals(3, result.getArgs().size());
            assertEquals("--flag", result.getArgs().get(0));
            assertEquals("value", result.getArgs().get(1));
            assertEquals("--other", result.getArgs().get(2));
        }

        @Test
        @DisplayName("terminal-auth with command but no args: command set, args remain null")
        void terminalAuth_commandOnly_argsNull() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "test");
            entry.addProperty("name", "Test");
            entry.addProperty("description", "");
            JsonObject termAuth = new JsonObject();
            termAuth.addProperty("command", "only-command");
            JsonObject meta = new JsonObject();
            meta.add("terminal-auth", termAuth);
            entry.add("_meta", meta);
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("only-command", result.getCommand());
            assertNull(result.getArgs());
        }

        @Test
        @DisplayName("terminal-auth with empty args array: args is empty list")
        void terminalAuth_emptyArgsArray_emptyList() {
            JsonArray array = new JsonArray();
            JsonObject entry = buildEntryWithTerminalAuth("cmd", List.of());
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("cmd", result.getCommand());
            assertNotNull(result.getArgs());
            assertTrue(result.getArgs().isEmpty());
        }

        @Test
        @DisplayName("terminal-auth without command key: command is null")
        void terminalAuth_noCommandKey_commandNull() {
            JsonArray array = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "test");
            entry.addProperty("name", "Test");
            entry.addProperty("description", "");
            JsonObject termAuth = new JsonObject();
            JsonArray args = new JsonArray();
            args.add("--arg");
            termAuth.add("args", args);
            JsonObject meta = new JsonObject();
            meta.add("terminal-auth", termAuth);
            entry.add("_meta", meta);
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertNull(result.getCommand());
        }

        @Test
        @DisplayName("terminal-auth with single-element args: list has one entry")
        void terminalAuth_singleArg() {
            JsonArray array = new JsonArray();
            JsonObject entry = buildEntryWithTerminalAuth("cli-tool", List.of("--only-flag"));
            array.add(entry);

            AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(array);

            assertNotNull(result);
            assertEquals("cli-tool", result.getCommand());
            assertEquals(1, result.getArgs().size());
            assertEquals("--only-flag", result.getArgs().getFirst());
        }

        // ── helpers ──────────────────────────────────────────────────────────

        private JsonObject buildEntryWithTerminalAuth(String command, List<String> argsList) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "test");
            entry.addProperty("name", "Test");
            entry.addProperty("description", "");
            JsonObject termAuth = new JsonObject();
            termAuth.addProperty("command", command);
            JsonArray args = new JsonArray();
            for (String a : argsList) {
                args.add(a);
            }
            termAuth.add("args", args);
            JsonObject meta = new JsonObject();
            meta.add("terminal-auth", termAuth);
            entry.add("_meta", meta);
            return entry;
        }
    }
}

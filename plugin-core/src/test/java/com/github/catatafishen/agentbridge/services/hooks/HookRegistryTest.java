package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookRegistryTest {

    private static final Path HOOKS_DIR = Path.of("/storage/hooks");

    @Test
    void parseToolConfig_successHookWithAllFields() {
        String json = """
            {
              "success": [
                {
                  "script": "scripts/bot-identity-reminder.sh",
                  "timeout": 15,
                  "failSilently": true,
                  "async": false,
                  "env": {"CUSTOM_VAR": "value"}
                }
              ]
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ToolHookConfig config = HookRegistry.parseToolConfig("git_commit", root, HOOKS_DIR);

        assertEquals("git_commit", config.toolId());
        assertTrue(config.hasTrigger(HookTrigger.SUCCESS));
        assertFalse(config.hasTrigger(HookTrigger.PERMISSION));
        assertFalse(config.hasTrigger(HookTrigger.PRE));
        assertFalse(config.hasTrigger(HookTrigger.FAILURE));

        List<HookEntryConfig> entries = config.entriesFor(HookTrigger.SUCCESS);
        assertEquals(1, entries.size());

        HookEntryConfig entry = entries.getFirst();
        assertEquals("scripts/bot-identity-reminder.sh", entry.script());
        assertEquals(15, entry.timeout());
        assertTrue(entry.failSilently());
        assertFalse(entry.async());
        assertEquals("value", entry.env().get("CUSTOM_VAR"));
    }

    @Test
    void parseToolConfig_multipleTriggersWithChaining() {
        String json = """
            {
              "permission": [
                {
                  "script": "check-allowed.sh",
                  "timeout": 5,
                  "rejectOnFailure": true
                }
              ],
              "success": [
                {
                  "script": "first-hook.sh",
                  "failSilently": true
                },
                {
                  "script": "second-hook.sh",
                  "failSilently": false,
                  "async": true
                }
              ]
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ToolHookConfig config = HookRegistry.parseToolConfig("run_command", root, HOOKS_DIR);

        assertTrue(config.hasTrigger(HookTrigger.PERMISSION));
        assertTrue(config.hasTrigger(HookTrigger.SUCCESS));
        assertFalse(config.hasTrigger(HookTrigger.PRE));
        assertFalse(config.hasTrigger(HookTrigger.FAILURE));

        List<HookEntryConfig> permEntries = config.entriesFor(HookTrigger.PERMISSION);
        assertEquals(1, permEntries.size());
        assertEquals("check-allowed.sh", permEntries.getFirst().script());
        assertEquals(5, permEntries.getFirst().timeout());
        assertFalse(permEntries.getFirst().failSilently()); // rejectOnFailure=true → failSilently=false

        List<HookEntryConfig> successEntries = config.entriesFor(HookTrigger.SUCCESS);
        assertEquals(2, successEntries.size());
        assertEquals("first-hook.sh", successEntries.getFirst().script());
        assertTrue(successEntries.getFirst().failSilently());
        assertFalse(successEntries.getFirst().async());
        assertEquals("second-hook.sh", successEntries.get(1).script());
        assertFalse(successEntries.get(1).failSilently());
        assertTrue(successEntries.get(1).async());
    }

    @Test
    void parseToolConfig_defaultsApplied() {
        String json = """
            {
              "success": [
                {
                  "script": "my-hook.sh"
                }
              ]
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ToolHookConfig config = HookRegistry.parseToolConfig("read_file", root, HOOKS_DIR);

        List<HookEntryConfig> entries = config.entriesFor(HookTrigger.SUCCESS);
        assertEquals(1, entries.size());

        HookEntryConfig entry = entries.getFirst();
        assertEquals(10, entry.timeout()); // default
        assertTrue(entry.failSilently()); // default for non-permission
        assertFalse(entry.async()); // default
        assertTrue(entry.env().isEmpty());
    }

    @Test
    void parseToolConfig_permissionDefaultsRejectOnFailure() {
        String json = """
            {
              "permission": [
                {
                  "script": "security-check.sh"
                }
              ]
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ToolHookConfig config = HookRegistry.parseToolConfig("run_command", root, HOOKS_DIR);

        List<HookEntryConfig> entries = config.entriesFor(HookTrigger.PERMISSION);
        assertEquals(1, entries.size());
        // Default: rejectOnFailure=true → failSilently=false
        assertFalse(entries.getFirst().failSilently());
    }

    @Test
    void resolveScript_returnsPathRelativeToHooksDir() {
        String json = """
            {
              "pre": [
                {
                  "script": "scripts/pre-hook.sh"
                }
              ]
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ToolHookConfig config = HookRegistry.parseToolConfig("git_push", root, HOOKS_DIR);

        HookEntryConfig entry = config.entriesFor(HookTrigger.PRE).getFirst();
        Path resolved = config.resolveScript(entry);
        assertNotNull(resolved);
        assertEquals(HOOKS_DIR.resolve("scripts/pre-hook.sh"), resolved);
    }

    @Test
    void parseToolConfig_allFourTriggers() {
        String json = """
            {
              "permission": [{"script": "perm.sh"}],
              "pre": [{"script": "pre.sh"}],
              "success": [{"script": "success.sh"}],
              "failure": [{"script": "failure.sh"}]
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ToolHookConfig config = HookRegistry.parseToolConfig("write_file", root, HOOKS_DIR);

        assertTrue(config.hasTrigger(HookTrigger.PERMISSION));
        assertTrue(config.hasTrigger(HookTrigger.PRE));
        assertTrue(config.hasTrigger(HookTrigger.SUCCESS));
        assertTrue(config.hasTrigger(HookTrigger.FAILURE));
    }

    @Test
    void triggerFromJsonKey_roundTrips() {
        for (HookTrigger trigger : HookTrigger.values()) {
            assertEquals(trigger, HookTrigger.fromJsonKey(trigger.jsonKey()));
        }
    }
}

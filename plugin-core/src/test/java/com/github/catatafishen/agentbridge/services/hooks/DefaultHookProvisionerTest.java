package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static utility methods in {@link DefaultHookProvisioner}.
 */
class DefaultHookProvisionerTest {

    @Nested
    class BuildJsonConfigs {
        @Test
        void containsRunCommandConfig() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            assertTrue(configs.containsKey("run_command.json"));
        }

        @Test
        void containsRunInTerminalConfig() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            assertTrue(configs.containsKey("run_in_terminal.json"));
        }

        @Test
        void containsWriteFileConfig() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            assertTrue(configs.containsKey("write_file.json"));
        }

        @Test
        void exactlyThreeConfigs() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            assertEquals(3, configs.size());
        }

        @Test
        void runCommandHasPermissionHook() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            JsonObject obj = JsonParser.parseString(configs.get("run_command.json")).getAsJsonObject();
            assertTrue(obj.has("permission"));
            JsonArray hooks = obj.getAsJsonArray("permission");
            assertEquals(1, hooks.size());
            JsonObject hook = hooks.get(0).getAsJsonObject();
            assertTrue(hook.get("script").getAsString().contains("run-command-abuse"));
            assertTrue(hook.get("rejectOnFailure").getAsBoolean());
        }

        @Test
        void runInTerminalHasPermissionAndSuccess() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            JsonObject obj = JsonParser.parseString(configs.get("run_in_terminal.json")).getAsJsonObject();
            assertTrue(obj.has("permission"));
            assertTrue(obj.has("success"));
        }

        @Test
        void writeFileHasSuccessHook() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            JsonObject obj = JsonParser.parseString(configs.get("write_file.json")).getAsJsonObject();
            assertFalse(obj.has("permission"));
            assertTrue(obj.has("success"));
            JsonArray hooks = obj.getAsJsonArray("success");
            assertEquals(1, hooks.size());
            assertTrue(hooks.get(0).getAsJsonObject().get("script").getAsString().contains("check-stale-naming"));
        }

        @Test
        void allConfigsAreValidJson() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                assertDoesNotThrow(() -> JsonParser.parseString(entry.getValue()),
                    "Invalid JSON in " + entry.getKey());
            }
        }

        @Test
        void scriptExtIsConsistent() {
            Map<String, String> configs = DefaultHookProvisioner.buildJsonConfigs();
            for (String value : configs.values()) {
                JsonObject obj = JsonParser.parseString(value).getAsJsonObject();
                if (obj.has("permission")) {
                    for (var rec : obj.getAsJsonArray("permission")) {
                        String script = rec.getAsJsonObject().get("script").getAsString();
                        assertTrue(script.startsWith("scripts/"), "Script should be in scripts/ dir: " + script);
                    }
                }
                if (obj.has("success")) {
                    for (var rec : obj.getAsJsonArray("success")) {
                        String script = rec.getAsJsonObject().get("script").getAsString();
                        assertTrue(script.startsWith("scripts/"), "Script should be in scripts/ dir: " + script);
                    }
                }
            }
        }
    }
}

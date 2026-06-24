package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bundled hook resources shipped under {@code /default-hooks/}.
 *
 * <p>The JSON tool configs are plain, user-editable resource files (no longer generated at
 * runtime), so these tests load them straight from the classpath and assert their shape.
 */
class DefaultHookProvisionerTest {

    private static final String RESOURCE_BASE = "/default-hooks/";
    private static final List<String> JSON_CONFIGS =
        List.of("run_command.json", "run_in_terminal.json", "write_file.json");

    private static String loadConfig(String name) {
        try (InputStream is = DefaultHookProvisionerTest.class.getResourceAsStream(RESOURCE_BASE + name)) {
            assertNotNull(is, "Bundled resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fail("Failed to read bundled resource: " + name, e);
        }
    }

    private static String loadManifest() {
        return loadConfig("manifest.txt");
    }

    @Nested
    class BundledJsonConfigs {
        @ParameterizedTest
        @ValueSource(strings = {"run_command.json", "run_in_terminal.json", "write_file.json"})
        void configResourceExists(String key) {
            assertFalse(loadConfig(key).isBlank(), "Empty config: " + key);
        }

        @Test
        void manifestListsExactlyThreeJsonConfigs() {
            long count = loadManifest().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .filter(line -> line.endsWith(".json"))
                .count();
            assertEquals(3, count);
        }

        @Test
        void manifestListsEveryJsonConfig() {
            String manifest = loadManifest();
            for (String name : JSON_CONFIGS) {
                assertTrue(manifest.lines().map(String::trim).anyMatch(name::equals),
                    "manifest.txt is missing " + name);
            }
        }

        @Test
        void runCommandHasPermissionHook() {
            JsonObject obj = JsonParser.parseString(loadConfig("run_command.json")).getAsJsonObject();
            assertTrue(obj.has("permission"));
            JsonArray hooks = obj.getAsJsonArray("permission");
            assertEquals(1, hooks.size());
            JsonObject hook = hooks.get(0).getAsJsonObject();
            assertTrue(hook.get("script").getAsString().contains("run-command-abuse"));
            assertTrue(hook.get("rejectOnFailure").getAsBoolean());
        }

        @Test
        void runInTerminalHasPermissionAndSuccess() {
            JsonObject obj = JsonParser.parseString(loadConfig("run_in_terminal.json")).getAsJsonObject();
            assertTrue(obj.has("permission"));
            assertTrue(obj.has("success"));
        }

        @Test
        void writeFileHasSuccessHook() {
            JsonObject obj = JsonParser.parseString(loadConfig("write_file.json")).getAsJsonObject();
            assertFalse(obj.has("permission"));
            assertTrue(obj.has("success"));
            JsonArray hooks = obj.getAsJsonArray("success");
            assertEquals(1, hooks.size());
            assertTrue(hooks.get(0).getAsJsonObject().get("script").getAsString().contains("check-stale-naming"));
        }

        @Test
        void allConfigsAreValidJson() {
            for (String name : JSON_CONFIGS) {
                assertDoesNotThrow(() -> JsonParser.parseString(loadConfig(name)),
                    "Invalid JSON in " + name);
            }
        }

        @Test
        void scriptExtIsConsistent() {
            for (String name : JSON_CONFIGS) {
                JsonObject obj = JsonParser.parseString(loadConfig(name)).getAsJsonObject();
                assertScriptsInScriptsDir(obj, "permission");
                assertScriptsInScriptsDir(obj, "success");
            }
        }

        private void assertScriptsInScriptsDir(JsonObject obj, String section) {
            if (!obj.has(section)) return;
            for (var rec : obj.getAsJsonArray(section)) {
                String script = rec.getAsJsonObject().get("script").getAsString();
                assertTrue(script.startsWith("scripts/"), "Script should be in scripts/ dir: " + script);
            }
        }
    }
}

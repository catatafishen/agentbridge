package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the bundled hook resources shipped under {@code /default-hooks/}.
 *
 * <p>The JSON tool configs are plain, user-editable resource files, so these tests load them
 * directly from the classpath and assert their shape and project-independent scope.
 */
class DefaultHookProvisionerTest {

    private static final String RESOURCE_BASE = "/default-hooks/";
    private static final List<String> JSON_CONFIGS =
        List.of("run_command.json", "run_in_terminal.json", "write_file.json");
    private static final Set<String> BUNDLED_ENTRIES = Set.of(
        "scripts/_lib.js",
        "scripts/run-command-abuse.js",
        "scripts/run-in-terminal-abort.js",
        "scripts/command-reprimand.js",
        "scripts/check-stale-naming.js",
        "run_command.json",
        "run_in_terminal.json",
        "write_file.json"
    );
    private static final List<String> PROJECT_ONLY_MARKERS = List.of(
        "AGENTBRIDGE_BOT_TOKEN",
        "github-app.pem",
        "repository bot identity",
        "agentbridge-fixer",
        "pr-creation-tip",
        "pr-description-reminder",
        "bot-identity-reminder",
        "enforce-commit-author",
        "commit-message-quality-reminder",
        "build-project-clear-cache",
        "enforce-http-bot-identity"
    );

    private static String loadResource(String name) {
        try (InputStream is = DefaultHookProvisionerTest.class.getResourceAsStream(RESOURCE_BASE + name)) {
            assertNotNull(is, "Bundled resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fail("Failed to read bundled resource: " + name, e);
        }
    }

    private static List<String> loadResourceEntries(String name) {
        return loadResource(name).lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }

    @Nested
    class BundledJsonConfigs {
        @ParameterizedTest
        @ValueSource(strings = {"run_command.json", "run_in_terminal.json", "write_file.json"})
        void configResourceExists(String key) {
            assertFalse(loadResource(key).isBlank(), "Empty config: " + key);
        }

        @Test
        void manifestListsOnlyGenericHookResources() {
            assertEquals(BUNDLED_ENTRIES, Set.copyOf(loadResourceEntries("manifest.txt")));
        }

        @Test
        void commandConfigsDoNotInstallProjectIdentityHooks() {
            for (String configName : List.of("run_command.json", "run_in_terminal.json")) {
                JsonObject config = JsonParser.parseString(loadResource(configName)).getAsJsonObject();
                assertFalse(config.has("pre"), configName + " must not install project identity hooks");
            }
        }

        @Test
        void projectIdentityResourcesAreNotBundled() {
            assertNull(DefaultHookProvisionerTest.class.getResource(
                RESOURCE_BASE + "scripts/enforce-gh-bot-identity.js"));
            assertNull(DefaultHookProvisionerTest.class.getResource(
                RESOURCE_BASE + "scripts/generate-github-app-token.sh"));
        }

        @Test
        void bundledResourcesContainNoProjectIdentityPolicy() {
            for (String entry : BUNDLED_ENTRIES) {
                String content = loadResource(entry);
                for (String marker : PROJECT_ONLY_MARKERS) {
                    assertFalse(content.contains(marker), entry + " leaks project-only marker: " + marker);
                }
            }
        }

        @Test
        void retiredListTracksPreviouslyLeakedResources() {
            assertEquals(Set.of(
                "scripts/enforce-gh-bot-identity.js",
                "scripts/generate-github-app-token.sh"
            ), Set.copyOf(loadResourceEntries("retired.txt")));
        }

        @Test
        void runCommandHasPermissionHook() {
            JsonObject obj = JsonParser.parseString(loadResource("run_command.json")).getAsJsonObject();
            assertTrue(obj.has("permission"));
            JsonArray hooks = obj.getAsJsonArray("permission");
            assertEquals(1, hooks.size());
            JsonObject hook = hooks.get(0).getAsJsonObject();
            assertTrue(hook.get("script").getAsString().contains("run-command-abuse"));
            assertTrue(hook.get("rejectOnFailure").getAsBoolean());
        }

        @Test
        void runInTerminalHasPermissionAndSuccess() {
            JsonObject obj = JsonParser.parseString(loadResource("run_in_terminal.json")).getAsJsonObject();
            assertTrue(obj.has("permission"));
            assertTrue(obj.has("success"));
        }

        @Test
        void writeFileHasSuccessHook() {
            JsonObject obj = JsonParser.parseString(loadResource("write_file.json")).getAsJsonObject();
            assertFalse(obj.has("permission"));
            assertTrue(obj.has("success"));
            JsonArray hooks = obj.getAsJsonArray("success");
            assertEquals(1, hooks.size());
            assertTrue(hooks.get(0).getAsJsonObject().get("script").getAsString().contains("check-stale-naming"));
        }

        @Test
        void allConfigsAreValidJson() {
            for (String name : JSON_CONFIGS) {
                assertDoesNotThrow(() -> JsonParser.parseString(loadResource(name)),
                    "Invalid JSON in " + name);
            }
        }

        @Test
        void scriptPathsAreConsistent() {
            for (String name : JSON_CONFIGS) {
                JsonObject obj = JsonParser.parseString(loadResource(name)).getAsJsonObject();
                assertScriptsInScriptsDir(obj, "permission");
                assertScriptsInScriptsDir(obj, "pre");
                assertScriptsInScriptsDir(obj, "success");
            }
        }

        private void assertScriptsInScriptsDir(JsonObject obj, String section) {
            if (!obj.has(section)) return;
            for (var hookElement : obj.getAsJsonArray(section)) {
                String script = hookElement.getAsJsonObject().get("script").getAsString();
                assertTrue(script.startsWith("scripts/"), "Script should be in scripts/ dir: " + script);
            }
        }
    }

    @Nested
    class RetiredHooks {
        @Test
        void noHashReprovisionPreservesRetiredFile(@TempDir Path hooksDir) throws Exception {
            Path retiredFile = hooksDir.resolve("scripts/enforce-gh-bot-identity.js");
            Files.createDirectories(retiredFile.getParent());
            Files.writeString(retiredFile, "custom project policy");

            assertTrue(DefaultHookProvisioner.wipeThenProvision(
                hooksDir, loadResourceEntries("manifest.txt")));

            assertEquals("custom project policy", Files.readString(retiredFile));
        }

        @Test
        void removesUnchangedProvisionedFile(@TempDir Path hooksDir) throws Exception {
            String entry = "scripts/retired.js";
            Path file = hooksDir.resolve(entry);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "official");
            String officialHash = HookHashRegistry.computeStringHash("official");
            Map<String, String> storedHashes = Map.of(entry, officialHash);
            Map<String, String> updatedHashes = new HashMap<>(storedHashes);

            DefaultHookProvisioner.removeRetiredEntries(
                hooksDir, List.of(entry), storedHashes, updatedHashes);

            assertFalse(Files.exists(file));
            assertFalse(updatedHashes.containsKey(entry));
        }

        @Test
        void preservesCustomizedFileAndReleasesProvisionerOwnership(@TempDir Path hooksDir) throws Exception {
            String entry = "scripts/retired.js";
            Path file = hooksDir.resolve(entry);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "customized");
            Map<String, String> storedHashes = Map.of(
                entry, HookHashRegistry.computeStringHash("official"));
            Map<String, String> updatedHashes = new HashMap<>(storedHashes);

            DefaultHookProvisioner.removeRetiredEntries(
                hooksDir, List.of(entry), storedHashes, updatedHashes);

            assertTrue(Files.exists(file));
            assertEquals("customized", Files.readString(file));
            assertFalse(updatedHashes.containsKey(entry));
        }
    }
}

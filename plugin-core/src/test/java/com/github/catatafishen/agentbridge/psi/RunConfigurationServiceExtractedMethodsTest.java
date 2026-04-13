package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for extracted pure-logic static helpers in {@link RunConfigurationService}.
 * These methods are package-private and tested directly (no reflection needed).
 */
class RunConfigurationServiceExtractedMethodsTest {

    // ── sanitizeConfigFileName ───────────────────────────────

    @Nested
    class SanitizeConfigFileName {

        @Test
        void simpleNameUnchanged() {
            assertEquals("MyApp.xml", RunConfigurationService.sanitizeConfigFileName("MyApp"));
        }

        @Test
        void spacesReplacedWithUnderscore() {
            assertEquals("My_App_Config.xml",
                RunConfigurationService.sanitizeConfigFileName("My App Config"));
        }

        @Test
        void specialCharsReplaced() {
            assertEquals("run__debug_.xml",
                RunConfigurationService.sanitizeConfigFileName("run (debug)"));
        }

        @Test
        void dotsAndHyphensPreserved() {
            assertEquals("my-app.v2.xml",
                RunConfigurationService.sanitizeConfigFileName("my-app.v2"));
        }

        @Test
        void emptyNameProducesJustExtension() {
            assertEquals(".xml", RunConfigurationService.sanitizeConfigFileName(""));
        }

        @Test
        void unicodeCharsReplaced() {
            assertEquals("______.xml",
                RunConfigurationService.sanitizeConfigFileName("日本語テスト"));
        }

        @Test
        void underscoresPreserved() {
            assertEquals("my_config_name.xml",
                RunConfigurationService.sanitizeConfigFileName("my_config_name"));
        }
    }

    // ── mergeEnvVars ─────────────────────────────────────────

    @Nested
    class MergeEnvVars {

        @Test
        void addsNewVariable() {
            Map<String, String> envs = new HashMap<>();
            List<String> changes = new ArrayList<>();
            JsonObject obj = new JsonObject();
            obj.addProperty("FOO", "bar");

            RunConfigurationService.mergeEnvVars(envs, obj, changes);

            assertEquals("bar", envs.get("FOO"));
            assertEquals(List.of("env FOO"), changes);
        }

        @Test
        void overwritesExistingVariable() {
            Map<String, String> envs = new HashMap<>(Map.of("FOO", "old"));
            List<String> changes = new ArrayList<>();
            JsonObject obj = new JsonObject();
            obj.addProperty("FOO", "new");

            RunConfigurationService.mergeEnvVars(envs, obj, changes);

            assertEquals("new", envs.get("FOO"));
            assertEquals(List.of("env FOO"), changes);
        }

        @Test
        void removesVariableOnNull() {
            Map<String, String> envs = new HashMap<>(Map.of("FOO", "bar"));
            List<String> changes = new ArrayList<>();
            JsonObject obj = new JsonObject();
            obj.add("FOO", JsonNull.INSTANCE);

            RunConfigurationService.mergeEnvVars(envs, obj, changes);

            assertFalse(envs.containsKey("FOO"));
            assertEquals(List.of("removed env FOO"), changes);
        }

        @Test
        void mixedAddAndRemove() {
            Map<String, String> envs = new HashMap<>(Map.of("REMOVE_ME", "val", "KEEP", "val"));
            List<String> changes = new ArrayList<>();
            JsonObject obj = new JsonObject();
            obj.add("REMOVE_ME", JsonNull.INSTANCE);
            obj.addProperty("NEW_VAR", "hello");

            RunConfigurationService.mergeEnvVars(envs, obj, changes);

            assertFalse(envs.containsKey("REMOVE_ME"));
            assertEquals("hello", envs.get("NEW_VAR"));
            assertEquals("val", envs.get("KEEP"));
            assertEquals(2, changes.size());
            assertTrue(changes.contains("removed env REMOVE_ME"));
            assertTrue(changes.contains("env NEW_VAR"));
        }

        @Test
        void emptyObjectProducesNoChanges() {
            Map<String, String> envs = new HashMap<>(Map.of("FOO", "bar"));
            List<String> changes = new ArrayList<>();

            RunConfigurationService.mergeEnvVars(envs, new JsonObject(), changes);

            assertEquals("bar", envs.get("FOO"));
            assertTrue(changes.isEmpty());
        }

        @Test
        void removeNonExistentKeyIsNoOp() {
            Map<String, String> envs = new HashMap<>();
            List<String> changes = new ArrayList<>();
            JsonObject obj = new JsonObject();
            obj.add("MISSING", JsonNull.INSTANCE);

            RunConfigurationService.mergeEnvVars(envs, obj, changes);

            assertTrue(envs.isEmpty());
            assertEquals(List.of("removed env MISSING"), changes);
        }
    }

    // ── formatRunCompletionMessage ───────────────────────────

    @Nested
    class FormatRunCompletionMessage {

        @Test
        void exitCodeZeroShowsPassed() {
            String msg = RunConfigurationService.formatRunCompletionMessage("MyApp", 0);
            assertTrue(msg.contains("PASSED"));
            assertTrue(msg.contains("MyApp"));
            assertFalse(msg.contains("FAILED"));
        }

        @Test
        void nonZeroExitCodeShowsFailed() {
            String msg = RunConfigurationService.formatRunCompletionMessage("MyApp", 1);
            assertTrue(msg.contains("FAILED"));
            assertTrue(msg.contains("exit code 1"));
            assertFalse(msg.contains("PASSED"));
        }

        @Test
        void includesReadRunOutputHint() {
            String msg = RunConfigurationService.formatRunCompletionMessage("MyApp", 0);
            assertTrue(msg.contains("read_run_output"));
            assertTrue(msg.contains("tab_name='MyApp'"));
        }

        @Test
        void negativeExitCode() {
            String msg = RunConfigurationService.formatRunCompletionMessage("Test", -1);
            assertTrue(msg.contains("FAILED"));
            assertTrue(msg.contains("exit code -1"));
        }
    }

    // ── formatRunTimeoutMessage ──────────────────────────────

    @Nested
    class FormatRunTimeoutMessage {

        @Test
        void includesNameAndTimeout() {
            String msg = RunConfigurationService.formatRunTimeoutMessage("MyApp", 30);
            assertTrue(msg.contains("MyApp"));
            assertTrue(msg.contains("30s"));
            assertTrue(msg.contains("did not complete"));
        }

        @Test
        void includesReadRunOutputHint() {
            String msg = RunConfigurationService.formatRunTimeoutMessage("Build", 60);
            assertTrue(msg.contains("read_run_output"));
            assertTrue(msg.contains("tab_name='Build'"));
        }
    }
}

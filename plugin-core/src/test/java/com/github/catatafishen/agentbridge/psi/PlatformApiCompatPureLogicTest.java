package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.catatafishen.agentbridge.psi.PlatformApiCompat.SourceRootKind;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for pure-logic methods in {@link PlatformApiCompat} that do NOT require
 * the IntelliJ platform.  Language-detection tests live in the sibling
 * {@link PlatformApiCompatLanguageDetectionTest}.
 */
class PlatformApiCompatPureLogicTest {

    // ── classifySourceRootType ────────────────────────────────

    @Nested
    class ClassifySourceRootType {

        @Test
        void sourcesReturnsSource() {
            assertEquals(SourceRootKind.SOURCE,
                PlatformApiCompat.classifySourceRootType("sources"));
        }

        @Test
        void testSourcesReturnsTestSource() {
            assertEquals(SourceRootKind.TEST_SOURCE,
                PlatformApiCompat.classifySourceRootType("test_sources"));
        }

        @Test
        void resourcesReturnsResource() {
            assertEquals(SourceRootKind.RESOURCE,
                PlatformApiCompat.classifySourceRootType("resources"));
        }

        @Test
        void testResourcesReturnsTestResource() {
            assertEquals(SourceRootKind.TEST_RESOURCE,
                PlatformApiCompat.classifySourceRootType("test_resources"));
        }

        @Test
        void generatedSourcesReturnsGeneratedSource() {
            assertEquals(SourceRootKind.GENERATED_SOURCE,
                PlatformApiCompat.classifySourceRootType("generated_sources"));
        }

        @Test
        void unknownTypeDefaultsToSource() {
            assertEquals(SourceRootKind.SOURCE,
                PlatformApiCompat.classifySourceRootType("something_else"));
        }

        @Test
        void testPrefixWithoutResourcesIsTestSource() {
            assertEquals(SourceRootKind.TEST_SOURCE,
                PlatformApiCompat.classifySourceRootType("test_whatever"));
        }

        @ParameterizedTest
        @CsvSource({
            "sources,          SOURCE",
            "test_sources,     TEST_SOURCE",
            "resources,        RESOURCE",
            "test_resources,   TEST_RESOURCE",
            "generated_sources,GENERATED_SOURCE",
        })
        void parametricClassification(String type, String expectedKind) {
            assertEquals(SourceRootKind.valueOf(expectedKind),
                PlatformApiCompat.classifySourceRootType(type));
        }

        @Test
        void resourcesSubstringMatchTriggers() {
            // "my_resources_custom" contains "resources" → RESOURCE (not test)
            assertEquals(SourceRootKind.RESOURCE,
                PlatformApiCompat.classifySourceRootType("my_resources_custom"));
        }

        @Test
        void testResourcesSubstringMatchTriggers() {
            // "test_extra_resources" starts with "test_" AND contains "resources" → TEST_RESOURCE
            assertEquals(SourceRootKind.TEST_RESOURCE,
                PlatformApiCompat.classifySourceRootType("test_extra_resources"));
        }

        @Test
        void resourcesCheckTakesPriorityOverTestPrefix() {
            // Edge case: "test_resources" → TEST_RESOURCE, not TEST_SOURCE
            // The resources check fires first because it checks contains("resources")
            assertEquals(SourceRootKind.TEST_RESOURCE,
                PlatformApiCompat.classifySourceRootType("test_resources"));
        }

        @Test
        void generatedSourcesMustBeExactMatch() {
            // "generated_sources_v2" does NOT match "generated_sources".equals() → defaults to SOURCE
            assertEquals(SourceRootKind.SOURCE,
                PlatformApiCompat.classifySourceRootType("generated_sources_v2"));
        }

        @Test
        void emptyStringDefaultsToSource() {
            assertEquals(SourceRootKind.SOURCE,
                PlatformApiCompat.classifySourceRootType(""));
        }
    }

    // ── SourceRootKind enum ───────────────────────────────────

    @Nested
    class SourceRootKindEnum {

        @Test
        void hasFiveValues() {
            assertEquals(5, SourceRootKind.values().length);
        }

        @ParameterizedTest
        @ValueSource(strings = {"SOURCE", "TEST_SOURCE", "RESOURCE", "TEST_RESOURCE", "GENERATED_SOURCE"})
        void allExpectedValuesExist(String name) {
            // Verifies valueOf doesn't throw
            assertEquals(name, SourceRootKind.valueOf(name).name());
        }
    }

    // ── formatGitCommandError ─────────────────────────────────

    @Nested
    class FormatGitCommandError {

        @Test
        void formatsWithExitCodeAndMessage() {
            assertEquals("Error (exit 128): fatal: not a git repository",
                PlatformApiCompat.formatGitCommandError(128, "fatal: not a git repository"));
        }

        @Test
        void formatsWithZeroExitCode() {
            assertEquals("Error (exit 0): ",
                PlatformApiCompat.formatGitCommandError(0, ""));
        }

        @Test
        void formatsWithNegativeExitCode() {
            assertEquals("Error (exit -1): signal killed",
                PlatformApiCompat.formatGitCommandError(-1, "signal killed"));
        }

        @Test
        void formatsWithExitCode1() {
            assertEquals("Error (exit 1): merge conflict",
                PlatformApiCompat.formatGitCommandError(1, "merge conflict"));
        }

        @Test
        void preservesMultilineError() {
            String multiline = "error: pathspec 'foo' did not match\nhint: check the path";
            assertEquals("Error (exit 1): " + multiline,
                PlatformApiCompat.formatGitCommandError(1, multiline));
        }

        @ParameterizedTest
        @CsvSource({
            "128, 'fatal: bad object',         'Error (exit 128): fatal: bad object'",
            "1,   'error: could not apply',    'Error (exit 1): error: could not apply'",
            "127, 'git: command not found',    'Error (exit 127): git: command not found'",
        })
        void parametricFormats(int exitCode, String errorMsg, String expected) {
            assertEquals(expected, PlatformApiCompat.formatGitCommandError(exitCode, errorMsg));
        }
    }

    // ── formatPluginVersionInfo ───────────────────────────────

    @Nested
    class FormatPluginVersionInfo {

        @Test
        void formatsNameAndVersion() {
            assertEquals("AgentBridge v1.2.3",
                PlatformApiCompat.formatPluginVersionInfo("AgentBridge", "1.2.3"));
        }

        @Test
        void formatsWithPreReleaseVersion() {
            assertEquals("My Plugin v0.1.0-beta.1",
                PlatformApiCompat.formatPluginVersionInfo("My Plugin", "0.1.0-beta.1"));
        }

        @Test
        void formatsWithSnapshotVersion() {
            assertEquals("Dev Plugin v2.0.0-SNAPSHOT",
                PlatformApiCompat.formatPluginVersionInfo("Dev Plugin", "2.0.0-SNAPSHOT"));
        }

        @Test
        void prefixesVersionWithLowercaseV() {
            String result = PlatformApiCompat.formatPluginVersionInfo("Test", "1.0");
            assertEquals("Test v1.0", result);
            // Verify it's lowercase 'v', not 'V'
            assertEquals('v', result.charAt(result.indexOf('v')));
        }

        @Test
        void handlesEmptyName() {
            assertEquals(" v1.0", PlatformApiCompat.formatPluginVersionInfo("", "1.0"));
        }

        @Test
        void handlesEmptyVersion() {
            assertEquals("Plugin v", PlatformApiCompat.formatPluginVersionInfo("Plugin", ""));
        }
    }

    // ── IDE_GIT_COMMAND_MAP ─────────────────────────────────────

    @Nested
    class IdeGitCommandMap {

        @SuppressWarnings("unchecked")
        private static java.util.Map<String, Object> getMap() throws ReflectiveOperationException {
            var field = PlatformApiCompat.class.getDeclaredField("IDE_GIT_COMMAND_MAP");
            field.setAccessible(true);
            return (java.util.Map<String, Object>) field.get(null);
        }

        @Test
        void containsLsFiles() throws ReflectiveOperationException {
            var map = getMap();
            org.junit.jupiter.api.Assertions.assertNotNull(map.get("ls-files"),
                "IDE_GIT_COMMAND_MAP should map 'ls-files'");
        }

        @Test
        void containsRevList() throws ReflectiveOperationException {
            var map = getMap();
            org.junit.jupiter.api.Assertions.assertNotNull(map.get("rev-list"),
                "IDE_GIT_COMMAND_MAP should map 'rev-list'");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "add", "blame", "branch", "checkout", "cherry-pick", "commit",
            "config", "diff", "fetch", "log", "merge", "pull", "push",
            "rebase", "remote", "ls-files", "reset", "restore", "rev-list",
            "rev-parse", "revert", "show", "stash", "status", "tag"
        })
        void containsAllExpectedCommands(String command) throws ReflectiveOperationException {
            var map = getMap();
            org.junit.jupiter.api.Assertions.assertNotNull(map.get(command),
                "IDE_GIT_COMMAND_MAP should map '" + command + "'");
        }
    }
}

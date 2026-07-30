package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VibeClient}.
 *
 * <p>Tests focus on the pure static helpers (prefix stripping, reprimand text) so
 * they can run without the IntelliJ platform or a live ACP process.</p>
 */
class VibeClientTest {

    // ─── Tool prefix helpers ─────────────────────────────────────────────────

    @Nested
    class ToolPrefix {

        @Test
        void hasPrefix_returnsTrueForMcpTool() {
            assertTrue(VibeClient.hasToolPrefix("agentbridge_read_file"));
        }

        @Test
        void hasPrefix_returnsFalseForNativeTool() {
            assertFalse(VibeClient.hasToolPrefix("bash"));
            assertFalse(VibeClient.hasToolPrefix("write"));
            assertFalse(VibeClient.hasToolPrefix("edit"));
        }

        @Test
        void stripPrefix_removesPrefix() {
            String result = VibeClient.stripToolPrefix("agentbridge_write_file");
            org.junit.jupiter.api.Assertions.assertEquals("write_file", result);
        }

        @Test
        void stripPrefix_noop_whenNoPrefixPresent() {
            String result = VibeClient.stripToolPrefix("bash");
            org.junit.jupiter.api.Assertions.assertEquals("bash", result);
        }
    }

    // ─── Reprimand text ──────────────────────────────────────────────────────

    @Nested
    class ReprimandText {

        @ParameterizedTest(name = "write tool ''{0}'' → write_file reprimand")
        @ValueSource(strings = {"write", "edit", "create"})
        void writeTool_mapsToWriteFile(String toolId) {
            String text = VibeClient.buildReprimandText(toolId);
            assertTrue(text.contains("agentbridge_write_file"),
                "Expected agentbridge_write_file in: " + text);
            // Must correct the wrong parameter name that Vibe's model has been observed to use
            assertTrue(text.contains("'path'"),
                "Expected 'path' parameter guidance in: " + text);
            assertTrue(text.contains("file_path"),
                "Expected mention of wrong 'file_path' name in: " + text);
        }

        @ParameterizedTest(name = "read tool ''{0}'' → read_file reprimand")
        @ValueSource(strings = {"read", "view"})
        void readTool_mapsToReadFile(String toolId) {
            String text = VibeClient.buildReprimandText(toolId);
            assertTrue(text.contains("agentbridge_read_file"),
                "Expected agentbridge_read_file in: " + text);
            assertTrue(text.contains("'path'"),
                "Expected 'path' parameter guidance in: " + text);
        }

        @Test
        void bashTool_mapsToRunCommand() {
            String text = VibeClient.buildReprimandText("bash");
            assertTrue(text.contains("agentbridge_run_command"),
                "Expected agentbridge_run_command in: " + text);
            assertTrue(text.contains("'command'"),
                "Expected 'command' parameter guidance in: " + text);
        }

        @Test
        void grepTool_mapsToSearchText() {
            String text = VibeClient.buildReprimandText("grep");
            assertTrue(text.contains("agentbridge_search_text"),
                "Expected agentbridge_search_text in: " + text);
        }

        @Test
        void globTool_mapsToListProjectFiles() {
            String text = VibeClient.buildReprimandText("glob");
            assertTrue(text.contains("agentbridge_list_project_files"),
                "Expected agentbridge_list_project_files in: " + text);
        }

        @Test
        void unknownTool_includesToolIdAndGenericGuidance() {
            String text = VibeClient.buildReprimandText("some_unknown_tool");
            assertTrue(text.contains("some_unknown_tool"),
                "Expected tool ID echoed in: " + text);
            assertTrue(text.contains("agentbridge_*"),
                "Expected agentbridge_* mention in: " + text);
            // Generic fallback also corrects the file_path → path confusion
            assertTrue(text.contains("file_path"),
                "Expected 'file_path' correction hint in: " + text);
        }

        @Test
        void reprimandText_neverEmpty() {
            for (String toolId : new String[]{"write", "edit", "create", "read", "view",
                "bash", "grep", "glob", "unknown"}) {
                String text = VibeClient.buildReprimandText(toolId);
                assertFalse(text == null || text.isBlank(),
                    "Reprimand text must not be blank for toolId: " + toolId);
            }
        }
    }
}

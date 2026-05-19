package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.ContextFileRef;
import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.bridge.FileRef;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDataModelTest {

    // ── toolDisplayInfo(name) ───────────────────────────────────────────

    @Test
    void toolDisplayInfo_knownTool_returnsNonNull() {
        ToolInfo info = ChatDataModelKt.toolDisplayInfo("view");
        assertNotNull(info);
    }

    @Test
    void toolDisplayInfo_unknownTool_returnsNull() {
        assertNull(ChatDataModelKt.toolDisplayInfo("totally_nonexistent_tool"));
    }

    @Test
    void toolDisplayInfo_caseInsensitive() {
        ToolInfo lower = ChatDataModelKt.toolDisplayInfo("bash");
        ToolInfo mixed = ChatDataModelKt.toolDisplayInfo("Bash");
        ToolInfo upper = ChatDataModelKt.toolDisplayInfo("BASH");
        assertNotNull(lower);
        assertEquals(lower, mixed);
        assertEquals(lower, upper);
    }

    @Test
    void toolDisplayInfo_specificDisplayNames() {
        assertEquals("Bash", ChatDataModelKt.toolDisplayInfo("bash").getDisplayName());
        assertEquals("View", ChatDataModelKt.toolDisplayInfo("view").getDisplayName());
        assertEquals("Grep", ChatDataModelKt.toolDisplayInfo("grep").getDisplayName());
        assertEquals("Web Search", ChatDataModelKt.toolDisplayInfo("web_search").getDisplayName());
    }

    // ── toolSubtitleKey(name) ───────────────────────────────────────────

    @Test
    void toolSubtitleKey_knownTool_returnsExpectedKey() {
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("read_file"));
        assertEquals("query", ChatDataModelKt.toolSubtitleKey("search_text"));
        assertEquals("target", ChatDataModelKt.toolSubtitleKey("run_tests"));
    }

    @Test
    void toolSubtitleKey_unknownTool_returnsNull() {
        assertNull(ChatDataModelKt.toolSubtitleKey("no_such_tool"));
    }

    // ── QUICK_REPLY_TAG_REGEX ───────────────────────────────────────────

    @Test
    void quickReplyRegex_matchesSimpleTag() {
        Regex regex = ChatDataModelKt.getQUICK_REPLY_TAG_REGEX();
        assertTrue(regex.containsMatchIn("[quick-reply: A | B]"));
    }

    @Test
    void quickReplyRegex_matchesTagWithDismiss() {
        Regex regex = ChatDataModelKt.getQUICK_REPLY_TAG_REGEX();
        assertTrue(regex.containsMatchIn("[quick-reply: Yes | No:dismiss]"));
    }

    @Test
    void quickReplyRegex_doesNotMatchPlainText() {
        Regex regex = ChatDataModelKt.getQUICK_REPLY_TAG_REGEX();
        assertFalse(regex.containsMatchIn("This is just regular text without any tag"));
    }

    @Test
    void quickReplyRegex_capturesContentAfterColon() {
        Regex regex = ChatDataModelKt.getQUICK_REPLY_TAG_REGEX();
        MatchResult match = regex.find("[quick-reply: Option A | Option B]", 0);
        assertNotNull(match);
        // Group 1 captures everything after "quick-reply:" inside the brackets
        String captured = match.getGroupValues().get(1);
        assertTrue(captured.contains("Option A"));
        assertTrue(captured.contains("Option B"));
    }

    // ── EntryData sealed hierarchy ──────────────────────────────────────

    @Test
    void prompt_constructionAndGetters() {
        EntryData.Prompt prompt = new EntryData.Prompt("Hello world", "2025-01-01T00:00:00Z", null, "");
        assertEquals("Hello world", prompt.getText());
        assertEquals("2025-01-01T00:00:00Z", prompt.getTimestamp());
    }

    @Test
    void text_constructionAndGetters() {
        EntryData.Text text = new EntryData.Text("raw content", "2025-01-01T00:00:00Z", "", "gpt-4");
        assertEquals("raw content", text.getRaw());
        assertEquals("gpt-4", text.getModel());
    }

    @Test
    void toolCall_constructionAndSetResult() {
        EntryData.ToolCall toolCall = new EntryData.ToolCall("read_file", "{\"path\": \"foo.txt\"}");
        assertEquals("read_file", toolCall.getTitle());
        assertEquals("{\"path\": \"foo.txt\"}", toolCall.getArguments());
        assertNull(toolCall.getResult());

        toolCall.setResult("file contents here");
        assertEquals("file contents here", toolCall.getResult());
    }

    @Test
    void thinking_construction() {
        EntryData.Thinking thinking = new EntryData.Thinking("thinking about it...", "2025-01-01T00:00:00Z");
        assertEquals("thinking about it...", thinking.getRaw());
        assertEquals("2025-01-01T00:00:00Z", thinking.getTimestamp());
    }

    @Test
    void entryId_autoGenerated_notEmpty() {
        EntryData.Prompt prompt = new EntryData.Prompt("test");
        EntryData.Text text = new EntryData.Text();
        EntryData.ToolCall toolCall = new EntryData.ToolCall("title");
        EntryData.Thinking thinking = new EntryData.Thinking();

        assertNotNull(prompt.getEntryId());
        assertFalse(prompt.getEntryId().isEmpty());
        assertNotNull(text.getEntryId());
        assertFalse(text.getEntryId().isEmpty());
        assertNotNull(toolCall.getEntryId());
        assertFalse(toolCall.getEntryId().isEmpty());
        assertNotNull(thinking.getEntryId());
        assertFalse(thinking.getEntryId().isEmpty());
    }

    @Test
    void entryId_differentEntriesHaveDifferentIds() {
        EntryData.Prompt a = new EntryData.Prompt("one");
        EntryData.Prompt b = new EntryData.Prompt("two");
        EntryData.Text c = new EntryData.Text("three");
        assertNotEquals(a.getEntryId(), b.getEntryId());
        assertNotEquals(a.getEntryId(), c.getEntryId());
    }

    // ── TOOL_DISPLAY_INFO map completeness ──────────────────────────────

    @Test
    void toolDisplayInfo_allEntriesHaveNonNullNameAndDescription() {
        Map<String, ToolInfo> map = ChatDataModelKt.getTOOL_DISPLAY_INFO();
        assertFalse(map.isEmpty());
        for (Map.Entry<String, ToolInfo> entry : map.entrySet()) {
            assertNotNull(entry.getValue().getDisplayName(),
                "displayName is null for key: " + entry.getKey());
            assertFalse(entry.getValue().getDisplayName().isEmpty(),
                "displayName is empty for key: " + entry.getKey());
            assertNotNull(entry.getValue().getDescription(),
                "description is null for key: " + entry.getKey());
            assertFalse(entry.getValue().getDescription().isEmpty(),
                "description is empty for key: " + entry.getKey());
        }
    }

    @Test
    void toolDisplayInfo_specificToolsArePresent() {
        Map<String, ToolInfo> map = ChatDataModelKt.getTOOL_DISPLAY_INFO();
        assertTrue(map.containsKey("view"), "Expected 'view' in TOOL_DISPLAY_INFO");
        assertTrue(map.containsKey("bash"), "Expected 'bash' in TOOL_DISPLAY_INFO");
        assertTrue(map.containsKey("web_search"), "Expected 'web_search' in TOOL_DISPLAY_INFO");
        assertTrue(map.containsKey("grep"), "Expected 'grep' in TOOL_DISPLAY_INFO");
    }

    // ── toolSubtitleKey case insensitivity ───────────────────────────────

    @Test
    void toolSubtitleKey_caseInsensitive_lowercase() {
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("read_file"));
    }

    @Test
    void toolSubtitleKey_caseInsensitive_uppercase() {
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("READ_FILE"));
    }

    @Test
    void toolSubtitleKey_caseInsensitive_mixedCase() {
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("Read_File"));
    }

    // ── TOOL_SUBTITLE_KEY map completeness ──────────────────────────────

    @Test
    void toolSubtitleKey_allEntriesHaveNonEmptyValues() {
        Map<String, String> map = ChatDataModelKt.getTOOL_SUBTITLE_KEY();
        assertFalse(map.isEmpty(), "TOOL_SUBTITLE_KEY should not be empty");
        for (Map.Entry<String, String> entry : map.entrySet()) {
            assertNotNull(entry.getValue(),
                "value is null for key: " + entry.getKey());
            assertFalse(entry.getValue().isEmpty(),
                "value is empty for key: " + entry.getKey());
        }
    }

    @Test
    void toolSubtitleKey_specificToolsPresent() {
        Map<String, String> map = ChatDataModelKt.getTOOL_SUBTITLE_KEY();
        // File operations
        assertTrue(map.containsKey("read_file"));
        assertTrue(map.containsKey("write_file"));
        assertTrue(map.containsKey("edit_text"));
        // Code navigation
        assertTrue(map.containsKey("search_symbols"));
        assertTrue(map.containsKey("find_references"));
        assertTrue(map.containsKey("go_to_declaration"));
        // Git
        assertTrue(map.containsKey("git_commit"));
        assertTrue(map.containsKey("git_branch"));
        // Tests
        assertTrue(map.containsKey("run_tests"));
        // Shell
        assertTrue(map.containsKey("run_command"));
        // Built-in CLI tools
        assertTrue(map.containsKey("bash"));
        assertTrue(map.containsKey("grep"));
        assertTrue(map.containsKey("glob"));
    }

    @Test
    void toolSubtitleKey_fileOperationsUsePathKey() {
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("read_file"));
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("write_file"));
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("edit_text"));
        assertEquals("path", ChatDataModelKt.toolSubtitleKey("create_file"));
    }

    @Test
    void toolSubtitleKey_searchToolsUseQueryKey() {
        assertEquals("query", ChatDataModelKt.toolSubtitleKey("search_symbols"));
        assertEquals("query", ChatDataModelKt.toolSubtitleKey("search_text"));
    }

    // ── SUB_AGENT_INFO map ──────────────────────────────────────────────

    @Test
    void subAgentInfo_allEntriesHaveNonEmptyDisplayNames() {
        Map<String, SubAgentInfo> map = ChatDataModelKt.getSUB_AGENT_INFO();
        assertFalse(map.isEmpty(), "SUB_AGENT_INFO should not be empty");
        for (Map.Entry<String, SubAgentInfo> entry : map.entrySet()) {
            assertNotNull(entry.getValue().getDisplayName(),
                "displayName is null for key: " + entry.getKey());
            assertFalse(entry.getValue().getDisplayName().isEmpty(),
                "displayName is empty for key: " + entry.getKey());
        }
    }

    @Test
    void subAgentInfo_containsExpectedAgentTypes() {
        Map<String, SubAgentInfo> map = ChatDataModelKt.getSUB_AGENT_INFO();
        // Built-in Claude Code agents
        assertTrue(map.containsKey("Explore"), "Expected 'Explore' in SUB_AGENT_INFO");
        assertTrue(map.containsKey("Plan"), "Expected 'Plan' in SUB_AGENT_INFO");
        // Legacy / lowercase aliases
        assertTrue(map.containsKey("explore"), "Expected 'explore' in SUB_AGENT_INFO");
        assertTrue(map.containsKey("task"), "Expected 'task' in SUB_AGENT_INFO");
        // General-purpose
        assertTrue(map.containsKey("general-purpose"), "Expected AGENT_TYPE_GENERAL in SUB_AGENT_INFO");
    }

    @Test
    void subAgentInfo_specificDisplayNames() {
        Map<String, SubAgentInfo> map = ChatDataModelKt.getSUB_AGENT_INFO();
        assertEquals("Explore", map.get("Explore").getDisplayName());
        assertEquals("Plan", map.get("Plan").getDisplayName());
        assertEquals("General", map.get("general-purpose").getDisplayName());
        assertEquals("Task Agent", map.get("task").getDisplayName());
    }

    @Test
    void subAgentInfo_intellectAgents() {
        Map<String, SubAgentInfo> map = ChatDataModelKt.getSUB_AGENT_INFO();
        assertTrue(map.containsKey("intellij-explore"));
        assertTrue(map.containsKey("intellij-edit"));
        assertTrue(map.containsKey("intellij-default"));
        assertEquals("Explore", map.get("intellij-explore").getDisplayName());
        assertEquals("Edit Agent", map.get("intellij-edit").getDisplayName());
        assertEquals("Agent", map.get("intellij-default").getDisplayName());
    }

    // ── Additional data class construction ──────────────────────────────

    @Test
    void nudge_constructionAndDefaults() {
        EntryData.Nudge nudge = new EntryData.Nudge("bump", "n1");
        assertEquals("bump", nudge.getText());
        assertEquals("n1", nudge.getId());
        assertFalse(nudge.getSent(), "default sent should be false");
        assertEquals("", nudge.getTimestamp());
        assertNotNull(nudge.getEntryId());
        assertFalse(nudge.getEntryId().isEmpty());
    }

    @Test
    void nudge_sentFlagTrue() {
        EntryData.Nudge nudge = new EntryData.Nudge("bump", "n2", true, "2025-01-01T00:00:00Z");
        assertTrue(nudge.getSent());
        assertEquals("2025-01-01T00:00:00Z", nudge.getTimestamp());
    }

    @Test
    void subAgent_constructionAndDefaults() {
        EntryData.SubAgent sa = new EntryData.SubAgent("explore", "Exploring codebase");
        assertEquals("explore", sa.getAgentType());
        assertEquals("Exploring codebase", sa.getDescription());
        assertNull(sa.getPrompt());
        assertNull(sa.getResult());
        assertNull(sa.getStatus());
        assertEquals(0, sa.getColorIndex());
        assertFalse(sa.getAutoDenied());
        assertNull(sa.getDenialReason());
        assertNotNull(sa.getEntryId());
    }

    @Test
    void subAgent_fullConstruction() {
        EntryData.SubAgent sa = new EntryData.SubAgent(
            "task", "Running task", "Find files", "Found 5 files",
            "completed", 2, "call-1", false, null,
            "2025-01-01T00:00:00Z", "copilot", "gpt-4");
        assertEquals("task", sa.getAgentType());
        assertEquals("Running task", sa.getDescription());
        assertEquals("Find files", sa.getPrompt());
        assertEquals("Found 5 files", sa.getResult());
        assertEquals("completed", sa.getStatus());
        assertEquals(2, sa.getColorIndex());
        assertEquals("call-1", sa.getCallId());
        assertEquals("copilot", sa.getAgent());
        assertEquals("gpt-4", sa.getModel());
    }

    @Test
    void status_construction() {
        EntryData.Status status = new EntryData.Status("✅", "Build successful");
        assertEquals("✅", status.getIcon());
        assertEquals("Build successful", status.getMessage());
        assertNotNull(status.getEntryId());
    }

    @Test
    void sessionSeparator_construction() {
        EntryData.SessionSeparator sep = new EntryData.SessionSeparator("2025-06-15T10:00:00Z", "copilot");
        assertEquals("2025-06-15T10:00:00Z", sep.getTimestamp());
        assertEquals("copilot", sep.getAgent());
        assertNotNull(sep.getEntryId());
    }

    @Test
    void contextFiles_construction() {
        List<FileRef> files = List.of(
            new FileRef("Foo.java", "/src/Foo.java"),
            new FileRef("Bar.kt", "/src/Bar.kt")
        );
        EntryData.ContextFiles ctx = new EntryData.ContextFiles(files);
        assertEquals(2, ctx.getFiles().size());
        assertEquals("Foo.java", ctx.getFiles().get(0).getName());
        assertEquals("/src/Bar.kt", ctx.getFiles().get(1).getPath());
        assertNotNull(ctx.getEntryId());
    }

    @Test
    void turnStats_constructionAndDefaults() {
        EntryData.TurnStats ts = new EntryData.TurnStats("t1");
        assertEquals("t1", ts.getTurnId());
        assertEquals(0, ts.getDurationMs());
        assertEquals(0, ts.getInputTokens());
        assertEquals(0, ts.getOutputTokens());
        assertEquals(0.0, ts.getCostUsd(), 0.001);
        assertEquals(0, ts.getToolCallCount());
        assertEquals(0, ts.getLinesAdded());
        assertEquals(0, ts.getLinesRemoved());
        assertEquals("", ts.getModel());
        assertEquals("", ts.getMultiplier());
    }

    @Test
    void contextFileRef_construction() {
        ContextFileRef ref = new ContextFileRef("Main.java", "/src/Main.java", 42);
        assertEquals("Main.java", ref.getName());
        assertEquals("/src/Main.java", ref.getPath());
        assertEquals(42, ref.getLine());
    }

    @Test
    void contextFileRef_defaultLine() {
        ContextFileRef ref = new ContextFileRef("Main.java", "/src/Main.java");
        assertEquals(0, ref.getLine());
    }

    @Test
    void fileRef_construction() {
        FileRef ref = new FileRef("build.gradle", "/build.gradle");
        assertEquals("build.gradle", ref.getName());
        assertEquals("/build.gradle", ref.getPath());
    }

    @Test
    void fileRef_equality() {
        FileRef a = new FileRef("a.txt", "/a.txt");
        FileRef b = new FileRef("a.txt", "/a.txt");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void fileRef_inequality() {
        FileRef a = new FileRef("a.txt", "/a.txt");
        FileRef b = new FileRef("b.txt", "/b.txt");
        assertNotEquals(a, b);
    }
}

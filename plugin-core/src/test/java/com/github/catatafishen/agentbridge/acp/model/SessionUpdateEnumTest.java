package com.github.catatafishen.agentbridge.acp.model;

import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.AgentMessageChunk;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.AgentThoughtChunk;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.BannerLevel;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.ClearOn;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.ToolCall;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.ToolCallStatus;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.ToolKind;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate.UserMessageChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for inner enums and record helper methods of {@link SessionUpdate}.
 */
class SessionUpdateEnumTest {

    // ── ToolKind ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolKind")
    class ToolKindTests {

        @Nested
        @DisplayName("fromString")
        class FromString {

            @Test
            @DisplayName("null → OTHER")
            void nullReturnsOther() {
                assertEquals(ToolKind.OTHER, ToolKind.fromString(null));
            }

            @Test
            @DisplayName("matches by value (lowercase)")
            void matchesByValue() {
                assertEquals(ToolKind.READ, ToolKind.fromString("read"));
                assertEquals(ToolKind.EDIT, ToolKind.fromString("edit"));
                assertEquals(ToolKind.DELETE, ToolKind.fromString("delete"));
                assertEquals(ToolKind.MOVE, ToolKind.fromString("move"));
                assertEquals(ToolKind.SEARCH, ToolKind.fromString("search"));
                assertEquals(ToolKind.EXECUTE, ToolKind.fromString("execute"));
                assertEquals(ToolKind.THINK, ToolKind.fromString("think"));
                assertEquals(ToolKind.FETCH, ToolKind.fromString("fetch"));
                assertEquals(ToolKind.SWITCH_MODE, ToolKind.fromString("switch_mode"));
                assertEquals(ToolKind.OTHER, ToolKind.fromString("other"));
            }

            @Test
            @DisplayName("case-insensitive matching")
            void caseInsensitive() {
                assertEquals(ToolKind.READ, ToolKind.fromString("READ"));
                assertEquals(ToolKind.EDIT, ToolKind.fromString("Edit"));
                assertEquals(ToolKind.SEARCH, ToolKind.fromString("SEARCH"));
                assertEquals(ToolKind.EXECUTE, ToolKind.fromString("Execute"));
            }

            @Test
            @DisplayName("matches by enum name")
            void matchesByEnumName() {
                assertEquals(ToolKind.SWITCH_MODE, ToolKind.fromString("SWITCH_MODE"));
                assertEquals(ToolKind.SWITCH_MODE, ToolKind.fromString("switch_mode"));
            }

            @Test
            @DisplayName("unknown string → OTHER")
            void unknownReturnsOther() {
                assertEquals(ToolKind.OTHER, ToolKind.fromString("unknown"));
                assertEquals(ToolKind.OTHER, ToolKind.fromString("foobar"));
                assertEquals(ToolKind.OTHER, ToolKind.fromString(""));
            }
        }

        @Nested
        @DisplayName("fromCategory")
        class FromCategory {

            @Test
            @DisplayName("null → OTHER")
            void nullReturnsOther() {
                assertEquals(ToolKind.OTHER, ToolKind.fromCategory(null));
            }

            @Test
            @DisplayName("SEARCH → SEARCH")
            void searchCategory() {
                assertEquals(ToolKind.SEARCH, ToolKind.fromCategory("SEARCH"));
            }

            @Test
            @DisplayName("FILE, EDITOR, REFACTOR → EDIT")
            void editCategories() {
                assertEquals(ToolKind.EDIT, ToolKind.fromCategory("FILE"));
                assertEquals(ToolKind.EDIT, ToolKind.fromCategory("EDITOR"));
                assertEquals(ToolKind.EDIT, ToolKind.fromCategory("REFACTOR"));
            }

            @Test
            @DisplayName("BUILD, RUN, TERMINAL, SHELL, GIT → EXECUTE")
            void executeCategories() {
                assertEquals(ToolKind.EXECUTE, ToolKind.fromCategory("BUILD"));
                assertEquals(ToolKind.EXECUTE, ToolKind.fromCategory("RUN"));
                assertEquals(ToolKind.EXECUTE, ToolKind.fromCategory("TERMINAL"));
                assertEquals(ToolKind.EXECUTE, ToolKind.fromCategory("SHELL"));
                assertEquals(ToolKind.EXECUTE, ToolKind.fromCategory("GIT"));
            }

            @Test
            @DisplayName("CODE_QUALITY, TESTING, IDE, PROJECT, INFRASTRUCTURE → READ")
            void readCategories() {
                assertEquals(ToolKind.READ, ToolKind.fromCategory("CODE_QUALITY"));
                assertEquals(ToolKind.READ, ToolKind.fromCategory("TESTING"));
                assertEquals(ToolKind.READ, ToolKind.fromCategory("IDE"));
                assertEquals(ToolKind.READ, ToolKind.fromCategory("PROJECT"));
                assertEquals(ToolKind.READ, ToolKind.fromCategory("INFRASTRUCTURE"));
            }

            @Test
            @DisplayName("unknown category → OTHER")
            void unknownReturnsOther() {
                assertEquals(ToolKind.OTHER, ToolKind.fromCategory("UNKNOWN"));
                assertEquals(ToolKind.OTHER, ToolKind.fromCategory(""));
                assertEquals(ToolKind.OTHER, ToolKind.fromCategory(42));
            }
        }

        @Test
        @DisplayName("value() returns serialized string")
        void valueReturnsSerializedString() {
            assertEquals("read", ToolKind.READ.value());
            assertEquals("edit", ToolKind.EDIT.value());
            assertEquals("delete", ToolKind.DELETE.value());
            assertEquals("move", ToolKind.MOVE.value());
            assertEquals("search", ToolKind.SEARCH.value());
            assertEquals("execute", ToolKind.EXECUTE.value());
            assertEquals("think", ToolKind.THINK.value());
            assertEquals("fetch", ToolKind.FETCH.value());
            assertEquals("switch_mode", ToolKind.SWITCH_MODE.value());
            assertEquals("other", ToolKind.OTHER.value());
        }
    }

    // ── ToolCallStatus ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolCallStatus")
    class ToolCallStatusTests {

        @Nested
        @DisplayName("fromString")
        class FromString {

            @Test
            @DisplayName("null → FAILED")
            void nullReturnsFailed() {
                assertEquals(ToolCallStatus.FAILED, ToolCallStatus.fromString(null));
            }

            @Test
            @DisplayName("matches by value")
            void matchesByValue() {
                assertEquals(ToolCallStatus.COMPLETED, ToolCallStatus.fromString("completed"));
                assertEquals(ToolCallStatus.FAILED, ToolCallStatus.fromString("failed"));
                // PENDING and IN_PROGRESS share value "in_progress"; PENDING is declared first so it wins
                assertEquals(ToolCallStatus.PENDING, ToolCallStatus.fromString("in_progress"));
            }

            @Test
            @DisplayName("aliases: success/succeeded → COMPLETED")
            void completedAliases() {
                assertEquals(ToolCallStatus.COMPLETED, ToolCallStatus.fromString("success"));
                assertEquals(ToolCallStatus.COMPLETED, ToolCallStatus.fromString("succeeded"));
            }

            @Test
            @DisplayName("aliases: in-progress/running → IN_PROGRESS")
            void inProgressAliases() {
                assertEquals(ToolCallStatus.IN_PROGRESS, ToolCallStatus.fromString("in-progress"));
                assertEquals(ToolCallStatus.IN_PROGRESS, ToolCallStatus.fromString("running"));
            }

            @Test
            @DisplayName("matches by enum name")
            void matchesByEnumName() {
                assertEquals(ToolCallStatus.COMPLETED, ToolCallStatus.fromString("COMPLETED"));
                assertEquals(ToolCallStatus.PENDING, ToolCallStatus.fromString("PENDING"));
                // "IN_PROGRESS" matches PENDING's value ("in_progress") first
                assertEquals(ToolCallStatus.PENDING, ToolCallStatus.fromString("IN_PROGRESS"));
                assertEquals(ToolCallStatus.FAILED, ToolCallStatus.fromString("FAILED"));
            }

            @Test
            @DisplayName("unknown → FAILED")
            void unknownReturnsFailed() {
                assertEquals(ToolCallStatus.FAILED, ToolCallStatus.fromString("unknown"));
                assertEquals(ToolCallStatus.FAILED, ToolCallStatus.fromString(""));
                assertEquals(ToolCallStatus.FAILED, ToolCallStatus.fromString("aborted"));
            }
        }

        @Test
        @DisplayName("value() returns serialized string")
        void valueReturnsSerializedString() {
            assertEquals("completed", ToolCallStatus.COMPLETED.value());
            assertEquals("failed", ToolCallStatus.FAILED.value());
            assertEquals("in_progress", ToolCallStatus.PENDING.value());
            assertEquals("in_progress", ToolCallStatus.IN_PROGRESS.value());
        }
    }

    // ── BannerLevel ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BannerLevel")
    class BannerLevelTests {

        @Nested
        @DisplayName("fromString")
        class FromString {

            @Test
            @DisplayName("null → WARNING")
            void nullReturnsWarning() {
                assertEquals(BannerLevel.WARNING, BannerLevel.fromString(null));
            }

            @Test
            @DisplayName("matches by value")
            void matchesByValue() {
                assertEquals(BannerLevel.WARNING, BannerLevel.fromString("warning"));
                assertEquals(BannerLevel.ERROR, BannerLevel.fromString("error"));
            }

            @Test
            @DisplayName("case-insensitive matching")
            void caseInsensitive() {
                assertEquals(BannerLevel.WARNING, BannerLevel.fromString("WARNING"));
                assertEquals(BannerLevel.ERROR, BannerLevel.fromString("Error"));
                assertEquals(BannerLevel.ERROR, BannerLevel.fromString("ERROR"));
            }

            @Test
            @DisplayName("unknown → WARNING")
            void unknownReturnsWarning() {
                assertEquals(BannerLevel.WARNING, BannerLevel.fromString("unknown"));
                assertEquals(BannerLevel.WARNING, BannerLevel.fromString("info"));
                assertEquals(BannerLevel.WARNING, BannerLevel.fromString(""));
            }
        }

        @Test
        @DisplayName("value() returns serialized string")
        void valueReturnsSerializedString() {
            assertEquals("warning", BannerLevel.WARNING.value());
            assertEquals("error", BannerLevel.ERROR.value());
        }
    }

    // ── ClearOn ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ClearOn")
    class ClearOnTests {

        @Nested
        @DisplayName("fromString")
        class FromString {

            @Test
            @DisplayName("null → MANUAL")
            void nullReturnsManual() {
                assertEquals(ClearOn.MANUAL, ClearOn.fromString(null));
            }

            @Test
            @DisplayName("matches by value")
            void matchesByValue() {
                assertEquals(ClearOn.NEXT_SUCCESS, ClearOn.fromString("next_success"));
                assertEquals(ClearOn.MANUAL, ClearOn.fromString("manual"));
            }

            @Test
            @DisplayName("case-insensitive matching")
            void caseInsensitive() {
                assertEquals(ClearOn.NEXT_SUCCESS, ClearOn.fromString("NEXT_SUCCESS"));
                assertEquals(ClearOn.MANUAL, ClearOn.fromString("MANUAL"));
                assertEquals(ClearOn.NEXT_SUCCESS, ClearOn.fromString("Next_Success"));
            }

            @Test
            @DisplayName("unknown → MANUAL")
            void unknownReturnsManual() {
                assertEquals(ClearOn.MANUAL, ClearOn.fromString("unknown"));
                assertEquals(ClearOn.MANUAL, ClearOn.fromString(""));
            }
        }

        @Test
        @DisplayName("value() returns serialized string")
        void valueReturnsSerializedString() {
            assertEquals("next_success", ClearOn.NEXT_SUCCESS.value());
            assertEquals("manual", ClearOn.MANUAL.value());
        }
    }

    // ── AgentMessageChunk ────────────────────────────────────────────────────

    @Nested
    @DisplayName("AgentMessageChunk")
    class AgentMessageChunkTests {

        @Test
        @DisplayName("text() with empty list returns empty string")
        void emptyListReturnsEmptyString() {
            var chunk = new AgentMessageChunk(List.of());
            assertEquals("", chunk.text());
        }

        @Test
        @DisplayName("text() with single Text block returns that text")
        void singleTextBlock() {
            var chunk = new AgentMessageChunk(List.of(new ContentBlock.Text("hello")));
            assertEquals("hello", chunk.text());
        }

        @Test
        @DisplayName("text() concatenates Text and Thinking blocks")
        void mixedTextAndThinking() {
            var chunk = new AgentMessageChunk(List.of(
                new ContentBlock.Text("hello "),
                new ContentBlock.Thinking("reasoning"),
                new ContentBlock.Text(" world")
            ));
            assertEquals("hello reasoning world", chunk.text());
        }

        @Test
        @DisplayName("text() skips Image blocks")
        void skipsImageBlocks() {
            var chunk = new AgentMessageChunk(List.of(
                new ContentBlock.Text("before"),
                new ContentBlock.Image("data", "image/png"),
                new ContentBlock.Text("after")
            ));
            assertEquals("beforeafter", chunk.text());
        }

        @Test
        @DisplayName("text() skips Audio blocks")
        void skipsAudioBlocks() {
            var chunk = new AgentMessageChunk(List.of(
                new ContentBlock.Text("before"),
                new ContentBlock.Audio("data", "audio/mp3"),
                new ContentBlock.Text("after")
            ));
            assertEquals("beforeafter", chunk.text());
        }

        @Test
        @DisplayName("text() concatenates multiple Text blocks")
        void multipleTextBlocks() {
            var chunk = new AgentMessageChunk(List.of(
                new ContentBlock.Text("one"),
                new ContentBlock.Text("two"),
                new ContentBlock.Text("three")
            ));
            assertEquals("onetwothree", chunk.text());
        }
    }

    // ── AgentThoughtChunk ────────────────────────────────────────────────────

    @Nested
    @DisplayName("AgentThoughtChunk")
    class AgentThoughtChunkTests {

        @Test
        @DisplayName("text() with empty list returns empty string")
        void emptyListReturnsEmptyString() {
            var chunk = new AgentThoughtChunk(List.of());
            assertEquals("", chunk.text());
        }

        @Test
        @DisplayName("text() concatenates Text and Thinking blocks")
        void mixedTextAndThinking() {
            var chunk = new AgentThoughtChunk(List.of(
                new ContentBlock.Thinking("thought"),
                new ContentBlock.Text(" and text")
            ));
            assertEquals("thought and text", chunk.text());
        }

        @Test
        @DisplayName("text() skips Image and Audio blocks")
        void skipsNonTextBlocks() {
            var chunk = new AgentThoughtChunk(List.of(
                new ContentBlock.Thinking("thought"),
                new ContentBlock.Image("img", "image/png"),
                new ContentBlock.Audio("audio", "audio/wav")
            ));
            assertEquals("thought", chunk.text());
        }
    }

    // ── UserMessageChunk ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("UserMessageChunk")
    class UserMessageChunkTests {

        @Test
        @DisplayName("text() with empty list returns empty string")
        void emptyListReturnsEmptyString() {
            var chunk = new UserMessageChunk(List.of());
            assertEquals("", chunk.text());
        }

        @Test
        @DisplayName("text() with single Text block returns that text")
        void singleTextBlock() {
            var chunk = new UserMessageChunk(List.of(new ContentBlock.Text("hello")));
            assertEquals("hello", chunk.text());
        }

        @Test
        @DisplayName("text() extracts only Text blocks, NOT Thinking")
        void excludesThinkingBlocks() {
            var chunk = new UserMessageChunk(List.of(
                new ContentBlock.Text("visible"),
                new ContentBlock.Thinking("hidden thought"),
                new ContentBlock.Text(" text")
            ));
            assertEquals("visible text", chunk.text());
        }

        @Test
        @DisplayName("text() skips Image blocks")
        void skipsImageBlocks() {
            var chunk = new UserMessageChunk(List.of(
                new ContentBlock.Text("before"),
                new ContentBlock.Image("data", "image/png"),
                new ContentBlock.Text("after")
            ));
            assertEquals("beforeafter", chunk.text());
        }

        @Test
        @DisplayName("text() with only Thinking blocks returns empty string")
        void onlyThinkingReturnsEmpty() {
            var chunk = new UserMessageChunk(List.of(
                new ContentBlock.Thinking("thought1"),
                new ContentBlock.Thinking("thought2")
            ));
            assertEquals("", chunk.text());
        }

        @Test
        @DisplayName("text() concatenates multiple Text blocks")
        void multipleTextBlocks() {
            var chunk = new UserMessageChunk(List.of(
                new ContentBlock.Text("one"),
                new ContentBlock.Text("two"),
                new ContentBlock.Text("three")
            ));
            assertEquals("onetwothree", chunk.text());
        }
    }

    // ── ToolCall ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolCall")
    class ToolCallTests {

        private ToolCall toolCall(String agentType, List<Location> locations) {
            return new ToolCall(
                "tc-1", "title", ToolKind.READ, null,
                locations, agentType, null, null, null
            );
        }

        @Nested
        @DisplayName("isSubAgent")
        class IsSubAgent {

            @Test
            @DisplayName("agentType=null → false")
            void nullAgentTypeReturnsFalse() {
                assertFalse(toolCall(null, null).isSubAgent());
            }

            @Test
            @DisplayName("agentType='explore' → true")
            void nonNullAgentTypeReturnsTrue() {
                assertTrue(toolCall("explore", null).isSubAgent());
            }

            @Test
            @DisplayName("agentType='' (empty) → true")
            void emptyAgentTypeReturnsTrue() {
                assertTrue(toolCall("", null).isSubAgent());
            }
        }

        @Nested
        @DisplayName("filePaths")
        class FilePaths {

            @Test
            @DisplayName("null locations → empty list")
            void nullLocationsReturnsEmptyList() {
                assertEquals(List.of(), toolCall(null, null).filePaths());
            }

            @Test
            @DisplayName("empty locations → empty list")
            void emptyLocationsReturnsEmptyList() {
                assertEquals(List.of(), toolCall(null, List.of()).filePaths());
            }

            @Test
            @DisplayName("locations with valid URIs → extracts them")
            void extractsValidUris() {
                var locations = List.of(
                    new Location("/src/Main.java", null),
                    new Location("/src/Test.java", new Location.Range(
                        new Location.Position(1, 0),
                        new Location.Position(10, 0)
                    ))
                );
                assertEquals(
                    List.of("/src/Main.java", "/src/Test.java"),
                    toolCall(null, locations).filePaths()
                );
            }

            @Test
            @DisplayName("locations with null URIs → filtered out")
            void filtersNullUris() {
                var locations = List.of(
                    new Location(null, null),
                    new Location("/src/Valid.java", null),
                    new Location(null, null)
                );
                assertEquals(
                    List.of("/src/Valid.java"),
                    toolCall(null, locations).filePaths()
                );
            }

            @Test
            @DisplayName("locations with empty URIs → filtered out")
            void filtersEmptyUris() {
                var locations = List.of(
                    new Location("", null),
                    new Location("/src/Valid.java", null),
                    new Location("", null)
                );
                assertEquals(
                    List.of("/src/Valid.java"),
                    toolCall(null, locations).filePaths()
                );
            }

            @Test
            @DisplayName("mixed null, empty, and valid URIs")
            void mixedUris() {
                var locations = List.of(
                    new Location(null, null),
                    new Location("", null),
                    new Location("/src/A.java", null),
                    new Location("/src/B.java", null)
                );
                assertEquals(
                    List.of("/src/A.java", "/src/B.java"),
                    toolCall(null, locations).filePaths()
                );
            }
        }
    }
}

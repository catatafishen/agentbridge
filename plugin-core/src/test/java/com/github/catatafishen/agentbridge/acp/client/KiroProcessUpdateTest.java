package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link KiroClient#convertThinkingToThought(SessionUpdate)} — the Kiro-specific
 * conversion of {@link SessionUpdate.AgentMessageChunk} with {@link ContentBlock.Thinking}
 * blocks to {@link SessionUpdate.AgentThoughtChunk} for proper UI rendering.
 */
@DisplayName("KiroClient.convertThinkingToThought")
class KiroProcessUpdateTest {

    // ── Conversion cases ────────────────────────────────────────────────────

    @Nested
    @DisplayName("AgentMessageChunk → AgentThoughtChunk conversion")
    class ThinkingConversion {

        @Test
        @DisplayName("converts message chunk with only Thinking blocks to thought chunk")
        void onlyThinkingBlocks() {
            var content = List.<ContentBlock>of(new ContentBlock.Thinking("reasoning step"));
            var input = new SessionUpdate.AgentMessageChunk(content);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, result);
            var thought = (SessionUpdate.AgentThoughtChunk) result;
            assertEquals(content, thought.content());
        }

        @Test
        @DisplayName("converts message chunk with mixed Text+Thinking to thought chunk")
        void mixedTextAndThinking() {
            var content = List.<ContentBlock>of(
                new ContentBlock.Text("visible text"),
                new ContentBlock.Thinking("internal reasoning")
            );
            var input = new SessionUpdate.AgentMessageChunk(content);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, result);
            var thought = (SessionUpdate.AgentThoughtChunk) result;
            assertEquals(2, thought.content().size());
        }

        @Test
        @DisplayName("preserves original content blocks in converted thought chunk")
        void preservesContent() {
            var thinking = new ContentBlock.Thinking("step 1");
            var text = new ContentBlock.Text("annotation");
            var content = List.<ContentBlock>of(thinking, text);
            var input = new SessionUpdate.AgentMessageChunk(content);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            var thought = (SessionUpdate.AgentThoughtChunk) result;
            assertSame(thinking, thought.content().get(0));
            assertSame(text, thought.content().get(1));
        }
    }

    // ── No conversion cases ─────────────────────────────────────────────────

    @Nested
    @DisplayName("No conversion needed")
    class NoConversion {

        @Test
        @DisplayName("message chunk with only Text blocks is returned unchanged")
        void onlyTextBlocks() {
            var content = List.<ContentBlock>of(new ContentBlock.Text("hello"));
            var input = new SessionUpdate.AgentMessageChunk(content);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("message chunk with no content blocks is returned unchanged")
        void emptyContent() {
            var input = new SessionUpdate.AgentMessageChunk(List.of());

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("AgentThoughtChunk is returned as-is (already the right type)")
        void alreadyThoughtChunk() {
            var content = List.<ContentBlock>of(new ContentBlock.Thinking("thought"));
            var input = new SessionUpdate.AgentThoughtChunk(content);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("ToolCall is returned as-is (not a message chunk)")
        void toolCallPassthrough() {
            var input = new SessionUpdate.ToolCall(
                "tc-1", "read_file", null, null, "{}", null, null, null, null, null
            );

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("ToolCallUpdate is returned as-is")
        void toolCallUpdatePassthrough() {
            var input = new SessionUpdate.ToolCallUpdate(
                "tc-1", SessionUpdate.ToolCallStatus.COMPLETED, "result", null, null
            );

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("UserMessageChunk is returned as-is")
        void userMessagePassthrough() {
            var content = List.<ContentBlock>of(new ContentBlock.Text("user text"));
            var input = new SessionUpdate.UserMessageChunk(content);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("Banner is returned as-is")
        void bannerPassthrough() {
            var input = new SessionUpdate.Banner(
                "warning msg", SessionUpdate.BannerLevel.WARNING, SessionUpdate.ClearOn.MANUAL
            );

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("TurnUsage is returned as-is")
        void turnUsagePassthrough() {
            var input = new SessionUpdate.TurnUsage(100, 200, 0.05);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("message chunk with Image and Audio blocks (no Thinking) is unchanged")
        void nonThinkingMediaBlocks() {
            var content = List.<ContentBlock>of(
                new ContentBlock.Text("caption"),
                new ContentBlock.Image("base64", "image/png"),
                new ContentBlock.Audio("base64", "audio/wav")
            );
            var input = new SessionUpdate.AgentMessageChunk(content);

            SessionUpdate result = KiroClient.convertThinkingToThought(input);

            assertSame(input, result);
        }
    }
}

package com.github.catatafishen.agentbridge.psi.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpRequestDeadline}, the shared guard that keeps blocking tools from waiting
 * longer than the MCP client is willing to keep the request open.
 */
class McpRequestDeadlineTest {

    @Nested
    @DisplayName("clamp")
    class Clamp {

        @Test
        @DisplayName("a timeout below the ceiling is passed through unchanged")
        void belowCeiling() {
            assertEquals(60, McpRequestDeadline.clamp(60, 60));
        }

        @Test
        @DisplayName("a timeout exactly at the ceiling is passed through unchanged")
        void atCeiling() {
            assertEquals(McpRequestDeadline.MAX_TIMEOUT_SECONDS,
                McpRequestDeadline.clamp(McpRequestDeadline.MAX_TIMEOUT_SECONDS, 60));
        }

        @Test
        @DisplayName("a timeout one second above the ceiling is clamped")
        void justAboveCeiling() {
            assertEquals(McpRequestDeadline.MAX_TIMEOUT_SECONDS,
                McpRequestDeadline.clamp(McpRequestDeadline.MAX_TIMEOUT_SECONDS + 1, 60));
        }

        @Test
        @DisplayName("the 300s default the test tools used to send is clamped")
        void formerTestDefaultIsClamped() {
            assertEquals(McpRequestDeadline.MAX_TIMEOUT_SECONDS, McpRequestDeadline.clamp(300, 150));
        }

        @Test
        @DisplayName("zero and negative timeouts fall back to the default, not to instant failure")
        void nonPositiveFallsBackToDefault() {
            assertEquals(60, McpRequestDeadline.clamp(0, 60));
            assertEquals(60, McpRequestDeadline.clamp(-5, 60));
        }

        @Test
        @DisplayName("a default above the ceiling is itself clamped")
        void oversizedDefaultIsClamped() {
            assertEquals(McpRequestDeadline.MAX_TIMEOUT_SECONDS, McpRequestDeadline.clamp(0, 600));
        }

        @Test
        @DisplayName("the ceiling leaves headroom under the measured ~180s client deadline")
        void ceilingLeavesHeadroom() {
            assertTrue(McpRequestDeadline.MAX_TIMEOUT_SECONDS < 180,
                "the clamp must sit below the deadline it is protecting against");
        }
    }

    @Nested
    @DisplayName("clampNotice")
    class ClampNotice {

        @Test
        @DisplayName("no notice when the requested timeout was honoured")
        void noNoticeWhenHonoured() {
            assertNull(McpRequestDeadline.clampNotice(60));
            assertNull(McpRequestDeadline.clampNotice(McpRequestDeadline.MAX_TIMEOUT_SECONDS));
        }

        @Test
        @DisplayName("notice states both the requested and the effective timeout")
        void noticeStatesBothValues() {
            String notice = McpRequestDeadline.clampNotice(600);

            assertNotNull(notice);
            assertTrue(notice.contains("600"));
            assertTrue(notice.contains(String.valueOf(McpRequestDeadline.MAX_TIMEOUT_SECONDS)));
        }

        @Test
        @DisplayName("notice points at the tools that can outlive the request deadline")
        void noticePointsAtAlternative() {
            String notice = McpRequestDeadline.clampNotice(600);

            assertNotNull(notice);
            assertTrue(notice.contains("run_in_terminal"));
            assertTrue(notice.contains("read_terminal_output"));
        }
    }

    @Nested
    @DisplayName("prependNotice")
    class PrependNotice {

        @Test
        @DisplayName("a null notice leaves the body untouched")
        void nullNoticeReturnsBody() {
            assertEquals("Command succeeded", McpRequestDeadline.prependNotice(null, "Command succeeded"));
        }

        @Test
        @DisplayName("a notice is separated from the body by a blank line")
        void noticeIsSeparated() {
            assertEquals("Note\n\nbody", McpRequestDeadline.prependNotice("Note", "body"));
        }
    }
}

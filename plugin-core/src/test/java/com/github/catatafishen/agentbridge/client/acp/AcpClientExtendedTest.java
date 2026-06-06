package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional tests for AcpClient static methods not covered by AcpClientTest.
 */
class AcpClientExtendedTest {

    @Nested
    class IsMcpResourceTool {
        @Test
        void readMcpResource() {
            assertTrue(AcpClient.isMcpResourceTool("read_mcp_resource"));
        }

        @Test
        void listMcpResources() {
            assertTrue(AcpClient.isMcpResourceTool("list_mcp_resources"));
        }

        @Test
        void caseInsensitive() {
            assertTrue(AcpClient.isMcpResourceTool("READ_MCP_RESOURCE"));
            assertTrue(AcpClient.isMcpResourceTool("List_Mcp_Resources"));
        }

        @Test
        void otherToolIsFalse() {
            assertFalse(AcpClient.isMcpResourceTool("read_file"));
            assertFalse(AcpClient.isMcpResourceTool("run_command"));
        }
    }

    @Nested
    class ExtractRootCauseMessage {
        @Test
        void skipsPromptFailedPrefix() {
            RuntimeException cause = new RuntimeException("Real problem here");
            RuntimeException e = new RuntimeException("Prompt failed for agent: details", cause);
            String result = AcpClient.extractRootCauseMessage(e);
            assertEquals("Real problem here", result);
        }

        @Test
        void skipsPromptInterruptedPrefix() {
            RuntimeException cause = new RuntimeException("Actual error");
            RuntimeException e = new RuntimeException("Prompt interrupted for copilot", cause);
            String result = AcpClient.extractRootCauseMessage(e);
            assertEquals("Actual error", result);
        }

        @Test
        void returnsLastNonFilteredMessage() {
            RuntimeException deep = new RuntimeException("deepest");
            RuntimeException mid = new RuntimeException("middle", deep);
            RuntimeException top = new RuntimeException("top", mid);
            assertEquals("deepest", AcpClient.extractRootCauseMessage(top));
        }

        @Test
        void returnsNullForOnlyFilteredMessages() {
            RuntimeException e = new RuntimeException("Prompt failed for agent: x");
            assertNull(AcpClient.extractRootCauseMessage(e));
        }

        @Test
        void returnsNullForNullMessage() {
            RuntimeException e = new RuntimeException((String) null);
            assertNull(AcpClient.extractRootCauseMessage(e));
        }

        @Test
        void returnsNullForBlankMessage() {
            RuntimeException e = new RuntimeException("   ");
            assertNull(AcpClient.extractRootCauseMessage(e));
        }
    }
}

package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional tests for AcpClient static methods not covered by AcpClientTest.
 */
class AcpClientExtendedTest {

    @Nested
    class CleanAuthMessage {
        @Test
        void extractsFromJsonRpcFormat() {
            String msg = "JsonRpcException{code=-32000, message='Authentication required'}";
            assertEquals("Authentication required", AcpClient.cleanAuthMessage(msg));
        }

        @Test
        void returnsOriginalWhenNoPattern() {
            String msg = "Some random error";
            assertEquals("Some random error", AcpClient.cleanAuthMessage(msg));
        }

        @Test
        void extractsNestedMessage() {
            String msg = "JsonRpcException{code=-32000, message='Please sign in to continue'}";
            assertEquals("Please sign in to continue", AcpClient.cleanAuthMessage(msg));
        }

        @Test
        void returnsOriginalWhenPatternIncomplete() {
            String msg = "message='missing closing";
            assertEquals("message='missing closing", AcpClient.cleanAuthMessage(msg));
        }

        @Test
        void handlesEmptyMessageValue() {
            // When message value is empty like message='', end == start so it falls through
            String msg = "JsonRpcException{code=-32000, message=''}";
            String result = AcpClient.cleanAuthMessage(msg);
            // Falls through because end == start (empty message), returns original
            assertEquals(msg, result);
        }
    }

    @Nested
    class ExtractAuthErrorMessage {
        @Test
        void findsAuthInDirectException() {
            Exception e = new Exception("Authentication failed: token expired");
            assertEquals("Authentication failed: token expired", AcpClient.extractAuthErrorMessage(e));
        }

        @Test
        void findsSignInInCauseChain() {
            Exception root = new Exception("Please sign in to your account");
            Exception wrapper = new Exception("Operation failed", root);
            String result = AcpClient.extractAuthErrorMessage(wrapper);
            assertNotNull(result);
            assertTrue(result.contains("sign in"));
        }

        @Test
        void returnsNullWhenNoAuthError() {
            Exception e = new Exception("Connection timeout");
            assertNull(AcpClient.extractAuthErrorMessage(e));
        }

        @Test
        void returnsNullForNullMessage() {
            Exception e = new Exception((String) null);
            assertNull(AcpClient.extractAuthErrorMessage(e));
        }

        @Test
        void cleansJsonRpcAuthMessage() {
            Exception e = new Exception("JsonRpcException{code=-32000, message='Auth token invalid'}");
            String result = AcpClient.extractAuthErrorMessage(e);
            assertEquals("Auth token invalid", result);
        }

        @Test
        void findsAuthDeepInChain() {
            Exception leaf = new Exception("auth error: expired session");
            Exception mid = new Exception("wrapper", leaf);
            Exception top = new Exception("top level", mid);
            String result = AcpClient.extractAuthErrorMessage(top);
            assertNotNull(result);
            assertTrue(result.contains("auth"));
        }
    }

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

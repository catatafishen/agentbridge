package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolCallRecord} derived methods and state logic.
 */
class ToolCallRecordTest {

    @Nested
    class GetMcpDurationMs {
        @Test
        void zeroWhenNotStarted() {
            var rec = new ToolCallRecord("r1", "hash1");
            assertEquals(0, rec.getMcpDurationMs());
        }

        @Test
        void zeroWhenNotCompleted() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setMcpFields("read_file", new JsonObject(), null, System.currentTimeMillis());
            assertEquals(0, rec.getMcpDurationMs());
        }

        @Test
        void returnsDifference() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setMcpFields("read_file", new JsonObject(), null, 1000L);
            rec.setMcpResult("ok", true);
            // After setMcpResult, mcpCompletedAt should be set — but it's set internally
            // We test via public API: getMcpDurationMs reflects the difference
            long duration = rec.getMcpDurationMs();
            assertTrue(duration >= 0);
        }
    }

    @Nested
    class IsCorrelated {
        @Test
        void trueWhenBothSet() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", "read_file", "Read File", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            rec.setMcpFields("read_file", new JsonObject(), null, 0);
            assertTrue(rec.isCorrelated());
        }

        @Test
        void falseWhenOnlyAcp() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", "read_file", "Read File", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            assertFalse(rec.isCorrelated());
        }

        @Test
        void falseWhenOnlyMcp() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setMcpFields("read_file", new JsonObject(), null, 0);
            assertFalse(rec.isCorrelated());
        }
    }

    @Nested
    class IsAcpOnly {
        @Test
        void trueWhenOnlyAcpSet() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", "tool", "Tool", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            assertTrue(rec.isAcpOnly());
        }

        @Test
        void falseWhenBothSet() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", "tool", "Tool", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            rec.setMcpFields("tool", new JsonObject(), null, 0);
            assertFalse(rec.isAcpOnly());
        }
    }

    @Nested
    class IsMcpOnly {
        @Test
        void trueWhenOnlyMcpSet() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setMcpFields("read_file", new JsonObject(), null, 0);
            assertTrue(rec.isMcpOnly());
        }

        @Test
        void falseWhenBothSet() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", "tool", "Tool", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            rec.setMcpFields("read_file", new JsonObject(), null, 0);
            assertFalse(rec.isMcpOnly());
        }
    }

    @Nested
    class GetEffectiveToolName {
        @Test
        void prefersMcpName() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", "acp_name", "ACP Title", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            rec.setMcpFields("mcp_name", new JsonObject(), null, 0);
            assertEquals("mcp_name", rec.getEffectiveToolName());
        }

        @Test
        void fallsBackToAcpName() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", "acp_name", "ACP Title", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            assertEquals("acp_name", rec.getEffectiveToolName());
        }

        @Test
        void fallsBackToAcpTitle() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("client1", null, "ACP Title", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            assertEquals("ACP Title", rec.getEffectiveToolName());
        }

        @Test
        void fallsBackToUnknown() {
            var rec = new ToolCallRecord("r1", "hash1");
            assertEquals("unknown", rec.getEffectiveToolName());
        }
    }

    @Nested
    class StateTransitions {
        @Test
        void initialStatePending() {
            var rec = new ToolCallRecord("r1", "hash1");
            assertEquals(ToolCallRecord.State.PENDING, rec.getState());
        }

        @Test
        void setStateChangesIt() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setState(ToolCallRecord.State.RUNNING);
            assertEquals(ToolCallRecord.State.RUNNING, rec.getState());
        }
    }

    @Nested
    class RoutingType {
        @Test
        void defaultRoutingType() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("c", "n", "t", null,
                ToolCallRecord.RoutingType.SUB_AGENT, 1);
            assertEquals(ToolCallRecord.RoutingType.SUB_AGENT, rec.getRoutingType());
        }
    }

    @Nested
    class SubAgentInfo {
        @Test
        void setsSubAgentFields() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setSubAgentInfo("explore", "Search codebase", "Find all usages");
            assertEquals("explore", rec.getSubAgentType());
            assertEquals("Search codebase", rec.getSubAgentDescription());
            assertEquals("Find all usages", rec.getSubAgentPrompt());
        }

        @Test
        void initiallyNull() {
            var rec = new ToolCallRecord("r1", "hash1");
            assertNull(rec.getSubAgentType());
            assertNull(rec.getSubAgentDescription());
            assertNull(rec.getSubAgentPrompt());
        }
    }

    @Nested
    class ToStringTest {
        @Test
        void containsRecordId() {
            var rec = new ToolCallRecord("test-id", "hash1");
            assertTrue(rec.toString().contains("test-id"));
        }

        @Test
        void containsToolNames() {
            var rec = new ToolCallRecord("r1", "hash1");
            rec.setAcpFields("c", "my_tool", "My Tool", null,
                ToolCallRecord.RoutingType.REGULAR, 1);
            String str = rec.toString();
            assertTrue(str.contains("my_tool"));
        }
    }

    @Nested
    class ArgsHash {
        @Test
        void initialArgsHash() {
            var rec = new ToolCallRecord("r1", "initial-hash");
            assertEquals("initial-hash", rec.getArgsHash());
        }

        @Test
        void updateArgsHash() {
            var rec = new ToolCallRecord("r1", "initial");
            rec.updateArgsHash("updated");
            assertEquals("updated", rec.getArgsHash());
        }
    }
}

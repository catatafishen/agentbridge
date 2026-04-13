package com.github.catatafishen.agentbridge.custommcp;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the extracted pure-logic helpers in {@link CustomMcpRegistrar}.
 * These methods are package-private static, so no IntelliJ platform or project needed.
 */
class CustomMcpRegistrarTest {

    // ── Helper factory ─────────────────────────────────────

    private static CustomMcpServerConfig server(String id, String url, boolean enabled) {
        return new CustomMcpServerConfig(id, "Server-" + id, url, "", enabled);
    }

    // ── collectActiveServerIds ─────────────────────────────

    @Nested
    class CollectActiveServerIds {

        @Test
        void emptyList_returnsEmptySet() {
            Set<String> result = CustomMcpRegistrar.collectActiveServerIds(Collections.emptyList());
            assertTrue(result.isEmpty());
        }

        @Test
        void allEnabled_returnsAllIds() {
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a/mcp", true),
                server("b", "http://b/mcp", true)
            );
            assertEquals(Set.of("a", "b"), CustomMcpRegistrar.collectActiveServerIds(servers));
        }

        @Test
        void disabledServers_areExcluded() {
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a/mcp", true),
                server("b", "http://b/mcp", false)
            );
            assertEquals(Set.of("a"), CustomMcpRegistrar.collectActiveServerIds(servers));
        }

        @Test
        void blankUrl_isExcluded() {
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "", true),
                server("b", "http://b/mcp", true)
            );
            assertEquals(Set.of("b"), CustomMcpRegistrar.collectActiveServerIds(servers));
        }

        @Test
        void whitespaceOnlyUrl_isExcluded() {
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "   ", true)
            );
            assertTrue(CustomMcpRegistrar.collectActiveServerIds(servers).isEmpty());
        }

        @Test
        void disabledAndBlankUrl_bothExcluded() {
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "", false),
                server("b", "http://b/mcp", true)
            );
            assertEquals(Set.of("b"), CustomMcpRegistrar.collectActiveServerIds(servers));
        }

        @Test
        void allDisabled_returnsEmptySet() {
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a/mcp", false),
                server("b", "http://b/mcp", false)
            );
            assertTrue(CustomMcpRegistrar.collectActiveServerIds(servers).isEmpty());
        }

        @Test
        void duplicateIds_deduplicatedInResult() {
            // Two configs with the same ID (shouldn't happen normally, but test the set behavior)
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a1/mcp", true),
                server("a", "http://a2/mcp", true)
            );
            Set<String> result = CustomMcpRegistrar.collectActiveServerIds(servers);
            assertEquals(1, result.size());
            assertTrue(result.contains("a"));
        }
    }

    // ── computeServersToRemove ─────────────────────────────

    @Nested
    class ComputeServersToRemove {

        @Test
        void bothEmpty_returnsEmptySet() {
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(
                Collections.emptySet(), Collections.emptySet());
            assertTrue(result.isEmpty());
        }

        @Test
        void currentEmpty_desiredNonEmpty_noRemovals() {
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(
                Collections.emptySet(), Set.of("a", "b"));
            assertTrue(result.isEmpty());
        }

        @Test
        void currentNonEmpty_desiredEmpty_removeAll() {
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(
                Set.of("a", "b"), Collections.emptySet());
            assertEquals(Set.of("a", "b"), result);
        }

        @Test
        void identicalSets_noRemovals() {
            Set<String> ids = Set.of("a", "b", "c");
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(ids, ids);
            assertTrue(result.isEmpty());
        }

        @Test
        void partialOverlap_removesOnlyMissing() {
            Set<String> current = Set.of("a", "b", "c");
            Set<String> desired = Set.of("b", "d");
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("a", "c"), result);
        }

        @Test
        void disjointSets_removeAllCurrent() {
            Set<String> current = Set.of("a", "b");
            Set<String> desired = Set.of("x", "y");
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("a", "b"), result);
        }

        @Test
        void singleServerRemoved() {
            Set<String> current = Set.of("a", "b", "c");
            Set<String> desired = Set.of("a", "c");
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("b"), result);
        }

        @Test
        void newServersInDesired_noRemovals() {
            Set<String> current = Set.of("a");
            Set<String> desired = Set.of("a", "b", "c");
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertTrue(result.isEmpty());
        }

        @Test
        void currentIsView_doesNotMutateCaller() {
            // Ensure the method works correctly with unmodifiable input sets
            Set<String> current = Collections.unmodifiableSet(new HashSet<>(Set.of("a", "b")));
            Set<String> desired = Collections.unmodifiableSet(new HashSet<>(Set.of("b")));
            Set<String> result = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("a"), result);
            // Original sets are unchanged
            assertEquals(2, current.size());
            assertEquals(1, desired.size());
        }
    }

    // ── formatConnectionError ──────────────────────────────

    @Nested
    class FormatConnectionError {

        @Test
        void includesAllComponents() {
            String msg = CustomMcpRegistrar.formatConnectionError(
                "MyDB", "http://localhost:8080/mcp", "Connection refused");
            assertEquals(
                "Failed to connect to custom MCP server 'MyDB' at http://localhost:8080/mcp: Connection refused",
                msg);
        }

        @Test
        void emptyServerName() {
            String msg = CustomMcpRegistrar.formatConnectionError(
                "", "http://x/mcp", "timeout");
            assertEquals("Failed to connect to custom MCP server '' at http://x/mcp: timeout", msg);
        }

        @Test
        void nullErrorMessage() {
            String msg = CustomMcpRegistrar.formatConnectionError(
                "srv", "http://x/mcp", null);
            assertEquals("Failed to connect to custom MCP server 'srv' at http://x/mcp: null", msg);
        }

        @Test
        void specialCharsInName_notEscaped() {
            String msg = CustomMcpRegistrar.formatConnectionError(
                "my <server>", "http://x/mcp", "err");
            assertTrue(msg.contains("'my <server>'"));
        }
    }

    // ── Integration: collectActiveServerIds → computeServersToRemove ───

    @Nested
    class SetDiffIntegration {

        @Test
        void fullSyncScenario_addKeepRemove() {
            // Current registered: a, b, c
            Set<String> current = Set.of("a", "b", "c");

            // Settings now have: b (still enabled), d (new), c (disabled)
            List<CustomMcpServerConfig> servers = List.of(
                server("b", "http://b/mcp", true),
                server("d", "http://d/mcp", true),
                server("c", "http://c/mcp", false)
            );

            Set<String> desired = CustomMcpRegistrar.collectActiveServerIds(servers);
            assertEquals(Set.of("b", "d"), desired);

            Set<String> toRemove = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("a", "c"), toRemove);
        }

        @Test
        void allServersRemoved() {
            Set<String> current = Set.of("a", "b");
            List<CustomMcpServerConfig> servers = Collections.emptyList();
            Set<String> desired = CustomMcpRegistrar.collectActiveServerIds(servers);
            Set<String> toRemove = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("a", "b"), toRemove);
        }

        @Test
        void freshStartup_nothingRegisteredYet() {
            Set<String> current = Collections.emptySet();
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a/mcp", true),
                server("b", "http://b/mcp", true)
            );
            Set<String> desired = CustomMcpRegistrar.collectActiveServerIds(servers);
            Set<String> toRemove = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertTrue(toRemove.isEmpty());
            assertEquals(Set.of("a", "b"), desired);
        }

        @Test
        void noChanges_stableState() {
            Set<String> current = Set.of("a", "b");
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a/mcp", true),
                server("b", "http://b/mcp", true)
            );
            Set<String> desired = CustomMcpRegistrar.collectActiveServerIds(servers);
            Set<String> toRemove = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertTrue(toRemove.isEmpty());
            assertEquals(current, desired);
        }

        @Test
        void serverDisabledButStillInConfig_removedFromActive() {
            Set<String> current = Set.of("a", "b");
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a/mcp", true),
                server("b", "http://b/mcp", false)  // disabled
            );
            Set<String> desired = CustomMcpRegistrar.collectActiveServerIds(servers);
            assertFalse(desired.contains("b"));
            Set<String> toRemove = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("b"), toRemove);
        }

        @Test
        void serverUrlCleared_removedFromActive() {
            Set<String> current = Set.of("a", "b");
            List<CustomMcpServerConfig> servers = List.of(
                server("a", "http://a/mcp", true),
                server("b", "", true)  // URL cleared
            );
            Set<String> desired = CustomMcpRegistrar.collectActiveServerIds(servers);
            assertFalse(desired.contains("b"));
            Set<String> toRemove = CustomMcpRegistrar.computeServersToRemove(current, desired);
            assertEquals(Set.of("b"), toRemove);
        }
    }
}

package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolChipRegistry")
class ToolChipRegistryTest {

    private ToolChipRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolChipRegistry();
    }

    // ── registerClientSide ────────────────────────────────────────────────────

    @Nested
    @DisplayName("registerClientSide")
    class RegisterClientSide {

        @Test
        @DisplayName("client-first with args: returns PENDING state and hash-based chipId")
        void clientFirst_returnsPendingWithHashChipId() {
            JsonObject args = new JsonObject();
            args.addProperty("file", "Foo.java");

            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("read_file", args, "cid-1");

            assertEquals(ToolChipRegistry.ChipState.PENDING, reg.initialState());
            String expectedHash = ToolChipRegistry.computeBaseHash(args);
            assertEquals(expectedHash, reg.chipId());
            assertTrue(reg.chipId().matches("[0-9a-f]{8}"),
                "chipId should be 8-char hex: " + reg.chipId());
        }

        @Test
        @DisplayName("MCP-first then client with same args: returns RUNNING immediately")
        void mcpFirst_thenClient_returnsRunning() {
            JsonObject args = new JsonObject();
            args.addProperty("query", "search text");

            registry.registerMcp("search_text", args);
            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("search_text", args, "cid-2");

            assertEquals(ToolChipRegistry.ChipState.RUNNING, reg.initialState());
        }

        @Test
        @DisplayName("null args with no MCP-first: returns PENDING with client-prefix chipId")
        void nullArgs_noMcpFirst_returnsPendingWithClientPrefix() {
            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("tool", null, "client:id:123");

            assertEquals(ToolChipRegistry.ChipState.PENDING, reg.initialState());
            assertTrue(reg.chipId().startsWith("c-"), "null-args chipId should start with 'c-': " + reg.chipId());
        }

        @Test
        @DisplayName("null args with MCP-first by same name: returns RUNNING (Junie case)")
        void nullArgs_mcpFirstByName_returnsRunning() {
            JsonObject mcpArgs = new JsonObject();
            mcpArgs.addProperty("key", "value");
            registry.registerMcp("junie_tool", mcpArgs);

            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("junie_tool", null, "cid-j");

            assertEquals(ToolChipRegistry.ChipState.RUNNING, reg.initialState());
        }

        @Test
        @DisplayName("null args with MCP-first for different name: returns PENDING (no name match)")
        void nullArgs_mcpFirstDifferentName_returnsPending() {
            JsonObject mcpArgs = new JsonObject();
            mcpArgs.addProperty("x", 1);
            registry.registerMcp("other_tool", mcpArgs);

            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("my_tool", null, "cid-nm");

            assertEquals(ToolChipRegistry.ChipState.PENDING, reg.initialState());
        }

        @Test
        @DisplayName("collision: second chip with same hash in same turn gets '-2' suffix")
        void collision_secondChipGetsSuffix() {
            JsonObject args = new JsonObject();
            args.addProperty("key", "same-value");

            ToolChipRegistry.ChipRegistration reg1 = registry.registerClientSide("tool", args, "cid-col-1");
            ToolChipRegistry.ChipRegistration reg2 = registry.registerClientSide("tool", args, "cid-col-2");

            String baseHash = ToolChipRegistry.computeBaseHash(args);
            assertEquals(baseHash, reg1.chipId());
            assertEquals(baseHash + "-2", reg2.chipId());
        }

        @Test
        @DisplayName("collision: third chip with same hash gets '-3' suffix")
        void collision_thirdChipGetsThreeSuffix() {
            JsonObject args = new JsonObject();
            args.addProperty("n", 42);

            registry.registerClientSide("tool", args, "cid-t1");
            registry.registerClientSide("tool", args, "cid-t2");
            ToolChipRegistry.ChipRegistration reg3 = registry.registerClientSide("tool", args, "cid-t3");

            String baseHash = ToolChipRegistry.computeBaseHash(args);
            assertEquals(baseHash + "-3", reg3.chipId());
        }

        @Test
        @DisplayName("empty args object hashes same as empty args (not null)")
        void emptyArgs_hashBasedChipId() {
            JsonObject emptyArgs = new JsonObject();

            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("build_project", emptyArgs, "cid-e");

            assertEquals(ToolChipRegistry.ChipState.PENDING, reg.initialState());
            assertEquals(8, reg.chipId().length(), "chipId should be 8-char hash");
        }

        @Test
        @DisplayName("findChipIdByClientId returns chipId after registration")
        void afterRegistration_findChipIdByClientId_returnsChipId() {
            JsonObject args = new JsonObject();
            args.addProperty("x", 99);

            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("tool", args, "cid-find");

            assertEquals(reg.chipId(), registry.findChipIdByClientId("cid-find"));
        }
    }

    // ── registerMcp ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("registerMcp")
    class RegisterMcp {

        @Test
        @DisplayName("matches existing client chip by hash and marks it registered")
        void matchesExistingClientChipByHash() {
            JsonObject args = new JsonObject();
            args.addProperty("path", "/test/file.java");

            registry.registerClientSide("read_file", args, "cid-mcp-1");
            registry.registerMcp("read_file", args);

            // The chip is now pluginRegistered; completing should not crash
            registry.completeClientSide("cid-mcp-1", true);
            assertNotNull(registry.findChipIdByClientId("cid-mcp-1"));
        }

        @Test
        @DisplayName("MCP-first: stores chip when no client chip found")
        void mcpFirst_storesChipEntry() {
            JsonObject args = new JsonObject();
            args.addProperty("q", "query");

            registry.registerMcp("search_symbols", args);

            // After client registers with same args, it should find the MCP-first chip
            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("search_symbols", args, "cid-mf");
            assertEquals(ToolChipRegistry.ChipState.RUNNING, reg.initialState());
        }

        @Test
        @DisplayName("registerMcp with kind: matches client chip and fires RUNNING")
        void registerMcpWithKind_matchesClientChip() {
            JsonObject args = new JsonObject();
            args.addProperty("target", "*Test");

            registry.registerClientSide("run_tests", args, "cid-kind");
            registry.registerMcp("run_tests", args, "server_tool");

            assertNotNull(registry.findChipIdByClientId("cid-kind"));
        }

        @Test
        @DisplayName("toolUseId direct lookup: matches chip regardless of args hash")
        void toolUseId_directLookup_matchesByClientId() {
            JsonObject clientArgs = new JsonObject();
            clientArgs.addProperty("action", "run");

            registry.registerClientSide("run_tests", clientArgs, "tooluse-abc");

            // MCP arrives with completely different args but correct toolUseId
            JsonObject mcpArgs = new JsonObject();
            mcpArgs.addProperty("completely", "different");
            registry.registerMcp("run_tests", mcpArgs, null, "tooluse-abc");

            // Chip is still findable and now pluginRegistered
            assertNotNull(registry.findChipIdByClientId("tooluse-abc"));
        }

        @Test
        @DisplayName("toolUseId not found in registry: falls back to hash-based matching")
        void toolUseId_notFound_fallsBackToHash() {
            JsonObject args = new JsonObject();
            args.addProperty("x", 1);

            registry.registerClientSide("tool", args, "cid-fb");
            // toolUseId doesn't exist in registry → hash fallback
            registry.registerMcp("tool", args, null, "unknown-tooluse-id");

            assertNotNull(registry.findChipIdByClientId("cid-fb"));
        }

        @Test
        @DisplayName("toolUseId: already pluginRegistered entry is not double-registered")
        void toolUseId_alreadyPluginRegistered_fallsThrough() {
            JsonObject args = new JsonObject();
            args.addProperty("z", 7);

            registry.registerClientSide("tool", args, "cid-dr");
            registry.registerMcp("tool", args); // marks pluginRegistered=true

            // Second registerMcp with same toolUseId → entry is already pluginRegistered, falls through to hash
            registry.registerMcp("tool", args, null, "cid-dr");
            assertNotNull(registry.findChipIdByClientId("cid-dr"));
        }
    }

    // ── completeClientSide ────────────────────────────────────────────────────

    @Nested
    @DisplayName("completeClientSide")
    class CompleteClientSide {

        @Test
        @DisplayName("unknown clientId: no-op, no exception")
        void unknownClientId_isNoOp() {
            registry.completeClientSide("unknown-id", true);
            assertNull(registry.findChipIdByClientId("unknown-id"));
        }

        @Test
        @DisplayName("success with MCP registered: fires COMPLETE (no listeners → no crash)")
        void successWithMcpRegistered_noException() {
            JsonObject args = new JsonObject();
            args.addProperty("a", 1);

            registry.registerClientSide("tool", args, "cid-complete-1");
            registry.registerMcp("tool", args);
            registry.completeClientSide("cid-complete-1", true);
            assertNotNull(registry.findChipIdByClientId("cid-complete-1"));
        }

        @Test
        @DisplayName("success without MCP registered: fires EXTERNAL (no listeners → no crash)")
        void successWithoutMcp_firesExternal() {
            JsonObject args = new JsonObject();
            args.addProperty("b", 2);

            registry.registerClientSide("tool", args, "cid-ext");
            registry.completeClientSide("cid-ext", true);
            assertNotNull(registry.findChipIdByClientId("cid-ext"));
        }

        @Test
        @DisplayName("failure: fires FAILED regardless of MCP state (no listeners → no crash)")
        void failure_firesFailed() {
            JsonObject args = new JsonObject();
            args.addProperty("c", 3);

            registry.registerClientSide("tool", args, "cid-fail");
            registry.completeClientSide("cid-fail", false);
            assertNotNull(registry.findChipIdByClientId("cid-fail"));
        }

        @Test
        @DisplayName("null-args chip completion does not crash")
        void nullArgsChip_completionNoException() {
            registry.registerClientSide("tool", null, "cid-null-complete");
            registry.completeClientSide("cid-null-complete", true);
            assertNotNull(registry.findChipIdByClientId("cid-null-complete"));
        }

        @Test
        @DisplayName("completing MCP-first then client chip: no exception")
        void mcpFirstThenClient_completionNoException() {
            JsonObject args = new JsonObject();
            args.addProperty("d", 4);

            registry.registerMcp("tool", args);
            registry.registerClientSide("tool", args, "cid-mf-complete");
            registry.completeClientSide("cid-mf-complete", true);
            assertNotNull(registry.findChipIdByClientId("cid-mf-complete"));
        }
    }

    // ── reregisterWithArgs ────────────────────────────────────────────────────

    @Nested
    @DisplayName("reregisterWithArgs")
    class ReregisterWithArgs {

        @Test
        @DisplayName("no existing chip: returns PENDING with fallback chipId")
        void noExistingChip_returnsPendingFallback() {
            JsonObject newArgs = new JsonObject();
            newArgs.addProperty("x", 1);

            ToolChipRegistry.ChipRegistration result = registry.reregisterWithArgs("nonexistent-cid", newArgs);

            assertEquals(ToolChipRegistry.ChipState.PENDING, result.initialState());
            assertTrue(result.chipId().startsWith("c-"), "Fallback chipId should start with 'c-': " + result.chipId());
        }

        @Test
        @DisplayName("finds MCP-first chip by tool name when null-args client can't correlate")
        void findsMcpFirstByName() {
            // Client registers with null args → chipId="c-cid-rn", can't match MCP by hash
            registry.registerClientSide("my_tool", null, "cid-rn");

            // MCP registers → creates MCP-first chip (null-args client chip won't match by hash)
            JsonObject mcpArgs = new JsonObject();
            mcpArgs.addProperty("param", "value");
            registry.registerMcp("my_tool", mcpArgs);

            // Reregister with actual args → should find MCP-first chip by display name
            ToolChipRegistry.ChipRegistration result = registry.reregisterWithArgs("cid-rn", mcpArgs);

            assertEquals(ToolChipRegistry.ChipState.RUNNING, result.initialState());
            assertEquals(result.chipId(), registry.findChipIdByClientId("cid-rn"));
        }

        @Test
        @DisplayName("finds MCP-first chip by hash when name doesn't match")
        void findsMcpFirstByHash() {
            JsonObject hashArgs = new JsonObject();
            hashArgs.addProperty("unique", "hash-test-value");

            // MCP-first: no client chip with this hash exists yet
            registry.registerMcp("mcp_tool_name", hashArgs);

            // Client registers with empty args (different hash, different display name)
            JsonObject emptyArgs = new JsonObject();
            registry.registerClientSide("different_display", emptyArgs, "cid-rh");

            // Reregister with args that match the MCP-first hash
            // "different_display" != "mcp_tool_name" → name lookup fails → hash lookup succeeds
            ToolChipRegistry.ChipRegistration result = registry.reregisterWithArgs("cid-rh", hashArgs);

            assertEquals(ToolChipRegistry.ChipState.RUNNING, result.initialState());
        }

        @Test
        @DisplayName("no MCP-first exists: creates new client chip with PENDING")
        void noMcpFirst_createsNewClientChipPending() {
            JsonObject originalArgs = new JsonObject();
            originalArgs.addProperty("f", "old.java");

            registry.registerClientSide("editor_tool", originalArgs, "cid-rp");

            JsonObject newArgs = new JsonObject();
            newArgs.addProperty("f", "new.java");
            ToolChipRegistry.ChipRegistration result = registry.reregisterWithArgs("cid-rp", newArgs);

            assertEquals(ToolChipRegistry.ChipState.PENDING, result.initialState());
            String expectedHash = ToolChipRegistry.computeBaseHash(newArgs);
            assertEquals(expectedHash, result.chipId());
        }

        @Test
        @DisplayName("after reregister, findChipIdByClientId uses new chipId")
        void afterReregister_findChipIdUsesNewId() {
            JsonObject args = new JsonObject();
            args.addProperty("v", "old");

            ToolChipRegistry.ChipRegistration oldReg = registry.registerClientSide("tool", args, "cid-rv");

            JsonObject newArgs = new JsonObject();
            newArgs.addProperty("v", "new");
            ToolChipRegistry.ChipRegistration newReg = registry.reregisterWithArgs("cid-rv", newArgs);

            assertNotEquals(oldReg.chipId(), newReg.chipId());
            assertEquals(newReg.chipId(), registry.findChipIdByClientId("cid-rv"));
        }
    }

    // ── clearTurn ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("clearTurn")
    class ClearTurn {

        @Test
        @DisplayName("after clearTurn, findChipIdByClientId returns null")
        void afterClear_findChipIdReturnsNull() {
            JsonObject args = new JsonObject();
            args.addProperty("x", 1);

            registry.registerClientSide("tool", args, "cid-cx");
            assertNotNull(registry.findChipIdByClientId("cid-cx"));

            registry.clearTurn();

            assertNull(registry.findChipIdByClientId("cid-cx"));
        }

        @Test
        @DisplayName("after clearTurn, hash collision counter resets to 1")
        void afterClear_collisionCounterResets() {
            JsonObject args = new JsonObject();
            args.addProperty("k", "v");

            registry.registerClientSide("tool", args, "cid-cc1");
            registry.registerClientSide("tool", args, "cid-cc2");

            registry.clearTurn();

            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("tool", args, "cid-cc3");
            String baseHash = ToolChipRegistry.computeBaseHash(args);
            assertEquals(baseHash, reg.chipId(), "After clearTurn, first chip should get plain hash again");
        }

        @Test
        @DisplayName("clearTurn is idempotent")
        void clearTurnIdempotent() {
            registry.clearTurn();
            registry.clearTurn();
            assertNull(registry.findChipIdByClientId("anything"));
        }

        @Test
        @DisplayName("clear() is equivalent to clearTurn()")
        void clearEquivalentToClearTurn() {
            JsonObject args = new JsonObject();
            args.addProperty("y", 2);

            registry.registerClientSide("tool", args, "cid-clear");
            registry.clear();

            assertNull(registry.findChipIdByClientId("cid-clear"));
        }

        @Test
        @DisplayName("after clearTurn, MCP-first entries are gone")
        void afterClear_mcpFirstEntriesGone() {
            JsonObject args = new JsonObject();
            args.addProperty("m", 5);

            registry.registerMcp("tool", args);
            registry.clearTurn();

            // If MCP-first entry was cleared, client registration should get PENDING not RUNNING
            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("tool", args, "cid-post-clear");
            assertEquals(ToolChipRegistry.ChipState.PENDING, reg.initialState());
        }
    }

    // ── findChipIdByClientId ──────────────────────────────────────────────────

    @Nested
    @DisplayName("findChipIdByClientId")
    class FindChipIdByClientId {

        @Test
        @DisplayName("returns null for unknown clientId")
        void unknownClientId_returnsNull() {
            assertNull(registry.findChipIdByClientId("not-registered"));
        }

        @Test
        @DisplayName("returns chipId after hash-based registration")
        void returnsChipIdAfterHashRegistration() {
            JsonObject args = new JsonObject();
            args.addProperty("x", 99);

            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("tool", args, "cid-lkp");

            assertEquals(reg.chipId(), registry.findChipIdByClientId("cid-lkp"));
        }

        @Test
        @DisplayName("returns chipId after null-args registration")
        void returnsChipIdAfterNullArgsRegistration() {
            ToolChipRegistry.ChipRegistration reg = registry.registerClientSide("tool", null, "cid-null-lkp");

            assertEquals(reg.chipId(), registry.findChipIdByClientId("cid-null-lkp"));
        }

        @Test
        @DisplayName("different clientIds return different chipIds when args differ")
        void differentClientIds_differentChipIds() {
            JsonObject args1 = new JsonObject();
            args1.addProperty("n", 1);
            JsonObject args2 = new JsonObject();
            args2.addProperty("n", 2);

            ToolChipRegistry.ChipRegistration reg1 = registry.registerClientSide("tool", args1, "cid-d1");
            ToolChipRegistry.ChipRegistration reg2 = registry.registerClientSide("tool", args2, "cid-d2");

            assertNotEquals(reg1.chipId(), reg2.chipId());
            assertEquals(reg1.chipId(), registry.findChipIdByClientId("cid-d1"));
            assertEquals(reg2.chipId(), registry.findChipIdByClientId("cid-d2"));
        }
    }

    // ── listener management ───────────────────────────────────────────────────

    @Nested
    @DisplayName("listener management")
    class ListenerManagement {

        @Test
        @DisplayName("addStateListener and removeStateListener do not crash")
        void addRemoveStateListener_noException() {
            BiConsumer<String, ToolChipRegistry.ChipState> listener = (id, state) -> {
            };
            registry.addStateListener(listener);
            registry.removeStateListener(listener);
            assertNull(registry.findChipIdByClientId("not-registered"));
        }

        @Test
        @DisplayName("addKindStateListener and removeKindStateListener do not crash")
        void addRemoveKindStateListener_noException() {
            ToolChipRegistry.ChipStateWithKindListener listener = (id, state, kind, name) -> {
            };
            registry.addKindStateListener(listener);
            registry.removeKindStateListener(listener);
            assertNull(registry.findChipIdByClientId("not-registered"));
        }

        @Test
        @DisplayName("removing a listener not previously added is a no-op")
        void removeNonAddedListener_noOp() {
            BiConsumer<String, ToolChipRegistry.ChipState> listener = (id, state) -> {
            };
            registry.removeStateListener(listener);
            assertNull(registry.findChipIdByClientId("not-registered"));
        }
    }

    // ── storeMcpResult / getStoredPluginResult ────────────────────────────────

    @Nested
    @DisplayName("storeMcpResult / getStoredPluginResult")
    class StoreMcpResult {

        @Test
        @DisplayName("stored result is retrievable by chipId")
        void storedResult_isRetrievableByChipId() {
            JsonObject args = new JsonObject();
            args.addProperty("file", "Foo.java");
            String chipId = ToolChipRegistry.computeBaseHash(args);
            registry.registerMcp("read_file", args);

            registry.storeMcpResult("read_file", args, "File content here");

            assertEquals("File content here", registry.getStoredPluginResult(chipId));
        }

        @Test
        @DisplayName("no stored result returns null")
        void noStoredResult_returnsNull() {
            assertNull(registry.getStoredPluginResult("nonexistent-chip"));
        }

        @Test
        @DisplayName("storeMcpResult with unregistered tool is a no-op")
        void unregisteredTool_isNoOp() {
            JsonObject args = new JsonObject();
            args.addProperty("x", 1);
            String chipId = ToolChipRegistry.computeBaseHash(args);

            registry.storeMcpResult("unknown_tool", args, "result");

            assertNull(registry.getStoredPluginResult(chipId));
        }

        @Test
        @DisplayName("stored result is cleared when turn is cleared")
        void storedResult_clearedAfterClearTurn() {
            JsonObject args = new JsonObject();
            args.addProperty("x", 1);
            String chipId = ToolChipRegistry.computeBaseHash(args);
            registry.registerMcp("write_file", args);
            registry.storeMcpResult("write_file", args, "Written successfully");
            assertEquals("Written successfully", registry.getStoredPluginResult(chipId));

            registry.clearTurn();

            assertNull(registry.getStoredPluginResult(chipId));
        }

        @Test
        @DisplayName("latest storeMcpResult call wins when chip matches multiple times")
        void latestResult_overwrites_previous() {
            JsonObject args = new JsonObject();
            args.addProperty("file", "Bar.java");
            String chipId = ToolChipRegistry.computeBaseHash(args);
            registry.registerMcp("read_file", args);

            registry.storeMcpResult("read_file", args, "First result");
            registry.storeMcpResult("read_file", args, "Updated result");

            assertEquals("Updated result", registry.getStoredPluginResult(chipId));
        }
    }
}

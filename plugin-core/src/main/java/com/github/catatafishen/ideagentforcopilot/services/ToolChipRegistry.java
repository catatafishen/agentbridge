package com.github.catatafishen.ideagentforcopilot.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Correlates tool chips across the ACP (or Claude streaming) and MCP channels.
 *
 * <h2>Correlation key</h2>
 * Both sides compute {@code hex8(args.hashCode())} over sorted key→value pairs of the tool
 * arguments. This gives a stable shared ID with no agent cooperation required.
 *
 * <h2>Chip lifecycle</h2>
 * <ol>
 *   <li>Client-side ({@link #registerClientSide}) arrives: chip created at {@link ChipState#PENDING}.</li>
 *   <li>MCP-side ({@link #registerMcp}) arrives: state listener fires {@link ChipState#RUNNING}.</li>
 *   <li>Client-side completion ({@link #completeClientSide}): fires {@link ChipState#COMPLETE},
 *       {@link ChipState#EXTERNAL} (MCP never came), or {@link ChipState#FAILED}.</li>
 * </ol>
 *
 * <h2>MCP-first case</h2>
 * If MCP arrives before ACP, the registry stores the entry internally. When client-side
 * arrives, {@link #registerClientSide} returns {@link ChipState#RUNNING} immediately, and
 * {@link #addToolCallEntry} creates the chip at "running" without needing a transition.
 *
 * <h2>Turn scope</h2>
 * All entries are turn-scoped. Call {@link #clearTurn()} when a new agent response starts.
 * Duplicate args within a turn use newest-wins: MCP matches the most recently registered
 * unmatched client-side chip with that hash.
 */
@Service(Service.Level.PROJECT)
public final class ToolChipRegistry {
    private static final Logger LOG = Logger.getInstance(ToolChipRegistry.class);

    public enum ChipState {PENDING, RUNNING, COMPLETE, EXTERNAL, FAILED}

    public record ChipRegistration(@NotNull String chipId, @NotNull ChipState initialState) {
    }

    record ChipEntry(
        @NotNull String chipId,
        @NotNull String displayName,
        @Nullable String clientId,
        boolean mcpHandled,
        long createdAt
    ) {
        ChipEntry withMcp() {
            return new ChipEntry(chipId, displayName, clientId, true, createdAt);
        }

        ChipEntry withClientId(@NotNull String id, @NotNull String name) {
            return new ChipEntry(chipId, name, id, mcpHandled, createdAt);
        }
    }

    // Insertion-ordered map of chipId → entry (newest entry with a given hash is last)
    private final LinkedHashMap<String, ChipEntry> chips = new LinkedHashMap<>();
    // clientId → chipId for completion routing
    private final Map<String, String> clientToChip = new LinkedHashMap<>();
    // base hash → count, for collision disambiguation
    private final Map<String, Integer> hashCounts = new LinkedHashMap<>();

    private final List<BiConsumer<String, ChipState>> listeners = new ArrayList<>();
    private final List<ChipStateWithKindListener> kindListeners = new ArrayList<>();

    public interface ChipStateWithKindListener {
        void stateChanged(@NotNull String chipId, @NotNull ChipState state, @Nullable String kind);
    }

    public static ToolChipRegistry getInstance(@NotNull Project project) {
        return project.getService(ToolChipRegistry.class);
    }

    // ── Client-side registration (ACP or Claude) ─────────────────────────────

    /**
     * Called when ACP or Claude reports a tool_call/tool_use event.
     * Returns a {@link ChipRegistration} with the chipId and the initial state to display.
     * The caller (ChatConsolePanel.addToolCallEntry) creates the DOM chip using this info.
     */
    public synchronized @NotNull ChipRegistration registerClientSide(
        @NotNull String displayTitle,
        @Nullable JsonObject args,
        @NotNull String clientId
    ) {
        // If args is null, we cannot compute a hash — but first try to find an MCP-first chip
        // by tool name (handles Junie, which sends tool_call with empty content:[]).
        // Fall back to a clientId-based chip that cannot correlate with MCP.
        // Empty args ({}) DO go through the hash path because the MCP side always hashes them the same way.
        if (args == null) {
            ChipEntry mcpFirstByName = findMcpFirstChipByName(displayTitle);
            if (mcpFirstByName != null) {
                ChipEntry updated = mcpFirstByName.withClientId(clientId, displayTitle);
                chips.put(mcpFirstByName.chipId(), updated);
                clientToChip.put(clientId, mcpFirstByName.chipId());
                LOG.debug("ToolChipRegistry: null-args client claimed MCP-first chip " + mcpFirstByName.chipId() + " (" + displayTitle + ")");
                return new ChipRegistration(mcpFirstByName.chipId(), ChipState.RUNNING);
            }
            String chipId = "c-" + clientId.replaceAll("[^a-zA-Z0-9]", "-");
            ChipEntry entry = new ChipEntry(chipId, displayTitle, clientId, false, System.currentTimeMillis());
            chips.put(chipId, entry);
            clientToChip.put(clientId, chipId);
            LOG.debug("ToolChipRegistry: null-args chip " + chipId + " (" + displayTitle + ")");
            return new ChipRegistration(chipId, ChipState.PENDING);
        }

        String baseHash = computeBaseHash(args);
        LOG.info("ToolChipRegistry [ACP→Client]: tool=" + displayTitle + " hash=" + baseHash + " args=" + args);

        // Look for a MCP-first chip (mcpHandled=true, clientId=null) with this hash
        ChipEntry mcpFirst = findMcpFirstChip(baseHash);
        if (mcpFirst != null) {
            ChipEntry updated = mcpFirst.withClientId(clientId, displayTitle);
            chips.put(mcpFirst.chipId(), updated);
            clientToChip.put(clientId, mcpFirst.chipId());
            LOG.debug("ToolChipRegistry: client claimed MCP-first chip " + mcpFirst.chipId() + " (" + displayTitle + ")");
            return new ChipRegistration(mcpFirst.chipId(), ChipState.RUNNING);
        }

        // New chip — assign chipId with collision counter
        int count = hashCounts.merge(baseHash, 1, Integer::sum);
        String chipId = count == 1 ? baseHash : baseHash + "-" + count;
        ChipEntry entry = new ChipEntry(chipId, displayTitle, clientId, false, System.currentTimeMillis());
        chips.put(chipId, entry);
        clientToChip.put(clientId, chipId);
        LOG.debug("ToolChipRegistry: client-first chip " + chipId + " (" + displayTitle + ")");
        return new ChipRegistration(chipId, ChipState.PENDING);
    }

    // ── MCP-side registration ─────────────────────────────────────────────────

    /**
     * Called by PsiBridgeService immediately before executing a tool.
     * Finds the newest unmatched client-side chip with this args hash and transitions it to RUNNING.
     * If no client-side chip exists yet (MCP arrived first), stores a pending entry.
     */
    public synchronized void registerMcp(@NotNull String toolName, @NotNull JsonObject args) {
        registerMcp(toolName, args, null);
    }

    /**
     * Called by PsiBridgeService immediately before executing a tool.
     * Finds the newest unmatched client-side chip with this args hash and transitions it to RUNNING.
     * If no client-side chip exists yet (MCP arrived first), stores a pending entry.
     */
    public synchronized void registerMcp(@NotNull String toolName, @NotNull JsonObject args, @Nullable String kind) {
        String baseHash = computeBaseHash(args);
        LOG.info("ToolChipRegistry [MCP→Server]: tool=" + toolName + " hash=" + baseHash + " args=" + args);

        // Find the newest unmatched client-side chip (newest = last in insertion order)
        ChipEntry target = findNewestUnmatchedClientChip(baseHash);
        if (target != null) {
            chips.put(target.chipId(), target.withMcp());
            LOG.info("ToolChipRegistry [MCP→Server]: ✓ MATCHED client chip " + target.chipId() + " for tool=" + toolName);
            fireState(target.chipId(), ChipState.RUNNING, kind);
            return;
        }

        // MCP arrived first — store for when client-side arrives
        LOG.warn("ToolChipRegistry [MCP→Server]: ✗ NO MATCH for tool=" + toolName + " hash=" + baseHash + " — MCP arrived before client or hash mismatch!");
        ChipEntry entry = new ChipEntry(baseHash, toolName, null, true, System.currentTimeMillis());
        chips.put(baseHash, entry);
        // Don't fire a state event — no DOM chip exists yet
        LOG.debug("ToolChipRegistry: MCP-first " + baseHash + " (" + toolName + ")");
    }

    // ── Completion ────────────────────────────────────────────────────────────

    /**
     * Called when ACP or Claude reports tool completion.
     * Fires COMPLETE, EXTERNAL (MCP never handled it), or FAILED.
     */
    public synchronized void completeClientSide(@NotNull String clientId, boolean success) {
        String chipId = clientToChip.get(clientId);
        if (chipId == null) {
            LOG.debug("ToolChipRegistry: no chip for clientId=" + clientId);
            return;
        }
        ChipEntry entry = chips.get(chipId);
        ChipState state;
        if (!success) {
            state = ChipState.FAILED;
        } else if (entry != null && entry.mcpHandled()) {
            state = ChipState.COMPLETE;
        } else {
            state = ChipState.EXTERNAL;
        }
        LOG.debug("ToolChipRegistry: complete " + chipId + " → " + state);
        fireState(chipId, state);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    /**
     * Register a state-change listener. Fired on EDT for RUNNING, COMPLETE, EXTERNAL, FAILED.
     */
    public synchronized void addStateListener(@NotNull BiConsumer<String, ChipState> listener) {
        listeners.add(listener);
    }

    public synchronized void addKindStateListener(@NotNull ChipStateWithKindListener listener) {
        kindListeners.add(listener);
    }

    public synchronized void removeStateListener(@NotNull BiConsumer<String, ChipState> listener) {
        listeners.remove(listener);
    }

    public synchronized void removeKindStateListener(@NotNull ChipStateWithKindListener listener) {
        kindListeners.remove(listener);
    }

    // ── Turn management ───────────────────────────────────────────────────────

    /**
     * Clear current-turn state. Call when a new agent response starts.
     */
    public synchronized void clearTurn() {
        int count = chips.size();
        chips.clear();
        clientToChip.clear();
        hashCounts.clear();
        if (count > 0) LOG.debug("ToolChipRegistry: cleared " + count + " chips");
    }

    /**
     * Full reset (session end).
     */
    public void clear() {
        clearTurn();
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    public synchronized @Nullable String findChipIdByClientId(@NotNull String clientId) {
        return clientToChip.get(clientId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Find a MCP-first chip (mcpHandled=true, clientId=null) by tool display name.
     * Used when the client-side event has no args (e.g. Junie's tool_call with empty content).
     */
    private @Nullable ChipEntry findMcpFirstChipByName(@NotNull String displayName) {
        ChipEntry result = null;
        for (var entry : chips.values()) {
            if (entry.clientId() == null && entry.mcpHandled() && displayName.equals(entry.displayName())) {
                result = entry; // keep iterating — last match wins (newest)
            }
        }
        return result;
    }

    /**
     * Find a MCP-first chip (mcpHandled=true, clientId=null) with the given base hash.
     */
    private @Nullable ChipEntry findMcpFirstChip(@NotNull String baseHash) {
        for (var entry : chips.values()) {
            if (entry.clientId() == null && entry.mcpHandled() && isMatchingHash(entry.chipId(), baseHash)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Find the newest (last in insertion order) client-side chip not yet MCP-matched.
     */
    private @Nullable ChipEntry findNewestUnmatchedClientChip(@NotNull String baseHash) {
        ChipEntry result = null;
        for (var entry : chips.values()) {
            if (!entry.mcpHandled() && entry.clientId() != null && isMatchingHash(entry.chipId(), baseHash)) {
                result = entry; // keep iterating — last match wins (newest)
            }
        }
        return result;
    }

    private static boolean isMatchingHash(@NotNull String chipId, @NotNull String baseHash) {
        return chipId.equals(baseHash) || chipId.startsWith(baseHash + "-");
    }

    private void fireState(@NotNull String chipId, @NotNull ChipState state) {
        fireState(chipId, state, null);
    }

    private void fireState(@NotNull String chipId, @NotNull ChipState state, @Nullable String kind) {
        List<BiConsumer<String, ChipState>> snapshot;
        List<ChipStateWithKindListener> kindSnapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(listeners);
            kindSnapshot = new ArrayList<>(kindListeners);
        }
        if (snapshot.isEmpty() && kindSnapshot.isEmpty()) return;
        LOG.debug("ToolChipRegistry: fireState " + chipId + " -> " + state + " (listeners=" + snapshot.size() + ", kindListeners=" + kindSnapshot.size() + ")");
        ApplicationManager.getApplication().invokeLater(() -> {
            for (var listener : snapshot) {
                try {
                    listener.accept(chipId, state);
                } catch (Exception e) {
                    LOG.warn("ToolChipRegistry listener error", e);
                }
            }
            for (var listener : kindSnapshot) {
                try {
                    listener.stateChanged(chipId, state, kind);
                } catch (Exception e) {
                    LOG.warn("ToolChipRegistry kind listener error", e);
                }
            }
        });
    }

    public static @NotNull String computeBaseHash(@NotNull JsonObject args) {
        try {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (String key : args.keySet()) {
                if (!"__tool_use_purpose".equals(key)) {
                    JsonElement value = args.get(key);
                    sorted.put(key, computeStableValue(value));
                }
            }
            String toHash = sorted.toString();
            String hash = String.format("%08x", toHash.hashCode());
            LOG.debug("ToolChipRegistry: hashing '" + toHash + "' -> " + hash);
            return hash;
        } catch (Exception e) {
            LOG.warn("ToolChipRegistry: hash error", e);
            return "00000000";
        }
    }

    private static String computeStableValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            TreeMap<String, String> sorted = new TreeMap<>();
            for (String key : obj.keySet()) {
                sorted.put(key, computeStableValue(obj.get(key)));
            }
            return sorted.toString();
        }
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            ArrayList<String> items = new ArrayList<>();
            for (JsonElement item : arr) {
                items.add(computeStableValue(item));
            }
            return items.toString();
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive p = value.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == (long) d) {
                    return String.valueOf((long) d);
                }
            }
        }
        return value.toString();
    }
}

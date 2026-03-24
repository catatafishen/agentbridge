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
 * <h2>Tool chip border</h2>
 * Chips start with a <b>dashed</b> border. As soon as the MCP server sees the tool call
 * (i.e. the agent is using AgentBridge to execute it), the border becomes <b>solid</b>.
 * Chips that stay dashed were handled by the agent's own built-in tools, not by AgentBridge.
 *
 * <h2>Correlation key</h2>
 * Both sides compute {@code hex8(args.hashCode())} over sorted key→value pairs of the tool
 * arguments. This gives a stable shared ID with no agent cooperation required.
 * When the agent sends no args (e.g. Junie's {@code tool_call} with empty {@code content:[]}),
 * the registry falls back to matching by tool display name.
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
 * {@code addToolCallEntry} creates the chip at "running" without needing a transition.
 *
 * <h2>Turn scope</h2>
 * All entries are turn-scoped. Call {@link #clearTurn()} when a new agent response starts.
 * Duplicate args within a turn use newest-wins: MCP matches the most recently registered
 * unmatched client-side chip with that hash.
 */
@Service(Service.Level.PROJECT)
public final class ToolChipRegistry {
    private static final Logger LOG = Logger.getInstance(ToolChipRegistry.class);

    /**
     * Lifecycle states for a tool chip. Border is dashed until MCP handles the call, then solid.
     */
    public enum ChipState {
        PENDING, RUNNING, COMPLETE, EXTERNAL, FAILED
    }

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

        // If args is empty {}, we cannot reliably correlate with MCP since MCP has the real arguments.
        // Fall back to finding an MCP-first chip by tool name (same logic as null args).
        if (args.isEmpty()) {
            ChipEntry mcpFirstByName = findMcpFirstChipByName(displayTitle);
            if (mcpFirstByName != null) {
                ChipEntry updated = mcpFirstByName.withClientId(clientId, displayTitle);
                chips.put(mcpFirstByName.chipId(), updated);
                clientToChip.put(clientId, mcpFirstByName.chipId());
                LOG.debug("ToolChipRegistry: empty-args client claimed MCP-first chip " + mcpFirstByName.chipId() + " (" + displayTitle + ")");
                return new ChipRegistration(mcpFirstByName.chipId(), ChipState.RUNNING);
            }
            // No MCP-first chip found - create client-only chip with clientId-based ID
            String chipId = "c-" + clientId.replaceAll("[^a-zA-Z0-9]", "-");
            ChipEntry entry = new ChipEntry(chipId, displayTitle, clientId, false, System.currentTimeMillis());
            chips.put(chipId, entry);
            clientToChip.put(clientId, chipId);
            LOG.debug("ToolChipRegistry: empty-args chip " + chipId + " (" + displayTitle + ")");
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
        LOG.info("ToolChipRegistry [MCP→Server]: MCP-first chip for tool=" + toolName + " hash=" + baseHash);
        ChipEntry entry = new ChipEntry(baseHash, toolName, null, true, System.currentTimeMillis());
        chips.put(baseHash, entry);
        // Fire RUNNING immediately so UI creates solid-border chip (MCP tools always get solid border)
        fireState(baseHash, ChipState.RUNNING, kind);
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

    /**
     * Re-register a chip with new arguments. Used when ACP sends tool_call with empty args first,
     * then tool_call_update with actual args. This removes the old chip and creates a new one
     * with the updated args, allowing proper correlation with MCP.
     *
     * @param clientId the ACP tool call ID
     * @param newArgs  the new arguments (must not be null or empty)
     * @return the new chip registration with updated chipId and state
     */
    public synchronized @NotNull ChipRegistration reregisterWithArgs(
        @NotNull String clientId,
        @NotNull JsonObject newArgs
    ) {
        // Find and remove the old chip, but save the tool name first
        String oldChipId = clientToChip.get(clientId);
        String toolName = null;
        if (oldChipId != null) {
            ChipEntry oldEntry = chips.get(oldChipId);
            if (oldEntry != null) {
                toolName = oldEntry.displayName();
            }
            chips.remove(oldChipId);
            clientToChip.remove(clientId);
            LOG.debug("ToolChipRegistry: removed old chip " + oldChipId + " for re-registration");
        }

        // If we don't have a tool name, we can't do name-based fallback - just return failure
        if (toolName == null) {
            LOG.debug("ToolChipRegistry: no tool name found for re-registration of clientId=" + clientId);
            return new ChipRegistration("c-" + clientId.replaceAll("[^a-zA-Z0-9]", "-"), ChipState.PENDING);
        }

        // Try to find MCP-first chip with new args (by tool name as fallback)
        ChipEntry mcpFirstByName = findMcpFirstChipByName(toolName);
        if (mcpFirstByName != null) {
            ChipEntry updated = mcpFirstByName.withClientId(clientId, toolName);
            chips.put(mcpFirstByName.chipId(), updated);
            clientToChip.put(clientId, mcpFirstByName.chipId());
            LOG.debug("ToolChipRegistry: re-registered claimed MCP-first chip by name " + mcpFirstByName.chipId() + " for " + toolName);
            return new ChipRegistration(mcpFirstByName.chipId(), ChipState.RUNNING);
        }

        // Try to find MCP-first chip with new args hash
        String newHash = computeBaseHash(newArgs);
        ChipEntry mcpFirst = findMcpFirstChip(newHash);
        if (mcpFirst != null) {
            ChipEntry updated = mcpFirst.withClientId(clientId, toolName);
            chips.put(mcpFirst.chipId(), updated);
            clientToChip.put(clientId, mcpFirst.chipId());
            LOG.debug("ToolChipRegistry: re-registered claimed MCP-first chip by hash " + mcpFirst.chipId() + " for " + toolName);
            return new ChipRegistration(mcpFirst.chipId(), ChipState.RUNNING);
        }

        // No MCP-first chip - create new client chip with new args
        int count = hashCounts.merge(newHash, 1, Integer::sum);
        String newChipId = count == 1 ? newHash : newHash + "-" + count;
        ChipEntry entry = new ChipEntry(newChipId, toolName, clientId, false, System.currentTimeMillis());
        chips.put(newChipId, entry);
        clientToChip.put(clientId, newChipId);
        LOG.debug("ToolChipRegistry: re-registered with new args " + newChipId + " for " + toolName);
        return new ChipRegistration(newChipId, ChipState.PENDING);
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

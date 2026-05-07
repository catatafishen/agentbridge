package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.google.gson.JsonObject;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for all live tool calls in the project.
 *
 * <p>Accepts events from two independent channels (ACP and MCP) in any order,
 * correlates them into unified {@link ToolCallRecord} instances, and fires typed
 * events to listeners. Consumers (UI, persistence, MCP tab) react to events
 * rather than maintaining their own parallel tracking maps.
 *
 * <p><b>Not turn-scoped.</b> Records stay live until explicitly completed or flushed.
 * MCP calls from non-ACP clients remain live indefinitely until ACP ordering proves
 * they will never be correlated (see flush logic in {@link #onAcpCompleted}).
 */
@Service(Service.Level.PROJECT)
public final class ToolCallTracker {

    private static final Logger LOG = Logger.getInstance(ToolCallTracker.class);
    private final Project project;

    /**
     * Listeners receive typed events about tool call lifecycle changes.
     * All callbacks fire on the EDT.
     */
    public interface Listener {
        /**
         * ACP reported a new tool call — chip can be created in DOM.
         */
        default void onAcpRegistered(@NotNull ToolCallRecord record) {
        }

        /**
         * MCP execution started for an uncorrelated call (no ACP counterpart yet).
         */
        default void onMcpRegistered(@NotNull ToolCallRecord record) {
        }

        /**
         * ACP and MCP matched — the record now has both sides.
         */
        default void onCorrelated(@NotNull ToolCallRecord record) {
        }

        /**
         * MCP execution finished, result stored in record.
         */
        default void onMcpCompleted(@NotNull ToolCallRecord record) {
        }

        /**
         * ACP reported COMPLETED or FAILED — terminal state.
         */
        default void onAcpCompleted(@NotNull ToolCallRecord record) {
        }

        /**
         * Record removed from live set (for cleanup).
         */
        default void onFlushed(@NotNull ToolCallRecord record) {
        }
    }

    // ── Live set ─────────────────────────────────────────────────────────────

    /**
     * All live records, keyed by their stable record ID (args hash or UUID).
     * Access must be synchronized on {@code this}.
     */
    private final LinkedHashMap<String, ToolCallRecord> liveRecords = new LinkedHashMap<>();

    /**
     * ACP client ID → record ID mapping for fast ACP-side lookups.
     */
    private final Map<String, String> acpIdToRecordId = new LinkedHashMap<>();

    /**
     * Claude CLI toolUseId → record ID mapping for Priority 0 correlation.
     */
    private final Map<String, String> toolUseIdToRecordId = new LinkedHashMap<>();

    /**
     * Monotone sequence counter per session for ACP ordering.
     * Incremented on every ACP registration.
     */
    private int acpSequence = 0;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public ToolCallTracker(@NotNull Project project) {
        this.project = project;
    }

    // ── Static accessor ──────────────────────────────────────────────────────

    public static ToolCallTracker getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ToolCallTracker.class);
    }

    /**
     * Public delegation to {@link ToolCallHasher#computeBaseHash(JsonObject)} for callers
     * outside the {@code services} package (e.g. PsiBridgeService).
     */
    public static @NotNull String computeHash(@NotNull JsonObject args) {
        return ToolCallHasher.computeBaseHash(args);
    }

    // ── Listener management ──────────────────────────────────────────────────

    public void addListener(@NotNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Listener listener) {
        listeners.remove(listener);
    }

    // ── ACP channel: tool call reported by the agent protocol ─────────────────

    /**
     * Called when ACP reports a new tool call ({@code tool_call} notification).
     *
     * @param acpClientId the ACP-assigned tool call ID
     * @param acpTitle    the display title reported by the protocol (may be imprecise)
     * @param args        tool arguments (may be null for some agents like Junie)
     * @param kind        tool category (e.g. "file", "git") or null
     * @param routingType the call's role: REGULAR, SUB_AGENT, SUB_AGENT_INTERNAL, TASK_COMPLETE
     * @param toolUseId   Claude CLI's toolUseId from _meta (for Priority 0 correlation), or null
     * @return the record (may be newly created or previously registered by MCP)
     */
    public synchronized @NotNull ToolCallRecord acpRegister(
        @NotNull String acpClientId,
        @NotNull String acpTitle,
        @Nullable JsonObject args,
        @Nullable String kind,
        @NotNull ToolCallRecord.RoutingType routingType,
        @Nullable String toolUseId
    ) {
        int seq = ++acpSequence;

        // Priority 0: toolUseId direct match (Claude CLI)
        if (toolUseId != null) {
            String existingRecordId = toolUseIdToRecordId.get(toolUseId);
            if (existingRecordId != null) {
                ToolCallRecord record = liveRecords.get(existingRecordId);
                if (record != null && record.getAcpClientId() == null) {
                    record.setAcpFields(acpClientId, acpTitle, args, routingType, seq);
                    acpIdToRecordId.put(acpClientId, existingRecordId);
                    LOG.info("ToolCallTracker [ACP]: correlated via toolUseId=" + toolUseId + " → " + existingRecordId);
                    fireOnCorrelated(record);
                    flushOlderUncorrelatedMcpRecords(seq);
                    return record;
                }
            }
        }

        // Priority 1: args hash match against unmatched MCP records
        if (args != null) {
            String argsHash = ToolCallHasher.computeBaseHash(args);
            ToolCallRecord mcpFirst = findNewestUnmatchedMcpRecord(argsHash);
            if (mcpFirst != null) {
                mcpFirst.setAcpFields(acpClientId, acpTitle, args, routingType, seq);
                acpIdToRecordId.put(acpClientId, mcpFirst.getRecordId());
                if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, mcpFirst.getRecordId());
                LOG.info("ToolCallTracker [ACP]: correlated via hash=" + argsHash + " → " + mcpFirst.getRecordId());
                fireOnCorrelated(mcpFirst);
                flushOlderUncorrelatedMcpRecords(seq);
                return mcpFirst;
            }
        }

        // No existing MCP record — create new ACP-first record
        String argsHash = args != null ? ToolCallHasher.computeBaseHash(args) : null;
        String recordId = allocateRecordId(argsHash);
        ToolCallRecord record = new ToolCallRecord(recordId, argsHash);
        record.setAcpFields(acpClientId, acpTitle, args, routingType, seq);
        if (kind != null) record.setKind(kind);

        liveRecords.put(recordId, record);
        acpIdToRecordId.put(acpClientId, recordId);
        if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, recordId);

        LOG.info("ToolCallTracker [ACP]: new record " + recordId + " title=" + acpTitle + " routing=" + routingType);
        fireOnAcpRegistered(record);
        return record;
    }

    // ── MCP channel: tool execution by our MCP server ────────────────────────

    /**
     * Called by PsiBridgeService immediately before executing a tool.
     *
     * @param toolName  the canonical MCP tool name
     * @param args      tool arguments
     * @param kind      tool category
     * @param toolUseId Claude CLI's toolUseId from _meta, or null
     */
    public synchronized @NotNull ToolCallRecord mcpRegister(
        @NotNull String toolName,
        @NotNull JsonObject args,
        @Nullable String kind,
        @Nullable String toolUseId
    ) {
        long startTime = System.currentTimeMillis();

        // Priority 0: toolUseId direct match
        if (toolUseId != null) {
            String existingRecordId = toolUseIdToRecordId.get(toolUseId);
            if (existingRecordId == null) {
                // ACP may have registered with same toolUseId under a different key scheme;
                // check if there's a record that has this ACP client ID matching
                // (In practice Claude sends toolUseId in _meta that matches the ACP tool_use.id)
                for (ToolCallRecord r : liveRecords.values()) {
                    if (toolUseId.equals(r.getAcpClientId()) && r.getMcpToolName() == null) {
                        r.setMcpFields(toolName, args, kind, startTime);
                        toolUseIdToRecordId.put(toolUseId, r.getRecordId());
                        LOG.info("ToolCallTracker [MCP]: correlated via acpClientId=" + toolUseId + " → " + r.getRecordId());
                        fireOnCorrelated(r);
                        bridgeRegisterMcp(r);
                        return r;
                    }
                }
            } else {
                ToolCallRecord record = liveRecords.get(existingRecordId);
                if (record != null && record.getMcpToolName() == null) {
                    record.setMcpFields(toolName, args, kind, startTime);
                    LOG.info("ToolCallTracker [MCP]: correlated via toolUseId=" + toolUseId + " → " + existingRecordId);
                    fireOnCorrelated(record);
                    bridgeRegisterMcp(record);
                    return record;
                }
            }
        }

        // Priority 1: args hash match against unmatched ACP records
        String argsHash = ToolCallHasher.computeBaseHash(args);
        ToolCallRecord acpFirst = findNewestUnmatchedAcpRecord(argsHash);
        if (acpFirst != null) {
            acpFirst.setMcpFields(toolName, args, kind, startTime);
            if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, acpFirst.getRecordId());
            LOG.info("ToolCallTracker [MCP]: correlated via hash=" + argsHash + " → " + acpFirst.getRecordId());
            fireOnCorrelated(acpFirst);
            bridgeRegisterMcp(acpFirst);
            return acpFirst;
        }

        // No ACP record — create MCP-first record
        String recordId = allocateRecordId(argsHash);
        ToolCallRecord record = new ToolCallRecord(recordId, argsHash);
        record.setMcpFields(toolName, args, kind, startTime);

        liveRecords.put(recordId, record);
        if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, recordId);

        LOG.info("ToolCallTracker [MCP]: new record " + recordId + " tool=" + toolName);
        fireOnMcpRegistered(record);
        bridgeRegisterMcp(record);
        return record;
    }

    // ── Completion ───────────────────────────────────────────────────────────

    /**
     * Called when MCP tool execution finishes. Stores result and timing data.
     */
    public synchronized void mcpComplete(@NotNull String recordId, @NotNull String result, boolean success) {
        ToolCallRecord record = liveRecords.get(recordId);
        if (record == null) {
            LOG.warn("ToolCallTracker: mcpComplete for unknown record " + recordId);
            return;
        }
        record.setMcpResult(result, success);
        LOG.debug("ToolCallTracker: mcpComplete " + recordId + " success=" + success + " resultLen=" + result.length());
        fireOnMcpCompleted(record);
        bridgeStoreMcpResult(record);
    }

    /**
     * Called when ACP reports COMPLETED or FAILED for a tool call.
     * This is the terminal event — the record is flushed from the live set.
     */
    public synchronized void acpComplete(@NotNull String acpClientId, boolean success) {
        String recordId = acpIdToRecordId.get(acpClientId);
        if (recordId == null) {
            LOG.debug("ToolCallTracker: acpComplete for unknown acpClientId=" + acpClientId);
            return;
        }
        ToolCallRecord record = liveRecords.get(recordId);
        if (record == null) return;

        record.setState(success ? ToolCallRecord.State.COMPLETED : ToolCallRecord.State.FAILED);
        LOG.debug("ToolCallTracker: acpComplete " + recordId + " success=" + success);
        fireOnAcpCompleted(record);
        flush(record);
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * Find a live record by its ACP client ID.
     */
    public synchronized @Nullable ToolCallRecord findByAcpId(@NotNull String acpClientId) {
        String recordId = acpIdToRecordId.get(acpClientId);
        return recordId != null ? liveRecords.get(recordId) : null;
    }

    /**
     * Find a live record by its record ID.
     */
    public synchronized @Nullable ToolCallRecord findByRecordId(@NotNull String recordId) {
        return liveRecords.get(recordId);
    }

    /**
     * Find a live record by MCP tool name and args (for MCP tab reverse lookup).
     */
    public synchronized @Nullable ToolCallRecord findByMcpCall(@NotNull String toolName, @NotNull JsonObject args) {
        String hash = ToolCallHasher.computeBaseHash(args);
        // Search newest first (reverse order)
        List<ToolCallRecord> values = new ArrayList<>(liveRecords.values());
        for (int i = values.size() - 1; i >= 0; i--) {
            ToolCallRecord r = values.get(i);
            if (toolName.equals(r.getMcpToolName()) && hash.equals(r.getArgsHash())) {
                return r;
            }
        }
        return null;
    }

    /**
     * Get the stored MCP result for a record (for UI that needs the raw tool output).
     */
    public synchronized @Nullable String getStoredResult(@NotNull String recordId) {
        ToolCallRecord record = liveRecords.get(recordId);
        return record != null ? record.getMcpResult() : null;
    }

    /**
     * Get the number of live records (for diagnostics).
     */
    public synchronized int liveCount() {
        return liveRecords.size();
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    /**
     * Bridge: delegates chip state transitions to the existing ToolChipRegistry during migration.
     * This ensures the DOM chip state listener continues to fire. Will be removed once
     * ChatConsolePanel switches to ToolCallTracker.Listener directly.
     */
    private void bridgeRegisterMcp(@NotNull ToolCallRecord record) {
        ToolChipRegistry chipRegistry = ToolChipRegistry.getInstance(project);
        chipRegistry.registerMcp(record.getMcpToolName(), record.getMcpArgs(),
            record.getKind() != null ? record.getKind() : "other",
            record.getAcpClientId());
    }

    /**
     * Bridge: delegates result storage to ToolChipRegistry during migration.
     */
    private void bridgeStoreMcpResult(@NotNull ToolCallRecord record) {
        ToolChipRegistry chipRegistry = ToolChipRegistry.getInstance(project);
        chipRegistry.storeMcpResult(record.getMcpToolName(), record.getMcpArgs(), record.getMcpResult());
    }

    /**
     * Full reset (session end). Flushes all records.
     */
    public synchronized void clear() {
        List<ToolCallRecord> all = new ArrayList<>(liveRecords.values());
        liveRecords.clear();
        acpIdToRecordId.clear();
        toolUseIdToRecordId.clear();
        acpSequence = 0;
        for (ToolCallRecord r : all) {
            fireOnFlushed(r);
        }
        if (!all.isEmpty()) LOG.debug("ToolCallTracker: cleared " + all.size() + " records");
    }

    // ── Flush logic ──────────────────────────────────────────────────────────

    /**
     * Flush MCP-only records (no ACP counterpart) that are older than the latest
     * correlated ACP call. Since ACP reports calls in order, if call N is already
     * correlated and an MCP-only call has no ACP match, it will never get one.
     *
     * <p>Called automatically when a new ACP registration correlates with an existing MCP record.
     */
    private void flushOlderUncorrelatedMcpRecords(int currentAcpSequence) {
        List<ToolCallRecord> toFlush = new ArrayList<>();
        for (ToolCallRecord r : liveRecords.values()) {
            // MCP-only: has MCP data but no ACP client ID
            if (r.getMcpToolName() != null && r.getAcpClientId() == null) {
                // This record was registered before the current ACP call but never matched.
                // Since ACP is ordered, it will never be correlated.
                toFlush.add(r);
            }
        }
        for (ToolCallRecord r : toFlush) {
            r.setState(ToolCallRecord.State.EXTERNAL);
            flush(r);
            LOG.debug("ToolCallTracker: flushed uncorrelated MCP record " + r.getRecordId() + " (tool=" + r.getMcpToolName() + ")");
        }
    }

    private void flush(@NotNull ToolCallRecord record) {
        liveRecords.remove(record.getRecordId());
        if (record.getAcpClientId() != null) {
            acpIdToRecordId.remove(record.getAcpClientId());
        }
        // Don't remove from toolUseIdToRecordId — it's harmless and avoids iteration
        fireOnFlushed(record);
    }

    // ── Internal: matching ───────────────────────────────────────────────────

    private @Nullable ToolCallRecord findNewestUnmatchedMcpRecord(@NotNull String argsHash) {
        ToolCallRecord result = null;
        for (ToolCallRecord r : liveRecords.values()) {
            // MCP-registered but not yet ACP-claimed
            if (r.getMcpToolName() != null && r.getAcpClientId() == null
                && argsHash.equals(r.getArgsHash())) {
                result = r; // keep going — last match = newest
            }
        }
        return result;
    }

    private @Nullable ToolCallRecord findNewestUnmatchedAcpRecord(@NotNull String argsHash) {
        ToolCallRecord result = null;
        for (ToolCallRecord r : liveRecords.values()) {
            // ACP-registered but not yet MCP-matched
            if (r.getAcpClientId() != null && r.getMcpToolName() == null
                && argsHash.equals(r.getArgsHash())) {
                result = r; // keep going — last match = newest
            }
        }
        return result;
    }

    private @NotNull String allocateRecordId(@Nullable String argsHash) {
        if (argsHash == null) {
            return "tc-" + UUID.randomUUID().toString().substring(0, 8);
        }
        // Use hash as base, disambiguate collisions
        if (!liveRecords.containsKey(argsHash)) return argsHash;
        for (int i = 2; ; i++) {
            String candidate = argsHash + "-" + i;
            if (!liveRecords.containsKey(candidate)) return candidate;
        }
    }

    // ── Event firing (on EDT) ────────────────────────────────────────────────

    private void fireOnAcpRegistered(@NotNull ToolCallRecord record) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onAcpRegistered(record);
                } catch (Exception e) {
                    LOG.warn("Listener error", e);
                }
            }
        });
    }

    private void fireOnMcpRegistered(@NotNull ToolCallRecord record) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onMcpRegistered(record);
                } catch (Exception e) {
                    LOG.warn("Listener error", e);
                }
            }
        });
    }

    private void fireOnCorrelated(@NotNull ToolCallRecord record) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onCorrelated(record);
                } catch (Exception e) {
                    LOG.warn("Listener error", e);
                }
            }
        });
    }

    private void fireOnMcpCompleted(@NotNull ToolCallRecord record) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onMcpCompleted(record);
                } catch (Exception e) {
                    LOG.warn("Listener error", e);
                }
            }
        });
    }

    private void fireOnAcpCompleted(@NotNull ToolCallRecord record) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onAcpCompleted(record);
                } catch (Exception e) {
                    LOG.warn("Listener error", e);
                }
            }
        });
    }

    private void fireOnFlushed(@NotNull ToolCallRecord record) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onFlushed(record);
                } catch (Exception e) {
                    LOG.warn("Listener error", e);
                }
            }
        });
    }
}

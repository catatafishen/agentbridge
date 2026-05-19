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
 * they will never be correlated (see flush logic in {@link #acpComplete}).
 */
@Service(Service.Level.PROJECT)
public final class ToolCallTracker {

    private static final Logger LOG = Logger.getInstance(ToolCallTracker.class);
    private static final String LISTENER_ERROR_MSG = "Listener error";
    private final Project project;

    /**
     * Listeners receive typed events about tool call lifecycle changes.
     * All callbacks fire on the EDT.
     */
    public interface Listener {
        /**
         * ACP reported a new tool call — chip can be created in DOM.
         */
        default void onAcpRegistered(@NotNull ToolCallRecord callRecord) {
        }

        /**
         * MCP execution started for an uncorrelated call (no ACP counterpart yet).
         */
        default void onMcpRegistered(@NotNull ToolCallRecord callRecord) {
        }

        /**
         * ACP and MCP matched — the record now has both sides.
         */
        default void onCorrelated(@NotNull ToolCallRecord callRecord) {
        }

        /**
         * MCP execution finished, result stored in record.
         */
        default void onMcpCompleted(@NotNull ToolCallRecord callRecord) {
        }

        /**
         * ACP reported COMPLETED or FAILED — terminal state.
         */
        default void onAcpCompleted(@NotNull ToolCallRecord callRecord) {
        }

        /**
         * Record removed from live set (for cleanup).
         */
        default void onFlushed(@NotNull ToolCallRecord callRecord) {
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

    public synchronized @NotNull ToolCallRecord acpRegister(
        @NotNull String acpClientId,
        @Nullable String acpName,
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
                ToolCallRecord callRecord = liveRecords.get(existingRecordId);
                if (callRecord != null && callRecord.getAcpClientId() == null) {
                    callRecord.setAcpFields(acpClientId, acpName, acpTitle, args, routingType, seq);
                    acpIdToRecordId.put(acpClientId, existingRecordId);
                    LOG.info("ToolCallTracker [ACP]: correlated via toolUseId=" + toolUseId + " → " + existingRecordId);
                    fireOnCorrelated(callRecord);
                    flushOlderUncorrelatedMcpRecords(existingRecordId);
                }
            }
        }

        // Priority 1: args hash match against unmatched MCP records
        String argsHash = args != null ? ToolCallHasher.computeBaseHash(args) : null;
        if (argsHash != null) {
            ToolCallRecord matched = tryAcpCorrelateByArgsHash(
                argsHash, acpClientId, acpName, acpTitle, args, routingType, seq, toolUseId);
            if (matched != null) return matched;
        }

        // No existing MCP record — create new ACP-first record
        String recordId = allocateRecordId();
        ToolCallRecord callRecord = new ToolCallRecord(recordId, argsHash);
        callRecord.setAcpFields(acpClientId, acpName, acpTitle, args, routingType, seq);
        if (kind != null) callRecord.setKind(kind);

        liveRecords.put(recordId, callRecord);
        acpIdToRecordId.put(acpClientId, recordId);
        if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, recordId);

        LOG.info("ToolCallTracker [ACP]: new record " + recordId + " name=" + acpName + " title=" + acpTitle + " routing=" + routingType);
        fireOnAcpRegistered(callRecord);
        return callRecord;
    }

    // ── MCP channel: tool execution by our MCP server ────────────────────────

    public synchronized @NotNull ToolCallRecord mcpRegister(
        @NotNull String toolName,
        @NotNull JsonObject args,
        @Nullable String kind,
        @Nullable String toolUseId
    ) {
        long startTime = System.currentTimeMillis();

        // Priority 0: toolUseId direct match
        if (toolUseId != null) {
            ToolCallRecord correlated = tryMcpCorrelateByToolUseId(toolName, args, kind, startTime, toolUseId);
            if (correlated != null) return correlated;
        }

        // Priority 1: args hash match against unmatched ACP records
        String argsHash = ToolCallHasher.computeBaseHash(args);
        ToolCallRecord acpFirst = findNewestUnmatchedAcpRecord(argsHash);
        if (acpFirst != null) {
            acpFirst.setMcpFields(toolName, args, kind, startTime);
            if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, acpFirst.getRecordId());
            LOG.info("ToolCallTracker [MCP]: correlated via hash=" + argsHash + " → " + acpFirst.getRecordId());
            fireOnCorrelated(acpFirst);
            return acpFirst;
        }

        // No ACP record — create MCP-first record
        String recordId = allocateRecordId();
        ToolCallRecord callRecord = new ToolCallRecord(recordId, argsHash);
        callRecord.setMcpFields(toolName, args, kind, startTime);

        liveRecords.put(recordId, callRecord);
        if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, recordId);

        LOG.info("ToolCallTracker [MCP]: new record " + recordId + " tool=" + toolName);
        fireOnMcpRegistered(callRecord);
        return callRecord;
    }

    @Nullable
    private ToolCallRecord tryAcpCorrelateByArgsHash(
        @NotNull String argsHash,
        @NotNull String acpClientId, @Nullable String acpName, @NotNull String acpTitle,
        @NotNull JsonObject args, @NotNull ToolCallRecord.RoutingType routingType, int seq,
        @Nullable String toolUseId
    ) {
        ToolCallRecord mcpFirst = findNewestUnmatchedMcpRecord(argsHash);
        if (mcpFirst == null) return null;
        mcpFirst.setAcpFields(acpClientId, acpName, acpTitle, args, routingType, seq);
        acpIdToRecordId.put(acpClientId, mcpFirst.getRecordId());
        if (toolUseId != null) toolUseIdToRecordId.put(toolUseId, mcpFirst.getRecordId());
        LOG.info("ToolCallTracker [ACP]: correlated via hash=" + argsHash + " → " + mcpFirst.getRecordId());
        fireOnCorrelated(mcpFirst);
        flushOlderUncorrelatedMcpRecords(mcpFirst.getRecordId());
        return mcpFirst;
    }

    @Nullable
    private ToolCallRecord tryMcpCorrelateByToolUseId(
        @NotNull String toolName, @NotNull JsonObject args, @Nullable String kind,
        long startTime, @NotNull String toolUseId
    ) {
        String existingRecordId = toolUseIdToRecordId.get(toolUseId);
        if (existingRecordId == null) {
            // ACP may have registered with same toolUseId under a different key scheme —
            // check if there's a record that has this ACP client ID matching.
            // (In practice Claude sends toolUseId in _meta that matches the ACP tool_use.id)
            for (ToolCallRecord r : liveRecords.values()) {
                if (toolUseId.equals(r.getAcpClientId()) && r.getMcpToolName() == null) {
                    r.setMcpFields(toolName, args, kind, startTime);
                    toolUseIdToRecordId.put(toolUseId, r.getRecordId());
                    LOG.info("ToolCallTracker [MCP]: correlated via acpClientId=" + toolUseId + " → " + r.getRecordId());
                    fireOnCorrelated(r);
                    return r;
                }
            }
        } else {
            ToolCallRecord callRecord = liveRecords.get(existingRecordId);
            if (callRecord != null && callRecord.getMcpToolName() == null) {
                callRecord.setMcpFields(toolName, args, kind, startTime);
                LOG.info("ToolCallTracker [MCP]: correlated via toolUseId=" + toolUseId + " → " + existingRecordId);
                fireOnCorrelated(callRecord);
                return callRecord;
            }
        }
        return null;
    }

    // ── Completion ───────────────────────────────────────────────────────────

    public synchronized void mcpComplete(@NotNull String recordId, @NotNull String result, boolean success) {
        ToolCallRecord callRecord = liveRecords.get(recordId);
        if (callRecord == null) {
            LOG.warn("ToolCallTracker: mcpComplete for unknown record " + recordId);
            return;
        }
        callRecord.setMcpResult(result, success);
        callRecord.setState(success ? ToolCallRecord.State.COMPLETED : ToolCallRecord.State.FAILED);
        LOG.debug("ToolCallTracker: mcpComplete " + recordId + " success=" + success + " resultLen=" + result.length());
        fireOnMcpCompleted(callRecord);
    }

    public synchronized void acpComplete(@NotNull String acpClientId, boolean success) {
        String recordId = acpIdToRecordId.get(acpClientId);
        if (recordId == null) {
            LOG.debug("ToolCallTracker: acpComplete for unknown acpClientId=" + acpClientId);
            return;
        }
        ToolCallRecord callRecord = liveRecords.get(recordId);
        if (callRecord == null) return;

        callRecord.setState(success ? ToolCallRecord.State.COMPLETED : ToolCallRecord.State.FAILED);
        LOG.debug("ToolCallTracker: acpComplete " + recordId + " success=" + success);
        fireOnAcpCompleted(callRecord);
        flush(callRecord);
    }

    public synchronized void acpProvideArgs(@NotNull String acpClientId, @NotNull JsonObject args) {
        String recordId = acpIdToRecordId.get(acpClientId);
        if (recordId == null) return;
        ToolCallRecord callRecord = liveRecords.get(recordId);
        if (callRecord == null) return;

        String newHash = ToolCallHasher.computeBaseHash(args);
        callRecord.updateArgsHash(newHash);
        callRecord.setAcpArgs(args);

        // If not yet correlated, try to find an MCP-first record by the new hash
        if (callRecord.getMcpToolName() == null) {
            ToolCallRecord mcpFirst = findNewestUnmatchedMcpRecord(newHash);
            if (mcpFirst != null) {
                callRecord.setMcpFields(mcpFirst.getMcpToolName(), mcpFirst.getMcpArgs(),
                    mcpFirst.getKind(), mcpFirst.getMcpStartedAt());
                if (mcpFirst.getMcpResult() != null) {
                    callRecord.setMcpResult(mcpFirst.getMcpResult(), mcpFirst.isMcpSuccess());
                }
                liveRecords.remove(mcpFirst.getRecordId());
                LOG.info("ToolCallTracker: acpProvideArgs correlated " + recordId + " via late hash=" + newHash);
                fireOnCorrelated(callRecord);
                flushOlderUncorrelatedMcpRecords(recordId);
            }
        }
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
     * Find a live record by the MCP tool-use ID that was passed in the protocol message.
     * Returns {@code null} if the toolUseId is null or not yet registered.
     */
    public synchronized @Nullable ToolCallRecord findByToolUseId(@Nullable String toolUseId) {
        if (toolUseId == null) return null;
        String recordId = toolUseIdToRecordId.get(toolUseId);
        return recordId != null ? liveRecords.get(recordId) : null;
    }

    public synchronized @Nullable String getStoredResult(@NotNull String recordId) {
        ToolCallRecord callRecord = liveRecords.get(recordId);
        return callRecord != null ? callRecord.getMcpResult() : null;
    }

    /**
     * Get the number of live records (for diagnostics).
     */
    public synchronized int liveCount() {
        return liveRecords.size();
    }

    // ── Reset ────────────────────────────────────────────────────────────────

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
     * Flush MCP-only records (no ACP counterpart) that were inserted into the live set
     * before the record that just correlated. Since ACP reports calls in order and the
     * LinkedHashMap preserves insertion order, any uncorrelated MCP record inserted before
     * a record that successfully correlated at ACP sequence {@code currentAcpSequence}
     * will never receive an ACP match.
     *
     * <p>Called automatically when a new ACP registration correlates with an existing MCP record.
     *
     * @param correlatedRecordId the record ID that was just correlated — only records
     *                           inserted before this one are eligible for flushing
     */
    private void flushOlderUncorrelatedMcpRecords(@NotNull String correlatedRecordId) {
        List<ToolCallRecord> toFlush = new ArrayList<>();
        for (ToolCallRecord r : liveRecords.values()) {
            // Stop when we reach the just-correlated record — everything after is newer.
            if (r.getRecordId().equals(correlatedRecordId)) break;

            // MCP-only: has MCP data but no ACP client ID, and MCP execution is done.
            // Records still executing might still receive an ACP match from a later stream event.
            if (r.getMcpToolName() != null && r.getAcpClientId() == null && r.getMcpResult() != null) {
                toFlush.add(r);
            }
        }
        for (ToolCallRecord r : toFlush) {
            r.setState(ToolCallRecord.State.EXTERNAL);
            flush(r);
            LOG.debug("ToolCallTracker: flushed uncorrelated MCP record " + r.getRecordId() + " (tool=" + r.getMcpToolName() + ")");
        }
    }

    private void flush(@NotNull ToolCallRecord callRecord) {
        liveRecords.remove(callRecord.getRecordId());
        if (callRecord.getAcpClientId() != null) {
            acpIdToRecordId.remove(callRecord.getAcpClientId());
        }
        // Don't remove from toolUseIdToRecordId — it's harmless and avoids iteration
        fireOnFlushed(callRecord);
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

    private @NotNull String allocateRecordId() {
        return "tc-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Event firing (on EDT) ────────────────────────────────────────────────

    private void fireOnAcpRegistered(@NotNull ToolCallRecord callRecord) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onAcpRegistered(callRecord);
                } catch (Exception e) {
                    LOG.warn(LISTENER_ERROR_MSG, e);
                }
            }
        });
    }

    private void fireOnMcpRegistered(@NotNull ToolCallRecord callRecord) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onMcpRegistered(callRecord);
                } catch (Exception e) {
                    LOG.warn(LISTENER_ERROR_MSG, e);
                }
            }
        });
    }

    private void fireOnCorrelated(@NotNull ToolCallRecord callRecord) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onCorrelated(callRecord);
                } catch (Exception e) {
                    LOG.warn(LISTENER_ERROR_MSG, e);
                }
            }
        });
    }

    private void fireOnMcpCompleted(@NotNull ToolCallRecord callRecord) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onMcpCompleted(callRecord);
                } catch (Exception e) {
                    LOG.warn(LISTENER_ERROR_MSG, e);
                }
            }
        });
    }

    private void fireOnAcpCompleted(@NotNull ToolCallRecord callRecord) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onAcpCompleted(callRecord);
                } catch (Exception e) {
                    LOG.warn(LISTENER_ERROR_MSG, e);
                }
            }
        });
    }

    private void fireOnFlushed(@NotNull ToolCallRecord callRecord) {
        if (listeners.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Listener l : listeners) {
                try {
                    l.onFlushed(callRecord);
                } catch (Exception e) {
                    LOG.warn(LISTENER_ERROR_MSG, e);
                }
            }
        });
    }
}

package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.memory.MemorySettings
import com.github.catatafishen.agentbridge.memory.mining.MiningTracker
import com.github.catatafishen.agentbridge.memory.mining.TurnMiner
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.session.migration.V1ToV2Migrator
import com.github.catatafishen.agentbridge.settings.ChatHistorySettings
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.time.Instant

/**
 * Manages conversation persistence: incremental saves, restore from disk,
 * history paging, archival, and usage-stats logging.
 */
class ConversationPersistenceManager(
    private val project: Project,
    private val conversationStore: ConversationService
) {

    companion object {
        private val LOG = Logger.getInstance(ConversationPersistenceManager::class.java)
        private const val AGENT_WORK_DIR = ".agent-work"
    }

    private val conversationReplayer = ConversationReplayer()

    private val saveIntervalMs = 30_000L

    @Volatile
    private var lastIncrementalSaveMs = 0L

    /** Number of entries already persisted to disk for the current session (deferred + panel). */
    @Volatile
    private var persistedEntryCount = 0

    private var callbacks: Callbacks? = null

    fun setCallbacks(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    // ------------------------------------------------------------------
    // Callbacks interface
    // ------------------------------------------------------------------

    interface Callbacks {
        /** Get all currently visible entries (deferred + panel entries) */
        fun getAllEntries(): List<EntryData>

        /** Get just the panel entries (for mining and archive) */
        fun getPanelEntries(): List<EntryData>

        /** Append restored entries to the chat panel */
        fun appendEntries(entries: List<EntryData>, totalPromptCount: Int)

        /** Prepend entries loaded from history */
        fun prependEntries(entries: List<EntryData>)

        /** Show "load more" indicator with remaining count */
        fun showLoadMore(remaining: Int)

        /** Hide "load more" indicator */
        fun hideLoadMore()

        /** Restore turn statistics in the timer panel */
        fun restoreTurnStats(stats: RestoredSessionStats, lastTurn: RestoredLastTurnStats)

        /** Restore billing counters */
        fun restoreBillingCounters(turnCount: Int, totalPremiumMultiplier: Double)

        /** Get the active agent's display name */
        fun getAgentDisplayName(): String

        /** Get the model multiplier for a model ID (may return null) */
        fun getModelMultiplier(modelId: String): String?

        /** Check if the agent client supports multiplier */
        fun supportsMultiplier(): Boolean
    }

    // ------------------------------------------------------------------
    // Data classes for stats restoration
    // ------------------------------------------------------------------

    data class RestoredSessionStats(
        val totalTimeMs: Long,
        val totalInputTokens: Long,
        val totalOutputTokens: Long,
        val totalCostUsd: Double,
        val totalToolCalls: Int,
        val totalLinesAdded: Int,
        val totalLinesRemoved: Int,
        val turnCount: Int
    )

    data class RestoredLastTurnStats(
        val elapsedSec: Long,
        val inputTokens: Int,
        val outputTokens: Int,
        val costUsd: Double?,
        val toolCalls: Int,
        val linesAdded: Int,
        val linesRemoved: Int,
        val multiplier: String
    )

    // ------------------------------------------------------------------
    // Incremental save
    // ------------------------------------------------------------------

    /**
     * Persists any new entries that have not yet been written to disk.
     */
    fun appendNewEntries() {
        lastIncrementalSaveMs = System.currentTimeMillis()
        val allEntries = conversationReplayer.deferredEntries() + (callbacks?.getPanelEntries() ?: emptyList())
        val newEntries = allEntries.drop(persistedEntryCount)
        if (newEntries.isEmpty()) return
        conversationStore.appendEntriesAsync(project.basePath, newEntries)
        persistedEntryCount = allEntries.size
    }

    /**
     * Appends new entries if at least [saveIntervalMs] elapsed since the last append.
     * Called after each tool-call completion during streaming so that long-running turns
     * are periodically persisted and survive IDE crashes.
     */
    fun appendNewEntriesThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastIncrementalSaveMs >= saveIntervalMs) {
            appendNewEntries()
        }
    }

    // ------------------------------------------------------------------
    // Memory mining
    // ------------------------------------------------------------------

    /**
     * Mines the current turn's entries into semantic memory (async, non-blocking).
     * Called by PromptOrchestrator after each turn completes.
     */
    fun mineEntriesAfterTurn(sessionId: String, agentName: String) {
        val settings = MemorySettings.getInstance(project)
        if (!settings.isEnabled || !settings.isAutoMineOnTurnComplete) return

        val entries = callbacks?.getPanelEntries() ?: return
        if (entries.isEmpty()) return

        val tracker = MiningTracker.getInstance(project)
        tracker.startTurnMining()

        val miner = TurnMiner(project)
        miner.mineTurn(entries, sessionId, agentName)
            .whenComplete { _, _ -> tracker.stop() }
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    /**
     * Loads conversation from disk on a pooled thread and restores entries on the EDT.
     */
    fun restoreConversation(onComplete: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            V1ToV2Migrator.migrateIfNeeded(project.basePath)
            val result = conversationStore.loadRecentEntries(project.basePath)
            val entries = result?.entries() ?: emptyList()
            val hasMoreOnDisk = result?.hasMoreOnDisk() ?: false
            ApplicationManager.getApplication().invokeLater {
                restoreEntries(entries, hasMoreOnDisk)
                onComplete()
            }
        }
    }

    private fun restoreEntries(entries: List<EntryData>, hasMoreOnDisk: Boolean) {
        if (entries.isEmpty()) return
        val cb = callbacks ?: return
        val histSettings = ChatHistorySettings.getInstance(project)
        conversationReplayer.loadAndSplit(entries, histSettings.recentTurnsOnRestore, hasMoreOnDisk)
        cb.appendEntries(
            conversationReplayer.recentEntries(),
            conversationReplayer.totalPromptCount()
        )
        showDeferredRestoreCount()
        restoreTurnStats(entries.filterIsInstance<EntryData.TurnStats>())
        persistedEntryCount = conversationReplayer.totalLoadedCount()
    }

    private fun showDeferredRestoreCount() {
        val deferred = conversationReplayer.remainingPromptCount()
        if (deferred > 0) callbacks?.showLoadMore(deferred)
    }

    private fun restoreTurnStats(turnStatsList: List<EntryData.TurnStats>) {
        val lastStats = turnStatsList.lastOrNull() ?: return
        val cb = callbacks ?: return

        val totalPremium = turnStatsList.sumOf {
            BillingCalculator.parseMultiplier(it.multiplier.ifEmpty { "1x" })
        }
        cb.restoreBillingCounters(turnStatsList.size, totalPremium)

        cb.restoreTurnStats(
            RestoredSessionStats(
                totalTimeMs = lastStats.totalDurationMs,
                totalInputTokens = lastStats.totalInputTokens,
                totalOutputTokens = lastStats.totalOutputTokens,
                totalCostUsd = lastStats.totalCostUsd,
                totalToolCalls = lastStats.totalToolCalls,
                totalLinesAdded = lastStats.totalLinesAdded,
                totalLinesRemoved = lastStats.totalLinesRemoved,
                turnCount = turnStatsList.size
            ),
            RestoredLastTurnStats(
                elapsedSec = lastStats.durationMs / 1000,
                inputTokens = lastStats.inputTokens.toInt(),
                outputTokens = lastStats.outputTokens.toInt(),
                costUsd = if (lastStats.costUsd > 0.0) lastStats.costUsd else null,
                toolCalls = lastStats.toolCallCount,
                linesAdded = lastStats.linesAdded,
                linesRemoved = lastStats.linesRemoved,
                multiplier = lastStats.multiplier
            )
        )
    }

    // ------------------------------------------------------------------
    // Load more history
    // ------------------------------------------------------------------

    /**
     * Loads the next batch of older history entries and updates UI via callbacks.
     */
    fun onLoadMoreHistory() {
        val cb = callbacks ?: return
        val batchSize = ChatHistorySettings.getInstance(project).loadMoreBatchSize
        val batch = conversationReplayer.loadNextBatch(batchSize)
        if (batch.isNotEmpty()) cb.prependEntries(batch)
        val remaining = conversationReplayer.remainingPromptCount()
        if (remaining > 0) {
            cb.showLoadMore(remaining)
        } else {
            if (conversationReplayer.hasOlderHistoryOnDisk) {
                LOG.info("Older history exists on disk but was not loaded (session too large for tail-read budget)")
            }
            cb.hideLoadMore()
        }
    }

    // ------------------------------------------------------------------
    // Archive
    // ------------------------------------------------------------------

    /**
     * Mines remaining entries and archives the current conversation.
     */
    fun archiveConversation() {
        val cb = callbacks
        val settings = MemorySettings.getInstance(project)
        if (settings.isEnabled && settings.isAutoMineOnSessionArchive) {
            val entries = cb?.getPanelEntries() ?: emptyList()
            if (entries.isNotEmpty()) {
                val tracker = MiningTracker.getInstance(project)
                tracker.startTurnMining()
                val sessionId = conversationStore.getCurrentSessionId(project.basePath)
                val agentName = cb?.getAgentDisplayName() ?: "unknown"
                val miner = TurnMiner(project)
                miner.mineTurn(entries, sessionId, agentName)
                    .whenComplete { _, _ -> tracker.stop() }
            }
        }
        conversationStore.archive()
        persistedEntryCount = 0
    }

    // ------------------------------------------------------------------
    // Usage stats
    // ------------------------------------------------------------------

    /**
     * Appends a turn's statistics to the usage-stats.jsonl file.
     */
    fun saveTurnStatistics(prompt: String, toolCalls: Int, modelId: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val statsDir = File(project.basePath ?: return@executeOnPooledThread, AGENT_WORK_DIR)
                statsDir.mkdirs()
                val statsFile = File(statsDir, "usage-stats.jsonl")
                val cb = callbacks
                val entry = JsonObject().apply {
                    addProperty("timestamp", Instant.now().toString())
                    addProperty("prompt", prompt.take(200))
                    addProperty("model", modelId)
                    if (cb?.supportsMultiplier() == true) {
                        val multiplier = try {
                            cb.getModelMultiplier(modelId)
                        } catch (_: Exception) {
                            null
                        }
                        if (multiplier != null) addProperty("multiplier", multiplier)
                    }
                    addProperty("toolCalls", toolCalls)
                }
                statsFile.appendText(entry.toString() + "\n")
            } catch (_: Exception) { /* best-effort */
            }
        }
    }

    // ------------------------------------------------------------------
    // Reset helpers
    // ------------------------------------------------------------------

    /**
     * Resets the persisted entry count to 0; called when session is reset.
     */
    fun resetPersistedState() {
        persistedEntryCount = 0
    }

    /**
     * Delegates to [ConversationService.resetCurrentSessionId].
     */
    fun resetCurrentSessionId() {
        conversationStore.resetCurrentSessionId(project.basePath)
    }

    /**
     * Exposes [ConversationReplayer.deferredEntries] for use by the host callbacks.
     */
    fun deferredEntries(): List<EntryData> = conversationReplayer.deferredEntries()

    /**
     * Delegates to [ConversationService.setCurrentAgent].
     */
    fun setCurrentAgent(agentName: String) {
        conversationStore.setCurrentAgent(agentName)
    }
}

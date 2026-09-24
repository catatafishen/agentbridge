package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.memory.MemorySettings
import com.github.catatafishen.agentbridge.memory.mining.MiningTracker
import com.github.catatafishen.agentbridge.memory.mining.TurnMiner
import com.github.catatafishen.agentbridge.session.ConversationEntryStore
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.session.migration.V1ToV2Migrator
import com.github.catatafishen.agentbridge.settings.ChatHistorySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

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
    }

    private val conversationReplayer = ConversationReplayer()

    /**
     * The entry store is the canonical data source for persistence. Set via [setEntryStore]
     * during panel setup. Reads never go through the UI panel — this keeps persistence
     * reliable even when the UI is frozen (e.g., Gateway thin-client connection outage).
     */
    @Volatile
    private var entryStore: ConversationEntryStore? = null

    fun setEntryStore(store: ConversationEntryStore) {
        entryStore = store
    }

    private val persistenceLock = Any()
    private val persistedEntryIds = mutableSetOf<String>()
    private val queuedEntryIds = mutableSetOf<String>()

    private var callbacks: Callbacks? = null

    fun setCallbacks(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    // ------------------------------------------------------------------
    // Callbacks interface
    // ------------------------------------------------------------------

    interface Callbacks {
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
        fun restoreBillingCounters(turnCount: Int)

        /** Get the active agent's display name */
        fun getAgentDisplayName(): String
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
        val linesRemoved: Int
    )

    // ------------------------------------------------------------------
    // Incremental save
    // ------------------------------------------------------------------

    fun appendNewEntries() {
        appendNewEntriesAsync()
    }

    /**
     * Queues every entry that has not already been committed or queued, and completes only after
     * SQLite commits the batch. Failed entries become eligible for the next save attempt.
     */
    fun appendNewEntriesAsync(): java.util.concurrent.CompletableFuture<Void> {
        val entries = entryStore?.getEntries().orEmpty()
        val newEntries = synchronized(persistenceLock) {
            entries.filter { entry ->
                entry.entryId !in persistedEntryIds && queuedEntryIds.add(entry.entryId)
            }
        }
        if (newEntries.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        }

        val entryIds = newEntries.map { it.entryId }.toSet()
        return conversationStore.appendEntriesAsync(project.basePath, newEntries)
            .whenComplete { _, error ->
                synchronized(persistenceLock) {
                    queuedEntryIds.removeAll(entryIds)
                    if (error == null) {
                        persistedEntryIds.addAll(entryIds)
                    }
                }
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

        val entries = entryStore?.getEntries() ?: return
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
            V1ToV2Migrator.migrateIfNeeded(project)
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
        synchronized(persistenceLock) {
            persistedEntryIds.clear()
            queuedEntryIds.clear()
            persistedEntryIds.addAll(entries.map { it.entryId })
        }
    }

    private fun showDeferredRestoreCount() {
        val deferred = conversationReplayer.remainingPromptCount()
        if (deferred > 0) callbacks?.showLoadMore(deferred)
    }

    private fun restoreTurnStats(turnStatsList: List<EntryData.TurnStats>) {
        val lastStats = turnStatsList.lastOrNull() ?: return
        val cb = callbacks ?: return

        cb.restoreBillingCounters(turnStatsList.size)

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
                linesRemoved = lastStats.linesRemoved
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
        val settings = MemorySettings.getInstance(project)
        if (settings.isEnabled && settings.isAutoMineOnSessionArchive) {
            val entries = entryStore?.getEntries() ?: emptyList()
            if (entries.isNotEmpty()) {
                val tracker = MiningTracker.getInstance(project)
                tracker.startTurnMining()
                val sessionId = conversationStore.getCurrentSessionId(project.basePath)
                val agentName = callbacks?.getAgentDisplayName() ?: "unknown"
                val miner = TurnMiner(project)
                miner.mineTurn(entries, sessionId, agentName)
                    .whenComplete { _, _ -> tracker.stop() }
            }
        }
        conversationStore.archive()
        synchronized(persistenceLock) {
            persistedEntryIds.clear()
            queuedEntryIds.clear()
        }
    }

    // ------------------------------------------------------------------
    // Reset helpers
    // ------------------------------------------------------------------

    /**
     * Delegates to [ConversationService.resetCurrentSessionId].
     */
    fun resetCurrentSessionId() {
        conversationStore.resetCurrentSessionId(project.basePath)
    }

    /**
     * Delegates to [ConversationService.setCurrentAgent].
     */
    fun setCurrentAgent(agentName: String) {
        conversationStore.setCurrentAgent(agentName)
    }
}

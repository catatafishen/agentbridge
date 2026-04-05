package com.github.catatafishen.ideagentforcopilot.ui

internal class ConversationReplayer {

    private val deferredEntries = ArrayDeque<EntryData>()
    private var recentSnapshot: List<EntryData> = emptyList()

    /**
     * Splits [entries] into recent/deferred and resets internal state.
     * Call [recentEntries] to get what should be rendered immediately, and
     * [loadNextBatch] each time the user scrolls up for more.
     */
    fun loadAndSplit(entries: List<EntryData>, recentTurns: Int = 5) {
        deferredEntries.clear()
        if (entries.isEmpty()) {
            recentSnapshot = emptyList()
            return
        }
        val splitAt = findSplitIndex(entries, recentTurns)
        for (i in 0 until splitAt) deferredEntries.addLast(entries[i])
        recentSnapshot = entries.subList(splitAt, entries.size)
    }

    /** Entries to render immediately (populated by [loadAndSplit]). */
    fun recentEntries(): List<EntryData> = recentSnapshot

    /** Number of entries still waiting in the deferred queue. */
    fun deferredCount(): Int = deferredEntries.size

    /** Number of prompt turns still waiting in the deferred queue. */
    fun remainingPromptCount(): Int = deferredEntries.count { it is EntryData.Prompt }

    /** Total prompt count across both deferred and recent entries. */
    fun totalPromptCount(): Int =
        deferredEntries.count { it is EntryData.Prompt } + recentSnapshot.count { it is EntryData.Prompt }

    /**
     * Pops the next [turnsToLoad] prompt-turns (plus all entries between them) from
     * the deferred queue. Returns entries in chronological order (oldest first).
     */
    fun loadNextBatch(turnsToLoad: Int = 3): List<EntryData> {
        if (deferredEntries.isEmpty()) return emptyList()
        var promptCount = 0
        var start = deferredEntries.size - 1
        while (start >= 0) {
            if (deferredEntries[start] is EntryData.Prompt) promptCount++
            if (promptCount >= turnsToLoad) break
            start--
        }
        if (start < 0) start = 0
        val batch = deferredEntries.subList(start, deferredEntries.size).toList()
        repeat(batch.size) { deferredEntries.removeLast() }
        return batch
    }

    private fun findSplitIndex(entries: List<EntryData>, turnsFromEnd: Int): Int {
        var promptCount = 0
        for (i in entries.indices.reversed()) {
            if (entries[i] is EntryData.Prompt) promptCount++
            if (promptCount >= turnsFromEnd) return i
        }
        return 0
    }
}

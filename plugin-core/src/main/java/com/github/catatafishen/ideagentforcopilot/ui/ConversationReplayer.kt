package com.github.catatafishen.ideagentforcopilot.ui

internal class ConversationReplayer {

    private val deferredEntries = ArrayDeque<EntryData>()
    private var recentSnapshot: List<EntryData> = emptyList()

    /**
     * Deserialises [json], splits into recent/deferred, and resets internal state.
     * Call [recentEntries] to get what should be rendered immediately, and
     * [loadNextBatch] each time the user scrolls up for more.
     */
    fun loadAndSplit(json: String, recentTurns: Int = 5) {
        deferredEntries.clear()
        val all = ConversationSerializer.deserialize(json)
        if (all.isEmpty()) {
            recentSnapshot = emptyList()
            return
        }
        val splitAt = findSplitIndex(all, recentTurns)
        for (i in 0 until splitAt) deferredEntries.addLast(all[i])
        recentSnapshot = all.subList(splitAt, all.size)
    }

    /** Entries to render immediately (populated by [loadAndSplit]). */
    fun recentEntries(): List<EntryData> = recentSnapshot

    /** Number of entries still waiting in the deferred queue. */
    fun deferredCount(): Int = deferredEntries.size

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

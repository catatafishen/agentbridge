package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.model.SessionUpdate

/**
 * Accumulates compactions reported by the agent within one turn and decides what the chat shows
 * for each update. Pure logic, no UI.
 *
 * Summary rules follow ACP "Session compaction": summary chunks append to the compaction with the
 * same ID; a `summary` on `compaction_update` replaces what was accumulated so far (an empty one
 * clears it); omitting it keeps the accumulated text.
 */
class CompactionTracker {

    /** What the chat should show for one update; [summary] is the text accumulated so far. */
    data class Notice(val status: SessionUpdate.CompactionStatus, val error: String?, val summary: String?)

    private val summaries = HashMap<String, StringBuilder>()

    /** Applies a [SessionUpdate.Compaction] and returns what to show for it. */
    fun onUpdate(update: SessionUpdate.Compaction): Notice {
        val id = update.compactionId()
        val summary = if (id == null) {
            update.summary()
        } else {
            val acc = summaries.getOrPut(id) { StringBuilder() }
            update.summary()?.let { acc.setLength(0); acc.append(it) }
            acc.toString().ifEmpty { null }
        }
        return Notice(update.status(), update.error(), summary)
    }

    /** Appends a summary chunk to its compaction. */
    fun onChunk(chunk: SessionUpdate.CompactionSummaryChunk) {
        summaries.getOrPut(chunk.compactionId()) { StringBuilder() }.append(chunk.text())
    }

    /** Forgets all compactions, e.g. at the start of a new turn. */
    fun reset() = summaries.clear()

    companion object {
        /**
         * Chat text for a compaction [notice] reported by [agentName]. The summary is appended for a
         * completed compaction.
         */
        @JvmStatic
        fun message(agentName: String, notice: Notice): String = when (notice.status) {
            SessionUpdate.CompactionStatus.STARTED -> "$agentName is compacting the conversation context…"
            SessionUpdate.CompactionStatus.COMPLETED -> buildString {
                append("$agentName compacted the conversation context. The session continues with a summary of it.")
                notice.summary?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
            }

            SessionUpdate.CompactionStatus.FAILED ->
                "$agentName could not compact the conversation context" + (notice.error?.let { ": $it" } ?: ".")

            SessionUpdate.CompactionStatus.CANCELLED -> "$agentName cancelled compacting the conversation context."
            SessionUpdate.CompactionStatus.UNKNOWN -> "$agentName reported a compaction status AgentBridge does not know."
        }

        /** Whether [notice] is a failure, shown as an error rather than information. */
        @JvmStatic
        fun isError(notice: Notice) = notice.status == SessionUpdate.CompactionStatus.FAILED
    }
}

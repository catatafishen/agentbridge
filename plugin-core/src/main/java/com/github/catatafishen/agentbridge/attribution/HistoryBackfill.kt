package com.github.catatafishen.agentbridge.attribution

/** One recorded editing tool call, reduced to what attribution needs. */
data class RecordedEdit(
    val turnId: String,
    val timestampMs: Long,
    /** The text the call put into the file; for creates/writes, the whole new content. */
    val newText: String,
    /** True when the call created or fully rewrote the file. */
    val wholeFile: Boolean,
)

/**
 * Best-effort reconstruction of line ownership from recorded tool calls, for files edited before live
 * capture existed. Pure logic; the result is always flagged inferred.
 *
 * Calls are replayed oldest to newest against the file's *current* text. A call's inserted text is
 * located in the current text, and the lines it covers are claimed for that call's turn; later calls
 * overwrite earlier claims, matching "last writer wins". A call whose text can no longer be found (the
 * code was changed afterwards) is skipped rather than guessed.
 */
object HistoryBackfill {

    /** Longest tool-call text that is searched for; huge payloads are skipped to bound the cost. */
    private const val MAX_TEXT_CHARS = 200_000

    /** Texts shorter than this are too ambiguous to place (e.g. a lone `}`), and are skipped. */
    private const val MIN_LINES_OR_CHARS = 8

    fun replay(currentLines: List<String>, edits: List<RecordedEdit>): LineOwnership {
        val model = LineOwnership.empty(currentLines.size)
        val ordered = edits.sortedBy { it.timestampMs }
        for ((index, edit) in ordered.withIndex()) {
            val owner = LineOwner(edit.turnId, edit.timestampMs, ownerKind(edit), inferred = true)
            if (edit.wholeFile) {
                claimWholeFile(model, currentLines, edit, owner, isLast = index == ordered.lastIndex)
            } else {
                claimInserted(model, currentLines, edit.newText, owner)
            }
        }
        return model
    }

    private fun ownerKind(edit: RecordedEdit) = if (edit.wholeFile) LineOwner.KIND_CREATE else LineOwner.KIND_EDIT

    /**
     * A whole-file write owns exactly the lines that still equal its content, in order, so the lines
     * later calls replaced are left for those calls to claim.
     */
    private fun claimWholeFile(
        model: LineOwnership, current: List<String>, edit: RecordedEdit, owner: LineOwner, isLast: Boolean,
    ) {
        if (edit.newText.length > MAX_TEXT_CHARS) return
        val written = edit.newText.split('\n')
        val matches = lcsMatches(written, current)
        if (matches.isEmpty()) return
        // Require a meaningful overlap so an unrelated later rewrite does not claim stray lines.
        if (!isLast && matches.size * 2 < written.size.coerceAtMost(current.size)) return
        for (line in matches) claim(model, line, line, owner)
    }

    private fun claimInserted(model: LineOwnership, current: List<String>, newText: String, owner: LineOwner) {
        if (newText.isEmpty() || newText.length > MAX_TEXT_CHARS) return
        val lines = newText.split('\n')
        if (lines.size < 2 && newText.trim().length < MIN_LINES_OR_CHARS) return
        val at = findUnique(current, lines) ?: return
        claim(model, at, at + lines.size - 1, owner)
    }

    private fun claim(model: LineOwnership, from: Int, to: Int, owner: LineOwner) {
        // Reuse the diff-free path: claim by replacing the identical region with an owned copy.
        model.claimRange(from, to, owner)
    }

    /** Index of the only place [needle] occurs in [haystack], or null when absent or ambiguous. */
    internal fun findUnique(haystack: List<String>, needle: List<String>): Int? {
        if (needle.isEmpty() || needle.size > haystack.size) return null
        var found = -1
        for (start in 0..haystack.size - needle.size) {
            if (matchesAt(haystack, needle, start)) {
                if (found >= 0) return null
                found = start
            }
        }
        return found.takeIf { it >= 0 }
    }

    private fun matchesAt(haystack: List<String>, needle: List<String>, start: Int): Boolean {
        for (i in needle.indices) if (haystack[start + i] != needle[i]) return false
        return true
    }

    /** Indices in [current] of a longest common subsequence with [written], in order. */
    internal fun lcsMatches(written: List<String>, current: List<String>): List<Int> {
        if (written.isEmpty() || current.isEmpty()) return emptyList()
        if (written.size.toLong() * current.size > 4_000_000L) return emptyList()
        val n = written.size
        val m = current.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (written[i] == current[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val result = ArrayList<Int>(dp[0][0])
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                written[i] == current[j] -> {
                    result.add(j); i++; j++
                }

                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return result
    }
}

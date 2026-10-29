package com.github.catatafishen.agentbridge.attribution

import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import java.security.MessageDigest

/** Who last wrote a line through an editing tool call. */
data class LineOwner(
    val turnId: String,
    val timestampMs: Long,
    val kind: String = KIND_EDIT,
    /** True when derived by replaying old tool calls rather than captured live. */
    val inferred: Boolean = false,
) {
    companion object {
        const val KIND_EDIT = "edit"
        const val KIND_CREATE = "create"
    }
}

/** A run of consecutive lines (inclusive, 0-based) with the same owner. */
data class OwnerRun(val owner: LineOwner, val startLine: Int, val endLine: Int, val contentHash: String)

/**
 * Per-line ownership of one file: which turn (prompt) last wrote each line through an editing tool.
 *
 * Pure logic, no IDE state. A line is `null` when it was written by the user or is otherwise unknown.
 *
 * Edits are applied as a line diff between the old and new text of the changed region, so unchanged
 * lines keep their owner even when a tool rewrites a whole file.
 *  * Agent edit: inserted/modified lines are owned by the agent's turn.
 *  * Other edit (user, formatter): lines modified in place keep their owner (so reformatting or a
 *    small tweak does not erase attribution), pure insertions become unowned, deletions disappear.
 */
class LineOwnership private constructor(private val owners: MutableList<LineOwner?>) {

    val lineCount: Int get() = owners.size

    fun ownerAt(line: Int): LineOwner? = owners.getOrNull(line)

    fun isEmpty(): Boolean = owners.none { it != null }

    /**
     * Applies a document change that replaced [oldLines] by [newLines] starting at line [startLine].
     * Both lists are the full lines of the affected region (including the partial first/last line).
     */
    fun applyEdit(startLine: Int, oldLines: List<String>, newLines: List<String>, owner: LineOwner?) {
        val start = startLine.coerceIn(0, owners.size)
        val oldCount = oldLines.size.coerceAtMost(owners.size - start)
        val old = owners.subList(start, start + oldCount).toList()

        val replacement = ArrayList<LineOwner?>(newLines.size)
        var oldIdx = 0
        var newIdx = 0
        for (hunk in hunks(oldLines, newLines)) {
            while (oldIdx < hunk.oldStart) { // unchanged lines
                replacement.add(old.getOrNull(oldIdx)); oldIdx++; newIdx++
            }
            for (k in 0 until hunk.inserted) {
                val inPlace = k < hunk.deleted
                replacement.add(
                    when {
                        owner != null -> owner
                        inPlace -> old.getOrNull(hunk.oldStart + k)
                        else -> null
                    }
                )
            }
            oldIdx = hunk.oldStart + hunk.deleted
            newIdx = hunk.newStart + hunk.inserted
        }
        while (newIdx < newLines.size) { // trailing unchanged lines
            replacement.add(old.getOrNull(oldIdx)); oldIdx++; newIdx++
        }

        val region = owners.subList(start, start + oldCount)
        region.clear()
        owners.addAll(start, replacement)
    }

    /** Marks every line as written by [owner]; used when a tool creates a file. */
    fun claimAll(owner: LineOwner) {
        for (i in owners.indices) owners[i] = owner
    }

    /** Marks lines [from]..[toInclusive] (clamped to the file) as written by [owner]. */
    fun claimRange(from: Int, toInclusive: Int, owner: LineOwner) {
        for (i in from.coerceAtLeast(0)..toInclusive.coerceAtMost(owners.size - 1)) owners[i] = owner
    }

    /** Collapses to runs of consecutive lines with the same owner. [lines] provides the text for hashing. */
    fun toRuns(lines: List<String>): List<OwnerRun> {
        val runs = ArrayList<OwnerRun>()
        var i = 0
        while (i < owners.size) {
            val owner = owners[i]
            if (owner == null) {
                i++; continue
            }
            var j = i
            while (j + 1 < owners.size && owners[j + 1] == owner) j++
            runs.add(OwnerRun(owner, i, j, hashLines(lines, i, j)))
            i = j + 1
        }
        return runs
    }

    private data class Hunk(val oldStart: Int, val deleted: Int, val newStart: Int, val inserted: Int)

    private fun hunks(oldLines: List<String>, newLines: List<String>): List<Hunk> {
        if (oldLines == newLines) return emptyList()
        return try {
            val result = ArrayList<Hunk>()
            var c: Diff.Change? = Diff.buildChanges(oldLines.toTypedArray(), newLines.toTypedArray())
            while (c != null) {
                result.add(Hunk(c.line0, c.deleted, c.line1, c.inserted))
                c = c.link
            }
            result
        } catch (_: FilesTooBigForDiffException) {
            listOf(Hunk(0, oldLines.size, 0, newLines.size))
        }
    }

    companion object {
        fun empty(lineCount: Int) = LineOwnership(MutableList(lineCount.coerceAtLeast(0)) { null })

        /**
         * Rebuilds ownership from stored [runs] for a file whose current text is [lines].
         *
         * When [storedFileHash] equals the hash of the current text the stored line numbers are exact.
         * Otherwise the file changed while the IDE was not watching (branch switch, external edit):
         * each run is kept only if its text can still be found, preferring the stored position.
         */
        fun restore(runs: List<OwnerRun>, storedFileHash: String?, lines: List<String>): LineOwnership {
            val model = empty(lines.size)
            val exact = storedFileHash != null && storedFileHash == hashText(lines)
            val taken = BooleanArray(lines.size)
            for (run in runs.sortedBy { it.startLine }) {
                val length = run.endLine - run.startLine + 1
                val at = when {
                    fitsAt(lines, run, run.startLine, taken) && (exact || matchesAt(
                        lines,
                        run,
                        run.startLine
                    )) -> run.startLine

                    exact -> -1
                    else -> findRun(lines, run, length, taken)
                }
                if (at < 0) continue
                for (l in at until at + length) {
                    model.owners[l] = run.owner
                    taken[l] = true
                }
            }
            return model
        }

        private fun fitsAt(lines: List<String>, run: OwnerRun, at: Int, taken: BooleanArray): Boolean {
            val end = at + (run.endLine - run.startLine)
            return at >= 0 && end < lines.size && (at..end).none { taken[it] }
        }

        private fun matchesAt(lines: List<String>, run: OwnerRun, at: Int): Boolean =
            hashLines(lines, at, at + (run.endLine - run.startLine)) == run.contentHash

        private fun findRun(lines: List<String>, run: OwnerRun, length: Int, taken: BooleanArray): Int {
            for (at in 0..lines.size - length) {
                if (fitsAt(lines, run, at, taken) && matchesAt(lines, run, at)) return at
            }
            return -1
        }

        fun hashLines(lines: List<String>, from: Int, toInclusive: Int): String =
            sha1((from..toInclusive).joinToString("\n") { lines.getOrElse(it) { "" } })

        fun hashText(lines: List<String>): String = sha1(lines.joinToString("\n"))

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Splits text into lines the way the editor does: a trailing newline yields a final empty line. */
        fun splitLines(text: CharSequence): List<String> = text.toString().split('\n')
    }
}

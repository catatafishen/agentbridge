package com.github.catatafishen.agentbridge.attribution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LineOwnershipTest {

    private val t1 = LineOwner("t1", 1000)
    private val t2 = LineOwner("t2", 2000)

    private fun lines(vararg l: String) = l.toList()

    private fun owners(m: LineOwnership) = (0 until m.lineCount).map { m.ownerAt(it)?.turnId }

    @Test
    fun `new file claimed by one turn owns every line`() {
        val m = LineOwnership.empty(3)
        m.claimAll(t1)
        assertEquals(listOf("t1", "t1", "t1"), owners(m))
    }

    @Test
    fun `agent insertion owns the new lines and shifts the rest`() {
        val m = LineOwnership.empty(3).also { it.claimAll(t1) }
        // insert two lines at line 1: region is the single line "b" replaced by "x","y","b"
        m.applyEdit(1, lines("b"), lines("x", "y", "b"), t2)
        assertEquals(listOf("t1", "t2", "t2", "t1", "t1"), owners(m))
    }

    @Test
    fun `agent replacing a region owns only the changed lines`() {
        val m = LineOwnership.empty(4).also { it.claimAll(t1) }
        m.applyEdit(0, lines("a", "b", "c", "d"), lines("a", "B", "c", "d"), t2)
        assertEquals(listOf("t1", "t2", "t1", "t1"), owners(m))
    }

    @Test
    fun `agent deleting lines removes their ownership`() {
        val m = LineOwnership.empty(4).also { it.claimAll(t1) }
        m.applyEdit(1, lines("b", "c"), lines(), t2)
        assertEquals(2, m.lineCount)
        assertEquals(listOf("t1", "t1"), owners(m))
    }

    @Test
    fun `a later agent edit takes over lines from an earlier one`() {
        val m = LineOwnership.empty(2).also { it.claimAll(t1) }
        m.applyEdit(0, lines("a", "b"), lines("a", "B"), t2)
        m.applyEdit(1, lines("B"), lines("BB"), LineOwner("t3", 3000))
        assertEquals(listOf("t1", "t3"), owners(m))
    }

    @Test
    fun `user typing inside an owned line keeps the owner`() {
        val m = LineOwnership.empty(2).also { it.claimAll(t1) }
        m.applyEdit(0, lines("hello"), lines("hello world"), null)
        assertEquals(listOf("t1", "t1"), owners(m))
    }

    @Test
    fun `user inserting a new line leaves it unowned`() {
        val m = LineOwnership.empty(2).also { it.claimAll(t1) }
        m.applyEdit(1, lines("b"), lines("new", "b"), null)
        assertEquals(listOf("t1", null, "t1"), owners(m))
    }

    @Test
    fun `reformatting keeps ownership of unchanged and modified lines`() {
        val m = LineOwnership.empty(3).also { it.claimAll(t1) }
        m.applyEdit(0, lines("a", "b", "c"), lines("a", "  b", "c"), null)
        assertEquals(listOf("t1", "t1", "t1"), owners(m))
    }

    @Test
    fun `whole file rewrite by the agent only claims lines that actually changed`() {
        val m = LineOwnership.empty(3).also { it.claimAll(t1) }
        m.applyEdit(0, lines("a", "b", "c"), lines("a", "B", "c"), t2)
        assertEquals(listOf("t1", "t2", "t1"), owners(m))
    }

    @Test
    fun `runs merge consecutive lines of the same owner and skip unowned lines`() {
        val m = LineOwnership.empty(5)
        m.applyEdit(0, lines("a", "b", "c", "d", "e"), lines("a", "b", "c", "d", "e"), t1) // no-op
        assertTrue(m.isEmpty())
        val full = LineOwnership.empty(5).also { it.claimAll(t1) }
        full.applyEdit(2, lines("c"), lines("C"), t2)
        val runs = full.toRuns(lines("a", "b", "C", "d", "e"))
        assertEquals(listOf(0 to 1, 2 to 2, 3 to 4), runs.map { it.startLine to it.endLine })
        assertEquals(listOf("t1", "t2", "t1"), runs.map { it.owner.turnId })
    }

    @Test
    fun `restore with matching file hash places runs exactly`() {
        val text = lines("a", "b", "c")
        val m = LineOwnership.empty(3).also { it.claimAll(t1) }
        m.applyEdit(1, lines("b"), lines("B"), t2)
        val current = lines("a", "B", "c")
        val runs = m.toRuns(current)

        val restored = LineOwnership.restore(runs, LineOwnership.hashText(current), current)

        assertEquals(listOf("t1", "t2", "t1"), owners(restored))
        assertEquals(3, text.size)
    }

    @Test
    fun `restore after the file changed re-anchors a run by its content`() {
        val before = lines("a", "b", "c")
        val m = LineOwnership.empty(3)
        m.applyEdit(0, before, before, null)
        val owned = LineOwnership.empty(3).also { it.claimAll(t1) }
        val runs = owned.toRuns(before)
        // file now has two extra lines on top: the same text moved down
        val after = lines("x", "y", "a", "b", "c")

        val restored = LineOwnership.restore(runs, "stale-hash", after)

        assertEquals(listOf(null, null, "t1", "t1", "t1"), owners(restored))
    }

    @Test
    fun `restore drops runs whose text no longer exists`() {
        val owned = LineOwnership.empty(2).also { it.claimAll(t1) }
        val runs = owned.toRuns(lines("a", "b"))

        val restored = LineOwnership.restore(runs, "stale-hash", lines("totally", "different"))

        assertTrue(restored.isEmpty())
    }

    @Test
    fun `restore with a matching hash never moves runs`() {
        val current = lines("a", "b", "a", "b")
        val owned = LineOwnership.empty(4)
        owned.applyEdit(0, current, current, null)
        val first = LineOwnership.empty(4)
        first.applyEdit(0, current, lines("a", "b", "X", "Y"), t1)
        val runs = first.toRuns(lines("a", "b", "X", "Y")).map { it.copy() }

        val restored =
            LineOwnership.restore(runs, LineOwnership.hashText(lines("a", "b", "X", "Y")), lines("a", "b", "X", "Y"))

        assertEquals(listOf(null, null, "t1", "t1"), owners(restored))
        assertNull(restored.ownerAt(0))
    }

    @Test
    fun `split lines keeps a trailing empty line like the editor`() {
        assertEquals(listOf("a", "b", ""), LineOwnership.splitLines("a\nb\n"))
        assertEquals(listOf(""), LineOwnership.splitLines(""))
    }
}

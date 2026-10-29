package com.github.catatafishen.agentbridge.attribution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryBackfillTest {

    private fun edit(turn: String, at: Long, text: String, whole: Boolean = false) =
        RecordedEdit(turn, at, text, whole)

    private fun owners(m: LineOwnership) = (0 until m.lineCount).map { m.ownerAt(it)?.turnId }

    private val file = listOf(
        "class Foo {",
        "    int a = 1;",
        "    int b = 2;",
        "    void run() {",
        "        System.out.println(a + b);",
        "    }",
        "}",
    )

    @Test
    fun `an inserted block is claimed by the turn that wrote it`() {
        val m = HistoryBackfill.replay(
            file,
            listOf(edit("t1", 1, "    void run() {\n        System.out.println(a + b);\n    }"))
        )
        assertEquals(listOf(null, null, null, "t1", "t1", "t1", null), owners(m))
    }

    @Test
    fun `everything claimed by backfill is flagged inferred`() {
        val m = HistoryBackfill.replay(file, listOf(edit("t1", 1, "    int a = 1;\n    int b = 2;")))
        assertTrue(m.ownerAt(1)!!.inferred)
    }

    @Test
    fun `a later call wins over an earlier one on the same lines`() {
        val m = HistoryBackfill.replay(
            file,
            listOf(
                edit("t2", 20, "    int a = 1;\n    int b = 2;"),
                edit("t1", 10, "    int a = 1;\n    int b = 2;\n    void run() {"),
            )
        )
        assertEquals(listOf(null, "t2", "t2", "t1", null, null, null), owners(m))
    }

    @Test
    fun `text that no longer exists in the file is skipped`() {
        val m = HistoryBackfill.replay(file, listOf(edit("t1", 1, "    int gone = 99;\n    int missing = 100;")))
        assertTrue(m.isEmpty())
    }

    @Test
    fun `ambiguous text that occurs twice is not guessed`() {
        val twice = listOf("    return value;", "    foo();", "    return value;", "    foo();")
        val m = HistoryBackfill.replay(twice, listOf(edit("t1", 1, "    return value;\n    foo();")))
        assertTrue(m.isEmpty())
    }

    @Test
    fun `a single tiny line is too ambiguous to place`() {
        val m = HistoryBackfill.replay(file, listOf(edit("t1", 1, "}")))
        assertTrue(m.isEmpty())
    }

    @Test
    fun `a created file owns the lines that still match its content`() {
        val created = file.joinToString("\n")
        val m = HistoryBackfill.replay(file, listOf(edit("t1", 1, created, whole = true)))
        assertEquals(List(file.size) { "t1" }, owners(m))
        assertEquals(LineOwner.KIND_CREATE, m.ownerAt(0)!!.kind)
    }

    @Test
    fun `a later edit takes over lines from the create`() {
        val created = file.joinToString("\n")
        val m = HistoryBackfill.replay(
            file,
            listOf(
                edit("t1", 1, created, whole = true),
                edit("t2", 5, "    int a = 1;\n    int b = 2;"),
            )
        )
        assertEquals(listOf("t1", "t2", "t2", "t1", "t1", "t1", "t1"), owners(m))
    }

    @Test
    fun `lines changed since the create stay unowned`() {
        val created = file.joinToString("\n")
        val changed = file.toMutableList().also { it[1] = "    int a = 100; // user" }
        val m = HistoryBackfill.replay(changed, listOf(edit("t1", 1, created, whole = true)))
        assertNull(m.ownerAt(1))
        assertEquals("t1", m.ownerAt(0)?.turnId)
        assertEquals("t1", m.ownerAt(2)?.turnId)
    }

    @Test
    fun `an old rewrite that no longer resembles the file claims nothing`() {
        val oldVersion = (1..40).joinToString("\n") { "old line $it" }
        val m = HistoryBackfill.replay(
            file,
            listOf(
                edit("t1", 1, oldVersion, whole = true),
                edit("t2", 5, "    int a = 1;\n    int b = 2;"),
            )
        )
        assertEquals(listOf(null, "t2", "t2", null, null, null, null), owners(m))
    }

    @Test
    fun `no recorded edits give an empty result`() {
        assertTrue(HistoryBackfill.replay(file, emptyList()).isEmpty())
    }

    @Test
    fun `longest common subsequence keeps order and skips changed lines`() {
        assertEquals(listOf(0, 2), HistoryBackfill.lcsMatches(listOf("a", "b", "c"), listOf("a", "x", "c")))
        assertEquals(emptyList<Int>(), HistoryBackfill.lcsMatches(listOf("a"), listOf("z")))
    }

    @Test
    fun `findUnique returns the single occurrence`() {
        assertEquals(1, HistoryBackfill.findUnique(listOf("a", "b", "c"), listOf("b", "c")))
        assertNull(HistoryBackfill.findUnique(listOf("a", "b", "a", "b"), listOf("a", "b")))
        assertNull(HistoryBackfill.findUnique(listOf("a"), listOf("a", "b")))
    }
}

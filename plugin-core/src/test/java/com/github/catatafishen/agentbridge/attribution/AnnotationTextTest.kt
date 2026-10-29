package com.github.catatafishen.agentbridge.attribution

import com.github.catatafishen.agentbridge.session.db.LineAttributionStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AnnotationTextTest {

    private val now = 10_000_000_000L
    private val owner = LineOwner("t1", now)

    private fun info(prompt: String = "Add a retry to the payment client", model: String? = "gpt-5") =
        LineAttributionStore.TurnInfo("t1", "s1", prompt, "Copilot", "Payments work", model, now - 3 * 3_600_000L)

    @Test
    fun `gutter label shows the shortened prompt and its age`() {
        val label = AnnotationText.gutterLabel(info(), owner, now)
        assertTrue(label.startsWith("Add a retry to the paym"), label)
        assertTrue(label.endsWith("3h ago"), label)
        assertTrue(label.contains("\u2026"), "long prompts are truncated: $label")
    }

    @Test
    fun `gutter label marks inferred attribution`() {
        val label = AnnotationText.gutterLabel(info("Short"), owner.copy(inferred = true), now)
        assertTrue(label.startsWith("~Short"))
    }

    @Test
    fun `gutter label before details load is a placeholder`() {
        assertEquals("\u2026", AnnotationText.gutterLabel(null, owner, now))
        assertEquals("~\u2026", AnnotationText.gutterLabel(null, owner.copy(inferred = true), now))
    }

    @Test
    fun `blank prompts fall back to a generic label`() {
        assertTrue(AnnotationText.gutterLabel(info(" "), owner, now).startsWith(AnnotationText.UNKNOWN_PROMPT))
    }

    @Test
    fun `tooltip contains agent model session and full prompt and escapes html`() {
        val html = AnnotationText.tooltipHtml(info("Fix <b>this</b> & that"), owner)
        assertTrue(html.contains("Copilot"))
        assertTrue(html.contains("gpt-5"))
        assertTrue(html.contains("Payments work"))
        assertTrue(html.contains("Fix &lt;b&gt;this&lt;/b&gt; &amp; that"))
        assertTrue(html.contains("Click to open"))
    }

    @Test
    fun `tooltip notes created files and inferred attribution`() {
        val created = AnnotationText.tooltipHtml(info(), owner.copy(kind = LineOwner.KIND_CREATE))
        assertTrue(created.contains("Created this file"))
        assertFalse(created.contains("Inferred"))
        assertTrue(AnnotationText.tooltipHtml(info(), owner.copy(inferred = true)).contains("Inferred"))
    }

    @Test
    fun `tooltip without details still renders`() {
        assertTrue(AnnotationText.tooltipHtml(null, owner).contains("details not available"))
    }

    @Test
    fun `a run starts where the owner changes`() {
        val m = LineOwnership.empty(5)
        m.claimAll(LineOwner("a", 1))
        m.applyEdit(2, listOf("c", "d"), listOf("C", "D"), LineOwner("b", 2))

        assertTrue(AnnotationText.startsRun(m, 0))
        assertFalse(AnnotationText.startsRun(m, 1))
        assertTrue(AnnotationText.startsRun(m, 2))
        assertFalse(AnnotationText.startsRun(m, 3))
        assertTrue(AnnotationText.startsRun(m, 4))
    }

    @Test
    fun `file entry shows a line count only when attribution is known`() {
        assertEquals("src/A.java", AnnotationText.fileEntryLabel("src/A.java", -1))
        assertEquals("src/A.java  (1 line still from this prompt)", AnnotationText.fileEntryLabel("src/A.java", 1))
        assertTrue(AnnotationText.fileEntryLabel("src/A.java", 7).contains("7 lines"))
    }

    @Test
    fun `unowned lines never start a run`() {
        assertFalse(AnnotationText.startsRun(LineOwnership.empty(3), 1))
    }

    @Test
    fun `hue is stable per turn and within range`() {
        assertEquals(AnnotationText.hueFor("turn-1"), AnnotationText.hueFor("turn-1"))
        assertTrue(AnnotationText.hueFor("turn-1") in 0f..1f)
        assertTrue(AnnotationText.hueFor("anything-else") in 0f..1f)
    }
}

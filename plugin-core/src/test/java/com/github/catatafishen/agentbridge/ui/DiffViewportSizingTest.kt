package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests for [DiffViewportSizing] — pure functions, no IDE fixtures. */
class DiffViewportSizingTest {

    @Test
    fun `short diff that fits horizontally uses exactly its content height`() {
        assertEquals(60, DiffViewportSizing.viewportHeight(60, 300, 500, 14, 320))
    }

    @Test
    fun `horizontal scrollbar height is reserved so no vertical bar is forced`() {
        assertEquals(74, DiffViewportSizing.viewportHeight(60, 800, 500, 14, 320))
    }

    @Test
    fun `diff a few pixels under the cap with a horizontal bar is capped, not overflowed`() {
        assertEquals(320, DiffViewportSizing.viewportHeight(316, 800, 500, 14, 320))
    }

    @Test
    fun `tall diff is capped at max height`() {
        assertEquals(320, DiffViewportSizing.viewportHeight(2000, 300, 500, 14, 320))
    }

    @Test
    fun `unlaid out pane assumes no horizontal bar`() {
        assertEquals(60, DiffViewportSizing.viewportHeight(60, 800, 0, 14, 320))
    }

    @Test
    fun `content exactly as wide as the viewport needs no horizontal bar`() {
        assertEquals(60, DiffViewportSizing.viewportHeight(60, 500, 500, 14, 320))
    }

    @Test
    fun `vertical scroll is needed only when content exceeds the cap`() {
        assertFalse(DiffViewportSizing.needsVerticalScroll(320, 320))
        assertTrue(DiffViewportSizing.needsVerticalScroll(321, 320))
    }
}

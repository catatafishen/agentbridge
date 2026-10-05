package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TurnStatsRowTest {

    @Test
    fun `usage is shown as used of size with a percentage`() {
        assertEquals("42k / 128k (33%)", formatContextUsage(42_000, 128_000))
    }

    @Test
    fun `small and large figures are abbreviated sensibly`() {
        assertEquals("850 / 4.1k (21%)", formatContextUsage(850, 4_096))
        assertEquals("1.5k / 8k (19%)", formatContextUsage(1_500, 8_000))
        assertEquals("200k / 1M (20%)", formatContextUsage(200_000, 1_000_000))
        assertEquals("1.2M / 2M (60%)", formatContextUsage(1_200_000, 2_000_000))
    }

    @Test
    fun `an overfull window shows the real percentage instead of being capped`() {
        assertEquals("130k / 128k (102%)", formatContextUsage(130_000, 128_000))
    }

    @Test
    fun `an empty conversation shows zero`() {
        assertEquals("0 / 128k (0%)", formatContextUsage(0, 128_000))
    }

    @Test
    fun `nothing is shown when either figure is unknown or unusable`() {
        assertNull(formatContextUsage(null, 128_000))
        assertNull(formatContextUsage(42_000, null))
        assertNull(formatContextUsage(null, null))
        assertNull(formatContextUsage(42_000, 0))
        assertNull(formatContextUsage(-1, 128_000))
    }
}

package com.github.catatafishen.agentbridge.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JPanel

/**
 * Paints a [NativeMarkdownPane] inside a bubble and checks the frame it caches: opaque over the
 * colour behind it, redrawn when that colour changes, translucent when the colour is unknown.
 */
class NativeMarkdownBackdropPaintTest : NativeMarkdownPaneTestBase() {

    private lateinit var outer: JPanel
    private lateinit var pane: NativeMarkdownPane

    override fun setUp() {
        super.setUp()
        outer = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL
        }
        val bubble = RoundedPanel(FILL, null, 10, BubbleCorner.NONE)
        outer.add(bubble)
        pane = NativeMarkdownPane(FileNavigator(project))
        pane.setCompleteMarkdown("Inline `code` in a bubble.")
        bubble.add(pane)
        pane.setBounds(0, 0, 240, 60)
    }

    override fun tearDown() {
        try {
            pane.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun testFrameIsOpaqueAndFilledWithTheColourBehindThePane() {
        assertEquals(PaneBackdrop.over(FILL, PANEL).rgb, cornerPixel())
        assertEquals("subpixel text needs an opaque frame", BufferedImage.TYPE_INT_RGB, pane.frameCache.image?.type)
    }

    fun testFrameIsRedrawnWhenTheColourBehindThePaneChanges() {
        cornerPixel()
        outer.background = Color.WHITE
        assertEquals(PaneBackdrop.over(FILL, Color.WHITE).rgb, cornerPixel())
    }

    fun testStaleFrameIsNotReusedOverAnotherColour() {
        cornerPixel()
        outer.background = Color.WHITE
        pane.setBounds(0, 0, 300, 60)
        assertEquals(PaneBackdrop.over(FILL, Color.WHITE).rgb, cornerPixel())
    }

    fun testFrameIsRedrawnWhenTheColourChangesDuringAResizeBurst() {
        try {
            cornerPixel()
            outer.background = Color.WHITE
            ResizeBurstClock.tick()
            assertEquals(PaneBackdrop.over(FILL, Color.WHITE).rgb, cornerPixel())
        } finally {
            waitOnEdt(BURST_OVER_MS)
        }
    }

    fun testFrameStaysTranslucentWithoutAnOpaqueAncestor() {
        outer.isOpaque = false
        assertEquals(0, cornerPixel() ushr 24)
        assertEquals(BufferedImage.TYPE_INT_ARGB, pane.frameCache.image?.type)
    }

    /** Paints the pane onto a transparent image and returns the ARGB of its top-left pixel, which has no text. */
    private fun cornerPixel(): Int {
        val image = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            pane.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0)
    }

    private companion object {
        val PANEL = Color(25, 26, 28)
        val FILL = Color(150, 200, 150, 51)

        /** Longer than [ResizeBurstClock]'s 200 ms window, so no burst outlives the test. */
        const val BURST_OVER_MS = 250
    }
}

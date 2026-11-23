package com.github.catatafishen.agentbridge.ui

import java.awt.image.BufferedImage

/**
 * The last frame a [NativeMarkdownPane] rendered, with the size, content version and backdrop
 * (see [PaneBackdrop]; null for a translucent frame) it was rendered for.
 */
internal class PaneFrameCache {

    var image: BufferedImage? = null
        private set
    private var width = -1
    private var height = -1
    private var version = -1
    private var backdrop: Int? = null

    /** True when the frame was rendered for exactly this state. */
    fun matches(w: Int, h: Int, version: Int, backdrop: Int?): Boolean =
        fits(w, h, backdrop) && width == w && height == h && this.version == version

    /**
     * True when the frame can stand in for a [w] × [h] paint over [backdrop]: it is no wider than the
     * pane (so no text is cropped), no shorter (so no blank bottom), and drawn over the same backdrop.
     */
    fun fits(w: Int, h: Int, backdrop: Int?): Boolean =
        image != null && width <= w && height >= h && this.backdrop == backdrop

    fun isOfVersion(version: Int): Boolean = image != null && this.version == version

    fun store(image: BufferedImage, w: Int, h: Int, version: Int, backdrop: Int?) {
        this.image = image
        width = w
        height = h
        this.version = version
        this.backdrop = backdrop
    }

    fun clear() {
        image = null
    }
}

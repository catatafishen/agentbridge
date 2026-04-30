package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link PasteAttachmentHandler}'s static helpers. No IDE / Project
 * fixture is required — these cover the encoding, conversion, and naming logic that
 * runs on the pool thread when a paste is intercepted.
 */
class PasteAttachmentHandlerTest {

    @Test
    void encodeAsPng_roundTripsThroughImageIO() throws Exception {
        BufferedImage original = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = original.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, 8, 4);
        } finally {
            g.dispose();
        }

        byte[] bytes = PasteAttachmentHandler.Companion.encodeAsPng(original);

        assertNotNull(bytes);
        assertTrue(bytes.length > 8, "PNG payload should be non-trivial");
        // PNG magic header
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 0x50, bytes[1]); // 'P'
        assertEquals((byte) 0x4E, bytes[2]); // 'N'
        assertEquals((byte) 0x47, bytes[3]); // 'G'

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(decoded, "PNG bytes must decode back via ImageIO");
        assertEquals(8, decoded.getWidth());
        assertEquals(4, decoded.getHeight());
    }

    @Test
    void toBufferedImage_returnsSameInstanceWhenAlreadyBuffered() {
        BufferedImage original = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = PasteAttachmentHandler.Companion.toBufferedImage(original);
        assertSame(original, result);
    }

    @Test
    void toBufferedImage_convertsArbitraryImage() {
        Image volatileImage = new BufferedImage(5, 7, BufferedImage.TYPE_INT_RGB)
                .getScaledInstance(5, 7, Image.SCALE_FAST);
        BufferedImage result = PasteAttachmentHandler.Companion.toBufferedImage(volatileImage);
        assertEquals(5, result.getWidth());
        assertEquals(7, result.getHeight());
    }

    @Test
    void uniqueTarget_returnsRequestedNameWhenFree(@TempDir Path dir) {
        Path target = PasteAttachmentHandler.Companion.uniqueTarget(dir, "screenshot.png");
        assertEquals(dir.resolve("screenshot.png"), target);
    }

    @Test
    void uniqueTarget_appendsSuffixOnCollision(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("screenshot.png"));
        Path target = PasteAttachmentHandler.Companion.uniqueTarget(dir, "screenshot.png");
        assertEquals(dir.resolve("screenshot-1.png"), target);
    }

    @Test
    void uniqueTarget_incrementsSuffixUntilFree(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("file.bin"));
        Files.createFile(dir.resolve("file-1.bin"));
        Files.createFile(dir.resolve("file-2.bin"));
        Path target = PasteAttachmentHandler.Companion.uniqueTarget(dir, "file.bin");
        assertEquals(dir.resolve("file-3.bin"), target);
    }

    @Test
    void uniqueTarget_handlesNamesWithoutExtension(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("README"));
        Path target = PasteAttachmentHandler.Companion.uniqueTarget(dir, "README");
        assertEquals(dir.resolve("README-1"), target);
    }

    @Test
    void uniqueTarget_sanitizesUnsafeCharacters(@TempDir Path dir) {
        Path target = PasteAttachmentHandler.Companion.uniqueTarget(dir, "weird name?*.png");
        assertEquals(dir.resolve("weird_name__.png"), target);
    }

    @Test
    void uniqueTarget_fallsBackToPastedForEmptyName(@TempDir Path dir) {
        Path target = PasteAttachmentHandler.Companion.uniqueTarget(dir, "");
        assertEquals(dir.resolve("pasted"), target);
    }
}

package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link AttachmentKind#isImageFileName(String)}. No IDE / Project
 * fixture is required. {@link AttachmentKind#forFile} additionally depends on
 * {@code VirtualFile.getFileType().isBinary()}, which needs a platform test fixture and is
 * covered indirectly by the attach-file / drag-and-drop flows that call it.
 */
class AttachmentKindTest {

    @Test
    void isImageFileName_recognizesCommonRasterExtensions() {
        assertTrue(AttachmentKind.Companion.isImageFileName("screenshot.png"));
        assertTrue(AttachmentKind.Companion.isImageFileName("photo.JPG"));
        assertTrue(AttachmentKind.Companion.isImageFileName("photo.jpeg"));
        assertTrue(AttachmentKind.Companion.isImageFileName("anim.gif"));
        assertTrue(AttachmentKind.Companion.isImageFileName("scan.bmp"));
        assertTrue(AttachmentKind.Companion.isImageFileName("icon.webp"));
        assertTrue(AttachmentKind.Companion.isImageFileName("scan.tiff"));
        assertTrue(AttachmentKind.Companion.isImageFileName("scan.tif"));
    }

    @Test
    void isImageFileName_rejectsNonImageExtensions() {
        assertFalse(AttachmentKind.Companion.isImageFileName("notes.txt"));
        assertFalse(AttachmentKind.Companion.isImageFileName("report.pdf"));
        assertFalse(AttachmentKind.Companion.isImageFileName("Main.java"));
        assertFalse(AttachmentKind.Companion.isImageFileName("noextension"));
    }

    @Test
    void isImageFileName_ignoresDirectoryPrefixCaseAndPicksLastExtension() {
        assertTrue(AttachmentKind.Companion.isImageFileName("archive.tar.PNG"));
        assertFalse(AttachmentKind.Companion.isImageFileName(""));
    }
}

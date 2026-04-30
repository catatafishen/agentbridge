package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Handles pasting non-text clipboard content (raster images, binary files) into the
 * chat prompt. Saves the bytes/files into the AgentBridge storage directory's `pastes/`
 * subfolder and inserts an inline context chip referencing the saved file.
 *
 * Image attachments are also encoded as inline base64 [com.github.catatafishen.agentbridge.acp.model.ContentBlock.Image]
 * blocks at prompt-send time (see [PromptContextManager.buildPromptAttachments]).
 */
internal class PasteAttachmentHandler(
    private val project: Project,
    private val promptTextArea: EditorTextField,
    private val contextManager: PromptContextManager,
) {
    private val log = Logger.getInstance(PasteAttachmentHandler::class.java)

    /**
     * Save [image] as a PNG into the storage `pastes/` directory and insert an IMAGE chip
     * at the current caret. Returns true on success so callers can know whether they
     * actually consumed the paste.
     */
    fun handleImagePaste(image: Image): Boolean {
        return try {
            val buffered = toBufferedImage(image)
            val pngBytes = encodeAsPng(buffered)
            val target = uniqueTarget(pastesDir(), defaultImageName())
            Files.write(target, pngBytes)
            refreshAndInsertChip(target, AttachmentKind.IMAGE)
            true
        } catch (e: IOException) {
            log.warn("Failed to save pasted image", e)
            false
        } catch (e: RuntimeException) {
            log.warn("Failed to save pasted image", e)
            false
        }
    }

    /**
     * Copy each pasted file into the storage `pastes/` directory and insert one chip per
     * file. The chip's [AttachmentKind] is [AttachmentKind.IMAGE] for raster image file
     * types and [AttachmentKind.BINARY] for everything else.
     *
     * Returns true if at least one file was copied successfully.
     */
    fun handleFilePaste(files: List<File>): Boolean {
        if (files.isEmpty()) return false
        var any = false
        for (source in files) {
            val saved = copySingleFile(source) ?: continue
            val kind = if (isImageFile(source.name)) AttachmentKind.IMAGE else AttachmentKind.BINARY
            refreshAndInsertChip(saved, kind)
            any = true
        }
        return any
    }

    private fun copySingleFile(source: File): Path? {
        return try {
            if (!source.exists() || !source.isFile) {
                log.warn("Pasted file does not exist or is not a regular file: ${source.path}")
                return null
            }
            val target = uniqueTarget(pastesDir(), source.name)
            Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (e: IOException) {
            log.warn("Failed to copy pasted file ${source.path}", e)
            null
        }
    }

    private fun pastesDir(): Path {
        val root = AgentBridgeStorageSettings.getInstance().getProjectStorageDir(project)
        val pastes = root.resolve(PASTES_SUBDIR)
        Files.createDirectories(pastes)
        return pastes
    }

    private fun refreshAndInsertChip(file: Path, kind: AttachmentKind) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
            val fileTypeName = vf?.fileType?.name
                ?: FileTypeManager.getInstance().getFileTypeByFileName(file.fileName.toString()).name
            val data = ContextItemData(
                path = file.toAbsolutePath().toString(),
                name = file.fileName.toString(),
                startLine = 1,
                endLine = 0,
                fileTypeName = fileTypeName,
                isSelection = false,
                attachmentKind = kind,
            )
            val editor = promptTextArea.editor as? EditorEx ?: return@invokeLater
            contextManager.insertInlineChip(editor, data)
        }
    }

    private fun defaultImageName(): String {
        val ts = LocalDateTime.now().format(TIMESTAMP_FORMAT)
        return "screenshot-$ts.png"
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    companion object {
        private const val PASTES_SUBDIR = "pastes"
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        private val IMAGE_EXTENSIONS = setOf(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tiff", ".tif",
        )

        /**
         * Convert any [Image] (e.g. the one returned by `Toolkit.systemClipboard`) into a
         * [BufferedImage] suitable for [ImageIO]. Pure: no IO, no UI dependencies.
         * Visible for testing.
         */
        fun toBufferedImage(image: Image): BufferedImage {
            if (image is BufferedImage) return image
            val width = image.getWidth(null).coerceAtLeast(1)
            val height = image.getHeight(null).coerceAtLeast(1)
            // Use raw BufferedImage (not UIUtil.createImage): we want the clipboard's
            // actual pixel data unscaled for HiDPI — UIUtil's HiDPI image would multiply
            // dimensions by the system scale and skew the saved file.
            @Suppress("UndesirableClassUsage")
            val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = buffered.createGraphics()
            try {
                g.drawImage(image, 0, 0, null)
            } finally {
                g.dispose()
            }
            return buffered
        }

        /** Encode a [BufferedImage] as PNG bytes. Visible for testing. */
        fun encodeAsPng(image: BufferedImage): ByteArray {
            val baos = ByteArrayOutputStream()
            if (!ImageIO.write(image, "PNG", baos)) {
                throw IOException("No PNG writer available for the given image")
            }
            return baos.toByteArray()
        }

        /**
         * Resolve a non-colliding target path in [dir] for [requestedName]. If a file with
         * the same name already exists, appends `-1`, `-2`, ... before the extension until
         * an unused name is found. Visible for testing.
         */
        fun uniqueTarget(dir: Path, requestedName: String): Path {
            val safe = sanitizeFileName(requestedName)
            val first = dir.resolve(safe)
            if (!Files.exists(first)) return first
            val dot = safe.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) safe.substring(0, dot) to safe.substring(dot)
            else safe to ""
            var i = 1
            while (true) {
                val candidate = dir.resolve("$stem-$i$ext")
                if (!Files.exists(candidate)) return candidate
                i++
            }
        }

        private fun sanitizeFileName(name: String): String {
            val cleaned = name.map { c ->
                if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
            }.joinToString("")
            return cleaned.ifEmpty { "pasted" }
        }
    }
}

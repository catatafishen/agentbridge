package com.github.catatafishen.agentbridge.ui

/**
 * Kind of attachment a context chip refers to.
 *
 * - [TEXT]   — a text file or selection; content is read as text and sent as a
 *              `Resource` link with embedded text.
 * - [IMAGE]  — a raster image (e.g. pasted screenshot) on disk; sent inline as a
 *              base64 `Image` content block (standard ACP format) so vision-capable
 *              agents can consume it directly.
 * - [BINARY] — any other binary file (PDF, archive, etc.) on disk; sent as a
 *              `Resource` link with mime type but without inline content.
 * - [PROMPT] — a reference to a historical prompt turn from the conversation
 *              database. Doesn't map to a file on disk — the full turn details
 *              (prompt, agent response, tool calls, stats, commits) are carried
 *              inline on [ContextItemData.inlineText] and sent as a `Resource`
 *              link with an `agentbridge://prompt/<id>` URI.
 */
enum class AttachmentKind {
    TEXT,
    IMAGE,
    BINARY,
    PROMPT;

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tiff", "tif")

        /** True if [fileName]'s extension indicates a raster image format sent as [IMAGE]. */
        fun isImageFileName(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }

        /**
         * Classify [file] for attachment purposes so callers that build a [ContextItemData]
         * from a [com.intellij.openapi.vfs.VirtualFile] (attach-current-file, file search,
         * drag-and-drop) don't default to [TEXT] for non-text files. Without this, images and
         * other binary files silently fail to load a [PromptAttachment] later — the chip and
         * file path are still shown in the UI, but no content ever reaches the agent, because
         * [TEXT]'s content path requires an IntelliJ [com.intellij.openapi.editor.Document],
         * which doesn't exist for binary files.
         *
         * Raster images become [IMAGE] (sent inline as base64); other binary file types become
         * [BINARY] (sent as a resource link only, no inline content); everything else defaults
         * to [TEXT].
         */
        fun forFile(file: com.intellij.openapi.vfs.VirtualFile): AttachmentKind = when {
            isImageFileName(file.name) -> IMAGE
            file.fileType.isBinary -> BINARY
            else -> TEXT
        }
    }
}

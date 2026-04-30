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
 */
enum class AttachmentKind {
    TEXT,
    IMAGE,
    BINARY,
}

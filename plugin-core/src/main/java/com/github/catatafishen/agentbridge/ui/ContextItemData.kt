package com.github.catatafishen.agentbridge.ui

/**
 * Data associated with an inline context chip.
 * Mirrors the fields of the old `ContextItem` but is a standalone data class
 * so the renderer can carry it without depending on the tool window's private types.
 *
 * [attachmentKind] selects how the chip is rendered and how the attachment is encoded
 * when building ACP content blocks for the prompt. Defaults to [AttachmentKind.TEXT]
 * for backwards compatibility with existing call-sites.
 */
data class ContextItemData @JvmOverloads constructor(
    val path: String,
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val fileTypeName: String?,
    val isSelection: Boolean,
    val attachmentKind: AttachmentKind = AttachmentKind.TEXT,
    /**
     * Inline text content carried by chips that do not map to a file on disk
     * — currently only [AttachmentKind.PROMPT], which serializes a full turn
     * (prompt + response + tool calls + stats) into this field at chip-creation
     * time. `null` for file-backed attachments, which read their content from
     * disk when the prompt is sent.
     */
    val inlineText: String? = null,
)

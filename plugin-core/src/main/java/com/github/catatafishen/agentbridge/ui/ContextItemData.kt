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
)

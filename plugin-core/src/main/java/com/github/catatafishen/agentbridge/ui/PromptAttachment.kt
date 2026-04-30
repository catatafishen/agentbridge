package com.github.catatafishen.agentbridge.ui

/**
 * Typed view of an attachment that has been resolved against the file system and is
 * ready to be turned into an ACP `ContentBlock` when sending the prompt.
 *
 * The three variants map to the three ACP content-block types we currently emit:
 *
 * - [TextRef]   → `ContentBlock.Resource(ResourceLink(uri, _, mime, text, _))`
 *                 with the file's text inlined (or an "// Selected lines …" snippet).
 * - [ImageRef]  → `ContentBlock.Image(base64, mime)` — proper inline ACP image.
 * - [BinaryRef] → `ContentBlock.Resource(ResourceLink(uri, name, mime, null, null))`
 *                 — file URI + mime type only; agents that support file links can pull
 *                 the bytes themselves.
 *
 * All variants carry [uri] so `PromptOrchestrator.addContextEntries` can render a
 * uniform "context files" entry in the chat history.
 */
sealed interface PromptAttachment {
    val uri: String
    val mimeType: String?
    val displayName: String

    data class TextRef(
        override val uri: String,
        override val mimeType: String?,
        override val displayName: String,
        val text: String,
    ) : PromptAttachment

    data class ImageRef(
        override val uri: String,
        override val mimeType: String,
        override val displayName: String,
        val base64Data: String,
    ) : PromptAttachment

    data class BinaryRef(
        override val uri: String,
        override val mimeType: String?,
        override val displayName: String,
    ) : PromptAttachment
}

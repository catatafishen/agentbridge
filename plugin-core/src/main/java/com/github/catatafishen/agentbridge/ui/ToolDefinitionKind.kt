package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.services.ToolDefinition

/**
 * Maps a [ToolDefinition.Kind] to the canonical CSS / chip kind string used everywhere
 * the agent UI needs to choose a kind color.
 *
 * This is the single classifier for `Kind` → kind-name. Both
 * [com.github.catatafishen.agentbridge.settings.ToolsConfigurable] (tool cards in
 * Settings) and [NativeChatPanel] (chat chips) call this, so the same `ToolDefinition`
 * is always painted with the same color in every surface.
 *
 * Mapping mirrors the doc comments on [ToolDefinition.Kind]:
 * - SEARCH → "search"
 * - EDIT, WRITE, DELETE, MOVE → "edit" (destructive file/state operations)
 * - EXECUTE → "execute"
 * - READ / OTHER / anything else → "read"
 *
 * The returned string feeds [NativeChatColors.kindColor], which resolves the actual
 * [java.awt.Color] honouring per-project user overrides in
 * [com.github.catatafishen.agentbridge.settings.McpServerSettings].
 */
fun ToolDefinition.cssKindName(): String = when (kind()) {
    ToolDefinition.Kind.SEARCH -> "search"
    ToolDefinition.Kind.EDIT,
    ToolDefinition.Kind.WRITE,
    ToolDefinition.Kind.DELETE,
    ToolDefinition.Kind.MOVE -> "edit"
    ToolDefinition.Kind.EXECUTE -> "execute"
    else -> "read"
}

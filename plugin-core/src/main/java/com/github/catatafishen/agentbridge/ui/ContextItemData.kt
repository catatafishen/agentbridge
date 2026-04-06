package com.github.catatafishen.agentbridge.ui

/**
 * Data associated with an inline context chip.
 * Mirrors the fields of the old `ContextItem` but is a standalone data class
 * so the renderer can carry it without depending on the tool window's private types.
 */
data class ContextItemData(
    val path: String,
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val fileTypeName: String?,
    val isSelection: Boolean
)

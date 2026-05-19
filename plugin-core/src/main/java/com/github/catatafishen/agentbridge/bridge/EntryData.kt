package com.github.catatafishen.agentbridge.bridge

import java.util.UUID

/**
 * Origin of a nudge message.
 *
 * [serialized] is the lowercase string used in JSON/DB/JS so it stays stable
 * even if enum name conventions change.
 */
enum class NudgeSource(val serialized: String) {
    /** Typed by the user in the nudge input area. */
    HUMAN("human"),

    /** Auto-generated because the agent used a native tool (bash, grep, read…) instead of an MCP equivalent. */
    NATIVE_TOOL_REPRIMAND("native_tool_reprimand"),

    /** Auto-generated because the agent misused an MCP tool (wrong tool for the job). */
    TOOL_ABUSE_REPRIMAND("tool_abuse_reprimand");

    /** True for any auto-generated reprimand source. */
    val isReprimand: Boolean get() = this == NATIVE_TOOL_REPRIMAND || this == TOOL_ABUSE_REPRIMAND

    companion object {
        /**
         * Converts a serialized string to a [NudgeSource].
         * Falls back to [NATIVE_TOOL_REPRIMAND] for the legacy `"reprimand"` value,
         * and to [HUMAN] for any other unknown value.
         */
        @JvmStatic
        fun fromSerialized(value: String): NudgeSource = when (value) {
            "reprimand" -> NATIVE_TOOL_REPRIMAND
            else -> entries.firstOrNull { it.serialized == value } ?: HUMAN
        }
    }
}

/** Named reference to a file shown in the prompt context area. */
data class ContextFileRef @JvmOverloads constructor(
    val name: String,
    val path: String,
    val line: Int = 0,
)

/** Named reference to a file (name + path). */
data class FileRef(val name: String, val path: String)

sealed class EntryData {
    abstract val entryId: String

    /** ISO 8601 timestamp; empty string when not applicable (TurnStats, ContextFiles, Status). */
    open val timestamp: String get() = ""

    data class Prompt @JvmOverloads constructor(
        val text: String,
        override val timestamp: String = "",
        val contextFiles: List<ContextFileRef>? = null,
        val id: String = "",
        override val entryId: String = id.ifEmpty { UUID.randomUUID().toString() },
    ) : EntryData()

    class Text @JvmOverloads constructor(
        var raw: String = "",
        override val timestamp: String = "",
        val agent: String = "",
        val model: String = "",
        override val entryId: String = UUID.randomUUID().toString()
    ) : EntryData()

    class Thinking @JvmOverloads constructor(
        var raw: String = "",
        override val timestamp: String = "",
        val agent: String = "",
        val model: String = "",
        override val entryId: String = UUID.randomUUID().toString()
    ) : EntryData()

    class ToolCall @JvmOverloads constructor(
        val title: String,
        val arguments: String? = null,
        var kind: String = "other",
        var result: String? = null,
        var status: String? = null,
        var description: String? = null,
        var filePath: String? = null,
        var autoDenied: Boolean = false,
        var denialReason: String? = null,
        var pluginTool: String? = null,
        override val timestamp: String = "",
        val agent: String = "",
        val model: String = "",
        override val entryId: String = UUID.randomUUID().toString()
    ) : EntryData() {
        /** Set by MCP protocol on completion; null = not yet executed via MCP. */
        var isMcp: Boolean? = null
        var inputSizeBytes: Long = 0
        var outputSizeBytes: Long = 0
        var durationMs: Long = 0
        var acpName: String? = null
    }

    class SubAgent @JvmOverloads constructor(
        val agentType: String,
        val description: String,
        val prompt: String? = null,
        var result: String? = null,
        var status: String? = null,
        var colorIndex: Int = 0,
        val callId: String? = null,
        var autoDenied: Boolean = false,
        var denialReason: String? = null,
        override val timestamp: String = "",
        val agent: String = "",
        val model: String = "",
        override val entryId: String = UUID.randomUUID().toString()
    ) : EntryData()

    data class TurnStats @JvmOverloads constructor(
        val turnId: String,
        val durationMs: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val costUsd: Double = 0.0,
        val toolCallCount: Int = 0,
        val linesAdded: Int = 0,
        val linesRemoved: Int = 0,
        val model: String = "",
        val multiplier: String = "",
        val totalDurationMs: Long = 0,
        val totalInputTokens: Long = 0,
        val totalOutputTokens: Long = 0,
        val totalCostUsd: Double = 0.0,
        val totalToolCalls: Int = 0,
        val totalLinesAdded: Int = 0,
        val totalLinesRemoved: Int = 0,
        override val timestamp: String = "",
        override val entryId: String = UUID.randomUUID().toString(),
        /** Hashes of git commits made during this prompt turn (best-effort: startHash..HEAD range). */
        val commitHashes: List<String> = emptyList(),
        /** Git branch active when the prompt was submitted. Null when git is unavailable. */
        val gitBranchAtStart: String? = null,
        /** Git branch at turn end (fallback when at_start is missing). Null when git is unavailable. */
        val gitBranchAtEnd: String? = null,
    ) : EntryData()

    data class ContextFiles @JvmOverloads constructor(
        val files: List<FileRef>,
        override val entryId: String = UUID.randomUUID().toString()
    ) : EntryData()

    data class Status @JvmOverloads constructor(
        val icon: String,
        val message: String,
        override val entryId: String = UUID.randomUUID().toString()
    ) : EntryData()

    data class SessionSeparator @JvmOverloads constructor(
        override val timestamp: String,
        val agent: String = "",
        override val entryId: String = UUID.randomUUID().toString()
    ) : EntryData()

    data class Nudge @JvmOverloads constructor(
        val text: String,
        val id: String,
        val sent: Boolean = false,
        override val timestamp: String = "",
        override val entryId: String = UUID.randomUUID().toString(),
        /** Origin of this nudge — [NudgeSource.HUMAN] for user-typed nudges, [NudgeSource.NATIVE_TOOL_REPRIMAND] or [NudgeSource.TOOL_ABUSE_REPRIMAND] for auto-generated corrections. Defaults to [NudgeSource.HUMAN] for pre-existing records. */
        val source: NudgeSource = NudgeSource.HUMAN,
    ) : EntryData()
}

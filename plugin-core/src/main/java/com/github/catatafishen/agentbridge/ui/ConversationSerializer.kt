package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.session.v2.EntryDataJsonAdapter
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * **V1 format — deprecated.** Retained only for reading legacy conversations
 * and for the dual-write path until V1 is fully removed.
 *
 * New code should use V2 ([EntryDataJsonAdapter]) as the canonical persistence format.
 *
 * Deserialises [EntryData] lists from the V1 on-disk JSON array schema.
 * Neither file I/O nor rendering is performed here.
 */
internal object ConversationSerializer {

    // ── Deserialise ───────────────────────────────────────────────────────────

    fun deserialize(json: String): List<EntryData> {
        val arr = try {
            JsonParser.parseString(json).asJsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        val result = mutableListOf<EntryData>()
        for (el in arr) {
            val entry = fromJson(el.asJsonObject) ?: continue
            result.add(entry)
        }
        return result
    }

    fun fromJson(obj: JsonObject): EntryData? {
        val eid = obj["eid"]?.asString ?: ""
        return when (obj["type"]?.asString) {
            EntryDataJsonAdapter.TYPE_PROMPT -> {
                val ctxFiles = obj["ctxFiles"]?.asJsonArray?.map { f ->
                    val fo = f.asJsonObject
                    ContextFileRef(fo["name"]?.asString ?: "", fo["path"]?.asString ?: "", fo["line"]?.asInt ?: 0)
                }
                val id = obj["id"]?.asString ?: ""
                EntryData.Prompt(
                    obj["text"]?.asString ?: "",
                    obj["ts"]?.asString ?: "",
                    ctxFiles,
                    id,
                    entryId = eid.ifEmpty { id.ifEmpty { java.util.UUID.randomUUID().toString() } }
                )
            }

            EntryDataJsonAdapter.TYPE_TEXT -> EntryData.Text(
                obj["raw"]?.asString ?: "",
                obj["ts"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            EntryDataJsonAdapter.TYPE_THINKING -> EntryData.Thinking(
                obj["raw"]?.asString ?: "",
                obj["ts"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            EntryDataJsonAdapter.TYPE_TOOL -> EntryData.ToolCall(
                obj["title"]?.asString ?: "",
                obj["args"]?.asString,
                obj["kind"]?.asString ?: "other",
                obj["result"]?.asString,
                obj["status"]?.asString,
                obj["description"]?.asString,
                obj["filePath"]?.asString,
                obj["autoDenied"]?.asBoolean ?: false,
                obj["denialReason"]?.asString,
                obj["pluginTool"]?.asString ?: if (obj["mcpHandled"]?.asBoolean == true) (obj["title"]?.asString
                    ?: "") else null,
                obj["ts"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            EntryDataJsonAdapter.TYPE_SUBAGENT -> {
                val ci = obj["colorIndex"]?.asInt ?: 0
                EntryData.SubAgent(
                    obj["agentType"]?.asString ?: AGENT_TYPE_GENERAL,
                    obj["description"]?.asString ?: "",
                    obj["prompt"]?.asString?.ifEmpty { null },
                    obj["result"]?.asString?.ifEmpty { null },
                    obj["status"]?.asString?.ifEmpty { null } ?: "completed",
                    ci,
                    obj["callId"]?.asString,
                    obj["autoDenied"]?.asBoolean ?: false,
                    obj["denialReason"]?.asString,
                    obj["ts"]?.asString ?: "",
                    obj["agent"]?.asString ?: "",
                    entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
                )
            }

            EntryDataJsonAdapter.TYPE_CONTEXT -> {
                val files = mutableListOf<FileRef>()
                obj["files"]?.asJsonArray?.forEach { f ->
                    val fo = f.asJsonObject
                    files.add(FileRef(fo["name"]?.asString.orEmpty(), fo["path"]?.asString.orEmpty()))
                }
                EntryData.ContextFiles(files, entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() })
            }

            EntryDataJsonAdapter.TYPE_STATUS -> EntryData.Status(
                obj["icon"]?.asString ?: "ℹ",
                obj["message"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            EntryDataJsonAdapter.TYPE_SEPARATOR -> EntryData.SessionSeparator(
                obj["timestamp"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            EntryDataJsonAdapter.TYPE_TURN_STATS -> EntryData.TurnStats(
                turnId = obj["turnId"]?.asString ?: "",
                durationMs = obj["durationMs"]?.asLong ?: 0,
                inputTokens = obj["inputTokens"]?.asLong ?: 0,
                outputTokens = obj["outputTokens"]?.asLong ?: 0,
                costUsd = obj["costUsd"]?.asDouble ?: 0.0,
                toolCallCount = obj["toolCallCount"]?.asInt ?: 0,
                linesAdded = obj["linesAdded"]?.asInt ?: 0,
                linesRemoved = obj["linesRemoved"]?.asInt ?: 0,
                model = obj["model"]?.asString ?: "",
                multiplier = obj["multiplier"]?.asString ?: "",
                totalDurationMs = obj["totalDurationMs"]?.asLong ?: 0,
                totalInputTokens = obj["totalInputTokens"]?.asLong ?: 0,
                totalOutputTokens = obj["totalOutputTokens"]?.asLong ?: 0,
                totalCostUsd = obj["totalCostUsd"]?.asDouble ?: 0.0,
                totalToolCalls = obj["totalToolCalls"]?.asInt ?: 0,
                totalLinesAdded = obj["totalLinesAdded"]?.asInt ?: 0,
                totalLinesRemoved = obj["totalLinesRemoved"]?.asInt ?: 0,
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            else -> null
        }
    }
}

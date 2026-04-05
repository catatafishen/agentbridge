package com.github.catatafishen.ideagentforcopilot.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * **V1 format — deprecated.** Retained only for reading legacy conversations
 * and for the dual-write path until V1 is fully removed.
 *
 * New code should use V2 ([EntryDataConverter]) as the canonical persistence format.
 *
 * Serialises [EntryData] lists to the V1 on-disk JSON array schema and deserialises them back.
 * Neither file I/O nor rendering is performed here.
 */
internal object ConversationSerializer {

    // ── Serialise ─────────────────────────────────────────────────────────────

    fun serialize(entries: List<EntryData>): String {
        val arr = JsonArray()
        for (e in entries) arr.add(toJson(e))
        return arr.toString()
    }

    private fun toJson(e: EntryData): JsonObject {
        val obj = JsonObject()
        when (e) {
            is EntryData.Prompt -> {
                obj.addProperty("type", "prompt")
                obj.addProperty("text", e.text)
                if (e.id.isNotEmpty()) obj.addProperty("id", e.id)
                if (e.timestamp.isNotEmpty()) obj.addProperty("ts", e.timestamp)
                if (!e.contextFiles.isNullOrEmpty()) {
                    val fa = JsonArray()
                    e.contextFiles.forEach { (name, path, line) ->
                        val fo = JsonObject()
                        fo.addProperty("name", name)
                        fo.addProperty("path", path)
                        fo.addProperty("line", line)
                        fa.add(fo)
                    }
                    obj.add("ctxFiles", fa)
                }
            }

            is EntryData.Text -> {
                obj.addProperty("type", "text")
                obj.addProperty("raw", e.raw.toString())
                if (e.timestamp.isNotEmpty()) obj.addProperty("ts", e.timestamp)
                if (e.agent.isNotEmpty()) obj.addProperty("agent", e.agent)
            }

            is EntryData.Thinking -> {
                obj.addProperty("type", "thinking")
                obj.addProperty("raw", e.raw.toString())
                if (e.timestamp.isNotEmpty()) obj.addProperty("ts", e.timestamp)
                if (e.agent.isNotEmpty()) obj.addProperty("agent", e.agent)
            }

            is EntryData.ToolCall -> {
                obj.addProperty("type", "tool")
                obj.addProperty("title", e.title)
                obj.addProperty("args", e.arguments ?: "")
                obj.addProperty("kind", e.kind)
                if (!e.result.isNullOrEmpty()) obj.addProperty("result", e.result)
                if (!e.status.isNullOrEmpty()) obj.addProperty("status", e.status)
                if (e.autoDenied) obj.addProperty("autoDenied", true)
                if (!e.denialReason.isNullOrEmpty()) obj.addProperty("denialReason", e.denialReason)
                if (e.mcpHandled) obj.addProperty("mcpHandled", true)
                if (e.timestamp.isNotEmpty()) obj.addProperty("ts", e.timestamp)
                if (e.agent.isNotEmpty()) obj.addProperty("agent", e.agent)
            }

            is EntryData.SubAgent -> {
                obj.addProperty("type", "subagent")
                obj.addProperty("agentType", e.agentType)
                obj.addProperty("description", e.description)
                obj.addProperty("prompt", e.prompt ?: "")
                obj.addProperty("result", e.result ?: "")
                obj.addProperty("status", e.status ?: "")
                obj.addProperty("colorIndex", e.colorIndex)
                if (!e.callId.isNullOrEmpty()) obj.addProperty("callId", e.callId)
                if (e.autoDenied) obj.addProperty("autoDenied", true)
                if (!e.denialReason.isNullOrEmpty()) obj.addProperty("denialReason", e.denialReason)
                if (e.timestamp.isNotEmpty()) obj.addProperty("ts", e.timestamp)
                if (e.agent.isNotEmpty()) obj.addProperty("agent", e.agent)
            }

            is EntryData.ContextFiles -> {
                obj.addProperty("type", "context")
                val fa = JsonArray()
                e.files.forEach { (name, path) ->
                    val fo = JsonObject()
                    fo.addProperty("name", name)
                    fo.addProperty("path", path)
                    fa.add(fo)
                }
                obj.add("files", fa)
            }

            is EntryData.Status -> {
                obj.addProperty("type", "status")
                obj.addProperty("icon", e.icon)
                obj.addProperty("message", e.message)
            }

            is EntryData.SessionSeparator -> {
                obj.addProperty("type", "separator")
                obj.addProperty("timestamp", e.timestamp)
                obj.addProperty("agent", e.agent)
            }

            is EntryData.TurnStats -> {
                obj.addProperty("type", "turnStats")
                obj.addProperty("turnId", e.turnId)
                obj.addProperty("durationMs", e.durationMs)
                obj.addProperty("inputTokens", e.inputTokens)
                obj.addProperty("outputTokens", e.outputTokens)
                obj.addProperty("costUsd", e.costUsd)
                obj.addProperty("toolCallCount", e.toolCallCount)
                obj.addProperty("linesAdded", e.linesAdded)
                obj.addProperty("linesRemoved", e.linesRemoved)
                obj.addProperty("model", e.model)
                obj.addProperty("multiplier", e.multiplier)
                obj.addProperty("totalDurationMs", e.totalDurationMs)
                obj.addProperty("totalInputTokens", e.totalInputTokens)
                obj.addProperty("totalOutputTokens", e.totalOutputTokens)
                obj.addProperty("totalCostUsd", e.totalCostUsd)
                obj.addProperty("totalToolCalls", e.totalToolCalls)
                obj.addProperty("totalLinesAdded", e.totalLinesAdded)
                obj.addProperty("totalLinesRemoved", e.totalLinesRemoved)
            }
        }
        obj.addProperty("eid", e.entryId)
        return obj
    }

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
            "prompt" -> {
                val ctxFiles = obj["ctxFiles"]?.asJsonArray?.map { f ->
                    val fo = f.asJsonObject
                    Triple(fo["name"]?.asString ?: "", fo["path"]?.asString ?: "", fo["line"]?.asInt ?: 0)
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

            "text" -> EntryData.Text(
                StringBuilder(obj["raw"]?.asString ?: ""),
                obj["ts"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            "thinking" -> EntryData.Thinking(
                StringBuilder(obj["raw"]?.asString ?: ""),
                obj["ts"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            "tool" -> EntryData.ToolCall(
                obj["title"]?.asString ?: "",
                obj["args"]?.asString,
                obj["kind"]?.asString ?: "other",
                obj["result"]?.asString,
                obj["status"]?.asString,
                obj["description"]?.asString,
                obj["filePath"]?.asString,
                obj["autoDenied"]?.asBoolean ?: false,
                obj["denialReason"]?.asString,
                obj["mcpHandled"]?.asBoolean ?: false,
                obj["ts"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            "subagent" -> {
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

            "context" -> {
                val files = mutableListOf<Pair<String, String>>()
                obj["files"]?.asJsonArray?.forEach { f ->
                    val fo = f.asJsonObject
                    files.add(fo["name"]?.asString.orEmpty() to fo["path"]?.asString.orEmpty())
                }
                EntryData.ContextFiles(files, entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() })
            }

            "status" -> EntryData.Status(
                obj["icon"]?.asString ?: "ℹ",
                obj["message"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            "separator" -> EntryData.SessionSeparator(
                obj["timestamp"]?.asString ?: "",
                obj["agent"]?.asString ?: "",
                entryId = eid.ifEmpty { java.util.UUID.randomUUID().toString() }
            )

            "turnStats" -> EntryData.TurnStats(
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

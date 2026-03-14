package com.github.catatafishen.ideagentforcopilot.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Serialises [EntryData] lists to the on-disk JSON schema and deserialises them back.
 *
 * This is the single source of truth for the conversation persistence format.
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
            }

            is EntryData.Thinking -> {
                obj.addProperty("type", "thinking")
                obj.addProperty("raw", e.raw.toString())
            }

            is EntryData.ToolCall -> {
                obj.addProperty("type", "tool")
                obj.addProperty("title", e.title)
                obj.addProperty("args", e.arguments ?: "")
                obj.addProperty("kind", e.kind)
                if (!e.result.isNullOrEmpty()) obj.addProperty("result", e.result)
                if (!e.status.isNullOrEmpty()) obj.addProperty("status", e.status)
            }

            is EntryData.SubAgent -> {
                obj.addProperty("type", "subagent")
                obj.addProperty("agentType", e.agentType)
                obj.addProperty("description", e.description)
                obj.addProperty("prompt", e.prompt ?: "")
                obj.addProperty("result", e.result ?: "")
                obj.addProperty("status", e.status ?: "")
                obj.addProperty("colorIndex", e.colorIndex)
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
        }
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

    fun fromJson(obj: JsonObject): EntryData? = when (obj["type"]?.asString) {
        "prompt" -> {
            val ctxFiles = obj["ctxFiles"]?.asJsonArray?.map { f ->
                val fo = f.asJsonObject
                Triple(fo["name"]?.asString ?: "", fo["path"]?.asString ?: "", fo["line"]?.asInt ?: 0)
            }
            EntryData.Prompt(obj["text"]?.asString ?: "", obj["ts"]?.asString ?: "", ctxFiles)
        }

        "text" -> EntryData.Text(StringBuilder(obj["raw"]?.asString ?: ""))
        "thinking" -> EntryData.Thinking(StringBuilder(obj["raw"]?.asString ?: ""))

        "tool" -> EntryData.ToolCall(
            obj["title"]?.asString ?: "",
            obj["args"]?.asString,
            obj["kind"]?.asString ?: "other",
            obj["result"]?.asString,
            obj["status"]?.asString
        )

        "subagent" -> {
            val ci = obj["colorIndex"]?.asInt ?: 0
            EntryData.SubAgent(
                obj["agentType"]?.asString ?: AGENT_TYPE_GENERAL,
                obj["description"]?.asString ?: "",
                obj["prompt"]?.asString?.ifEmpty { null },
                obj["result"]?.asString?.ifEmpty { null },
                obj["status"]?.asString?.ifEmpty { null } ?: "completed",
                ci
            )
        }

        "context" -> {
            val files = mutableListOf<Pair<String, String>>()
            obj["files"]?.asJsonArray?.forEach { f ->
                val fo = f.asJsonObject
                files.add(fo["name"]?.asString.orEmpty() to fo["path"]?.asString.orEmpty())
            }
            EntryData.ContextFiles(files)
        }

        "status" -> EntryData.Status(obj["icon"]?.asString ?: "ℹ", obj["message"]?.asString ?: "")

        "separator" -> EntryData.SessionSeparator(
            obj["timestamp"]?.asString ?: "",
            obj["agent"]?.asString ?: ""
        )

        else -> null
    }
}

package com.github.catatafishen.agentbridge.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Parsed payload of an ACP permission request, ready for rendering by the chat UI.
 *
 * The raw `description` produced by [com.github.catatafishen.agentbridge.client.AbstractClient.PermissionPrompt.arguments]
 * is a JSON object of the form `{question?: string, args?: object}`. This helper extracts
 * the user-facing question (when present) and the flattened tool arguments so the UI can
 * render each piece on its own row without leaking raw JSON.
 *
 * Rendering rules:
 *  - If `question` is present and non-blank → [headline] is the question text.
 *  - Otherwise → [headline] is the literal string `"Permission requested"`.
 *  - [toolName] is always the display name supplied by the caller.
 *  - [args] contains one entry per top-level field of the `args` object; complex
 *    values (objects/arrays) are pretty-printed JSON, scalars are rendered as-is
 *    with strings wrapped in double quotes.
 */
data class PermissionRequestContent(
    val question: String?,
    val toolName: String,
    val args: List<Arg>,
) {
    data class Arg(val key: String, val value: String)

    companion object {
        const val DEFAULT_HEADLINE = "Permission requested"
        private val PRETTY = GsonBuilder().setPrettyPrinting().create()

        fun parse(toolDisplayName: String, description: String?): PermissionRequestContent {
            val payload = description?.takeIf { it.isNotBlank() }
            val parsed: JsonElement? = payload?.let {
                try {
                    JsonParser.parseString(it)
                } catch (_: Exception) {
                    null
                }
            }
            val obj = parsed?.takeIf { it.isJsonObject }?.asJsonObject

            val question = obj?.get("question")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf { it.isNotBlank() }

            val argsObj = obj?.get("args")?.takeIf { it.isJsonObject }?.asJsonObject

            val args = buildList {
                if (argsObj != null) {
                    for ((key, value) in argsObj.entrySet()) {
                        add(Arg(key, renderValue(value)))
                    }
                } else if (question == null && !payload.isNullOrBlank()) {
                    // Fall back to raw description as a single arg so users still see
                    // what was sent when the payload is not a {question, args} object.
                    add(Arg("description", payload))
                }
            }

            return PermissionRequestContent(
                question = question,
                toolName = toolDisplayName,
                args = args,
            )
        }

        private fun renderValue(value: JsonElement): String = when {
            value.isJsonNull -> "null"
            value.isJsonPrimitive -> {
                val p = value.asJsonPrimitive
                if (p.isString) "\"${p.asString}\"" else p.asString
            }

            else -> PRETTY.toJson(value)
        }
    }
}

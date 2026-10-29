package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** Visual emphasis of a chat button. Shared by reply and action buttons. */
enum class ButtonStyle {
    DEFAULT, PRIMARY, SUCCESS, WARNING, DANGER;

    companion object {
        val NAMES: List<String> = entries.map { it.name.lowercase() }

        /** `null` input means "not specified" → [DEFAULT]; an unknown name returns `null`. */
        fun parse(raw: String?): ButtonStyle? =
            if (raw == null) DEFAULT else entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/** Button whose click sends [text] to the agent as a new prompt (or just closes the strip if [dismiss]). */
data class ReplyButton(
    val label: String,
    val text: String,
    val style: ButtonStyle = ButtonStyle.DEFAULT,
    val dismiss: Boolean = false,
)

/** Button whose click runs [tool] with [arguments] directly, without involving the LLM. */
data class ActionButton(
    val label: String,
    val tool: String,
    val arguments: JsonObject,
    val style: ButtonStyle = ButtonStyle.DEFAULT,
)

/** Everything rendered under the end of an agent turn. [caption] is optional text above the buttons. */
data class ButtonStrip(
    val caption: String?,
    val replies: List<ReplyButton>,
    val actions: List<ActionButton>,
) {
    val isEmpty: Boolean get() = replies.isEmpty() && actions.isEmpty()
}

/** What the parser needs to know about the registered tools; abstracted so it is unit-testable. */
interface ToolCatalog {
    /** Input schema of the tool, or `null` if no such tool exists. */
    fun inputSchema(toolName: String): JsonObject?

    fun allToolNames(): Collection<String>

    /** True if the user's permission settings deny the tool outright. */
    fun isDenied(toolName: String): Boolean
}

/**
 * Parses and validates the `replies` / `actions` arguments of `prompt_user`.
 *
 * Validation collects **every** problem so that the agent can fix its call in one retry.
 * Nothing is silently dropped: any invalid button makes the whole call fail.
 */
object ButtonSpecParser {
    const val MAX_BUTTONS = 6
    const val MAX_LABEL_LENGTH = 30
    const val KEY_REPLIES = "replies"
    const val KEY_ACTIONS = "actions"

    /**
     * Previous name of [KEY_REPLIES] in the old `prompt_user` schema. Accepted silently as a tolerance for
     * clients that still hold a cached copy of that schema; it is not advertised in the tool definition and
     * can be removed once such clients are no longer a concern.
     */
    const val KEY_LEGACY_OPTIONS = "options"
    private const val SELF_TOOL = "prompt_user"

    class Result(val replies: List<ReplyButton>, val actions: List<ActionButton>, val errors: List<String>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    fun parse(args: JsonObject, catalog: ToolCatalog): Result {
        val errors = mutableListOf<String>()
        val labels = HashSet<String>()
        val replies = parseReplies(args, errors, labels)
        val actions = parseActions(args, catalog, errors, labels)
        val total = replies.size + actions.size
        if (total > MAX_BUTTONS) errors += "Too many buttons ($total); the maximum in total is $MAX_BUTTONS."
        return Result(replies, actions, errors)
    }

    /** Strips client-specific prefixes: `agentbridge/git_push` and `mcp__agentbridge__git_push` → `git_push`. */
    fun normalizeToolName(raw: String): String = raw.trim().substringAfterLast('/').substringAfterLast("__")

    // ── replies ──────────────────────────────────────────────────────────────

    private fun parseReplies(
        args: JsonObject,
        errors: MutableList<String>,
        labels: MutableSet<String>
    ): List<ReplyButton> {
        val key = if (args.has(KEY_REPLIES)) KEY_REPLIES else KEY_LEGACY_OPTIONS
        val el = args.get(key)
        if (el == null || el.isJsonNull) return emptyList()
        if (!el.isJsonArray) {
            errors += "$key must be an array."
            return emptyList()
        }
        val result = mutableListOf<ReplyButton>()
        for ((i, item) in el.asJsonArray.withIndex()) {
            val path = "$key[$i]"
            val button = when {
                item.isJsonPrimitive ->
                    checkLabel(path, item.asString, labels, errors, enforceLength = false)?.let { ReplyButton(it, it) }

                item.isJsonObject -> parseReplyObject(path, item.asJsonObject, labels, errors)
                else -> {
                    errors += "$path must be a string or an object with a \"label\"."
                    null
                }
            }
            if (button != null) result += button
        }
        return result
    }

    private fun parseReplyObject(
        path: String,
        obj: JsonObject,
        labels: MutableSet<String>,
        errors: MutableList<String>
    ): ReplyButton? {
        // Reply labels may be long: the UI shortens them with an ellipsis and shows the full text on hover.
        val label = checkLabel(path, obj.str("label"), labels, errors, enforceLength = false)
        val style = parseStyle(path, obj, errors)
        val dismiss = parseDismiss(path, obj, errors)
        if (label == null || style == null || dismiss == null) return null
        val text = obj.str("text")?.takeIf { it.isNotBlank() } ?: label
        return ReplyButton(label, text, style, dismiss)
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private fun parseActions(
        args: JsonObject, catalog: ToolCatalog, errors: MutableList<String>, labels: MutableSet<String>,
    ): List<ActionButton> {
        val el = args.get(KEY_ACTIONS)
        if (el == null || el.isJsonNull) return emptyList()
        if (!el.isJsonArray) {
            errors += "$KEY_ACTIONS must be an array."
            return emptyList()
        }
        val result = mutableListOf<ActionButton>()
        for ((i, item) in el.asJsonArray.withIndex()) {
            val path = "$KEY_ACTIONS[$i]"
            if (!item.isJsonObject) {
                errors += "$path must be an object: {\"label\":\"Push\",\"tool\":\"git_push\",\"arguments\":{}}."
                continue
            }
            parseActionObject(path, item.asJsonObject, catalog, labels, errors)?.let { result += it }
        }
        return result
    }

    private fun parseActionObject(
        path: String, obj: JsonObject, catalog: ToolCatalog, labels: MutableSet<String>, errors: MutableList<String>,
    ): ActionButton? {
        val label = checkLabel(path, obj.str("label"), labels, errors)
        val style = parseStyle(path, obj, errors)
        val tool = checkTool(path, obj.str("tool"), catalog, errors)
        val arguments = if (tool != null) parseArguments(path, obj, catalog, tool, errors) else null
        if (label == null || style == null || tool == null || arguments == null) return null
        return ActionButton(label, tool, arguments, style)
    }

    private fun checkTool(path: String, rawTool: String?, catalog: ToolCatalog, errors: MutableList<String>): String? {
        if (rawTool.isNullOrBlank()) {
            errors += "$path.tool is required, e.g. {\"label\":\"Push\",\"tool\":\"git_push\",\"arguments\":{}}."
            return null
        }
        val tool = normalizeToolName(rawTool)
        if (tool == SELF_TOOL) {
            errors += "$path.tool cannot be \"$SELF_TOOL\"."
            return null
        }
        if (catalog.inputSchema(tool) == null) {
            errors += "$path.tool \"$rawTool\" not found." + didYouMean(tool, catalog.allToolNames())
            return null
        }
        if (catalog.isDenied(tool)) {
            errors += "$path.tool \"$tool\" is denied by the user's permission settings."
            return null
        }
        return tool
    }

    private fun parseArguments(
        path: String,
        obj: JsonObject,
        catalog: ToolCatalog,
        tool: String,
        errors: MutableList<String>
    ): JsonObject? {
        val raw = obj.get("arguments")
        val arguments: JsonObject? = when {
            raw == null || raw.isJsonNull -> JsonObject()
            raw.isJsonObject -> raw.asJsonObject
            raw.isJsonPrimitive && raw.asJsonPrimitive.isString -> parseJsonObject(raw.asString)
            else -> null
        }
        if (arguments == null) {
            errors += "$path.arguments must be an object (or a JSON string encoding an object)."
            return null
        }
        val problems = missingRequired(catalog.inputSchema(tool), arguments)
        if (problems.isNotEmpty()) {
            errors += "$path.arguments for \"$tool\": $problems"
            return null
        }
        return arguments
    }

    private fun parseJsonObject(text: String): JsonObject? = try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: JsonParseException) {
        null
    }

    private fun missingRequired(schema: JsonObject?, arguments: JsonObject): String {
        val required = schema?.getAsJsonArray("required") ?: return ""
        val missing =
            required.filter { it.isJsonPrimitive }.map { it.asString }.filter { arguments.get(it)?.isJsonNull != false }
        if (missing.isEmpty()) return ""
        val props = schema.getAsJsonObject("properties")
        val described = props?.keySet()?.joinToString(", ") { name ->
            name + ":" + (props.getAsJsonObject(name)?.get("type")?.asString ?: "any")
        }
        return "missing required ${missing.joinToString(", ") { "\"$it\"" }}" +
            (if (described.isNullOrEmpty()) "" else " (parameters: $described)")
    }

    // ── shared ───────────────────────────────────────────────────────────────

    private fun checkLabel(
        path: String,
        label: String?,
        labels: MutableSet<String>,
        errors: MutableList<String>,
        enforceLength: Boolean = true,
    ): String? {
        val trimmed = label?.trim()
        return when {
            trimmed.isNullOrEmpty() -> {
                errors += "$path.label is required."
                null
            }

            enforceLength && trimmed.length > MAX_LABEL_LENGTH -> {
                errors += "$path.label is too long (${trimmed.length} chars, max $MAX_LABEL_LENGTH). Use a short label."
                null
            }

            !labels.add(trimmed.lowercase()) -> {
                errors += "$path.label \"$trimmed\" is a duplicate; labels must be unique."
                null
            }

            else -> trimmed
        }
    }

    private fun parseStyle(path: String, obj: JsonObject, errors: MutableList<String>): ButtonStyle? {
        val raw = obj.str("style")
        val style = ButtonStyle.parse(raw)
        if (style == null) errors += "$path.style \"$raw\" is invalid; use one of ${ButtonStyle.NAMES}."
        return style
    }

    private fun didYouMean(name: String, all: Collection<String>): String {
        val suggestions = all.filter { it.startsWith(name) || editDistance(it, name) <= 2 }
            .sortedBy { editDistance(it, name) }.take(3)
        return if (suggestions.isEmpty()) "" else " Did you mean ${suggestions.joinToString(" or ") { "\"$it\"" }}?"
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** Absent/null → false; a non-boolean value is reported as an error and returns `null`. */
    private fun parseDismiss(path: String, obj: JsonObject, errors: MutableList<String>): Boolean? {
        val el = obj.get("dismiss")
        if (el == null || el.isJsonNull) return false
        if (el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) return el.asBoolean
        errors += "$path.dismiss must be true or false."
        return null
    }
}

package com.github.catatafishen.agentbridge.attribution

import com.github.catatafishen.agentbridge.session.db.LineAttributionStore
import com.github.catatafishen.agentbridge.ui.sessions.SessionFormat

/** Text shown by the prompt annotation. Pure functions, no Swing or platform state. */
object AnnotationText {

    const val UNKNOWN_PROMPT = "AgentBridge prompt"
    private const val GUTTER_PROMPT_CHARS = 26

    /** Short gutter text: the prompt start and how long ago it ran; "~" marks inferred attribution. */
    fun gutterLabel(
        info: LineAttributionStore.TurnInfo?,
        owner: LineOwner,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val mark = if (owner.inferred) "~" else ""
        if (info == null) return "$mark…"
        val prompt = SessionFormat.truncate(info.prompt().ifBlank { UNKNOWN_PROMPT }, GUTTER_PROMPT_CHARS)
        val age = SessionFormat.relativeTime(info.startedAtMs(), nowMs)
        return if (age.isEmpty()) "$mark$prompt" else "$mark$prompt \u00b7 $age"
    }

    /** Tooltip with the full prompt and where it ran. */
    fun tooltipHtml(info: LineAttributionStore.TurnInfo?, owner: LineOwner): String {
        if (info == null) return "<html>Written by an AgentBridge prompt (details not available yet)</html>"
        val sb = StringBuilder("<html><b>").append(SessionFormat.escapeHtml(info.agentName()))
        if (!info.model().isNullOrBlank()) sb.append(" \u00b7 ").append(SessionFormat.escapeHtml(info.model()!!))
        sb.append("</b><br>").append(SessionFormat.escapeHtml(SessionFormat.dateTime(info.startedAtMs())))
        if (info.sessionName().isNotBlank()) {
            sb.append("<br>Session: ").append(SessionFormat.escapeHtml(SessionFormat.truncate(info.sessionName(), 80)))
        }
        sb.append("<br><br>").append(SessionFormat.escapeHtml(SessionFormat.truncate(info.prompt(), 600)))
        if (owner.kind == LineOwner.KIND_CREATE) sb.append("<br><br><i>Created this file</i>")
        if (owner.inferred) sb.append("<br><br><i>Inferred from recorded tool calls</i>")
        sb.append("<br><br><font color='gray'>Click to open this prompt in the conversation history</font></html>")
        return sb.toString()
    }

    /** True when [line] is the first line of a run of lines with the same owner. */
    fun startsRun(ownership: LineOwnership, line: Int): Boolean {
        val owner = ownership.ownerAt(line) ?: return false
        return line == 0 || ownership.ownerAt(line - 1)?.turnId != owner.turnId
    }

    /** Stable hue in [0,1) for a turn, so the same prompt always gets the same colour. */
    fun hueFor(turnId: String): Float = (Math.floorMod(turnId.hashCode(), 360)) / 360f
}

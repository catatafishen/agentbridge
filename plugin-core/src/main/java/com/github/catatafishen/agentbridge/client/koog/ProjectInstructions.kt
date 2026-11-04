package com.github.catatafishen.agentbridge.client.koog

import com.github.catatafishen.agentbridge.client.claude.InstructionsManager
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project's own agent instructions, `AGENTS.md` at the project root, for the Koog agent.
 *
 * CLI agents read this file themselves; Koog builds its prompt from bundled text only, so it would
 * otherwise never see the project's rules. Read at the start of every turn, so an edit applies to the
 * next message.
 */
object ProjectInstructions {

    const val FILE_NAME = "AGENTS.md"

    /** Upper bound on what is sent, so one huge file cannot crowd out the conversation. */
    const val MAX_CHARS = 32_000

    private val log = Logger.getInstance(ProjectInstructions::class.java)

    /** The prepared instructions in [projectRoot], or null when there is no readable, non-blank file. */
    @JvmStatic
    fun load(projectRoot: String): String? {
        val file = Path.of(projectRoot, FILE_NAME)
        if (!Files.isRegularFile(file)) return null
        val raw = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            log.warn("Koog: could not read $file, continuing without project instructions", e)
            return null
        }
        return prepare(raw)
    }

    /**
     * Drops the plugin's auto-generated block (another agent's profile may have written it into this
     * file, and Koog already gets those instructions), trims, and caps the length with a visible note.
     * Returns null when nothing is left.
     */
    @JvmStatic
    fun prepare(raw: String): String? {
        val text = InstructionsManager.stripGeneratedBlock(raw).trim()
        if (text.isEmpty()) return null
        if (text.length <= MAX_CHARS) return text
        return text.take(MAX_CHARS).trimEnd() +
            "\n\n[$FILE_NAME was truncated: only its first $MAX_CHARS characters are included]"
    }
}

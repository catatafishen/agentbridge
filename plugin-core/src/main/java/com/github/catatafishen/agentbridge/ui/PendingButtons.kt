package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Holds the buttons an agent registered with `prompt_user(wait=false)` until its turn ends,
 * plus short notes about actions the user ran, which are prepended to the next prompt so the
 * agent learns what happened without being triggered.
 *
 * The latest registration replaces any earlier one in the same turn.
 */
@Service(Service.Level.PROJECT)
class PendingButtons {

    @Volatile
    private var strip: ButtonStrip? = null
    private val notes = ConcurrentLinkedQueue<String>()

    fun set(newStrip: ButtonStrip) {
        strip = newStrip
    }

    /** Returns and clears the registered strip, or `null` if none. */
    fun take(): ButtonStrip? {
        val current = strip
        strip = null
        return current
    }

    fun clear() {
        strip = null
    }

    fun addNote(note: String) {
        notes.add(note)
    }

    /** Returns the notes in order and clears them. */
    fun drainNotes(): List<String> {
        val drained = mutableListOf<String>()
        while (true) drained += notes.poll() ?: break
        return drained
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PendingButtons = project.getService(PendingButtons::class.java)
    }
}

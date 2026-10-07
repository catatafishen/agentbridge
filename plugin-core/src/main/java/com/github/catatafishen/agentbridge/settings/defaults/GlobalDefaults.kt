package com.github.catatafishen.agentbridge.settings.defaults

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * The user's global defaults: for each section, the values saved from some project, kept at application level.
 *
 * Stored as one flat `sectionId|key -> value` map. It never roams through Settings Sync: it can hold machine
 * specific paths and the environment variables and headers of custom MCP servers, which may be credentials.
 */
@Service(Service.Level.APP)
@State(
    name = "AgentBridgeGlobalDefaults",
    storages = [Storage(value = "agentbridgeGlobalDefaults.xml", roamingType = RoamingType.DISABLED)],
)
class GlobalDefaults : PersistentStateComponent<GlobalDefaults.State> {

    class State {
        var entries: MutableMap<String, String> = LinkedHashMap()
    }

    // Replaced, never modified in place: the platform may be serializing the previous one on another thread.
    @Volatile
    private var current = State()

    override fun getState(): State = current

    override fun loadState(state: State) {
        current = state
    }

    fun entriesFor(sectionId: String): Map<String, String> {
        val prefix = prefixOf(sectionId)
        return current.entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    fun hasDefaults(sectionId: String): Boolean {
        val prefix = prefixOf(sectionId)
        return current.entries.keys.any { it.startsWith(prefix) }
    }

    /** Replaces everything saved for [sectionId], so a value that was dropped from the section is dropped here. */
    @Synchronized
    fun replace(sectionId: String, entries: Map<String, String>) {
        val prefix = prefixOf(sectionId)
        val next = LinkedHashMap(current.entries.filterKeys { !it.startsWith(prefix) })
        for ((key, value) in entries) next[prefix + key] = value
        current = State().also { it.entries = next }
    }

    @Synchronized
    fun clear(sectionId: String) = replace(sectionId, emptyMap())

    private fun prefixOf(sectionId: String) = sectionId + SEPARATOR

    companion object {
        private const val SEPARATOR = "|"

        @JvmStatic
        fun getInstance(): GlobalDefaults = ApplicationManager.getApplication().getService(GlobalDefaults::class.java)
    }
}

/**
 * Saves, applies and clears global defaults for a set of [sections]. Takes the sections and the store as
 * parameters so it can be tested with fakes; [DefaultsSections.engine] wires the real ones.
 */
class DefaultsEngine(val sections: List<DefaultsSection>, private val defaults: GlobalDefaults) {

    /** @param withoutDefaults the requested sections that have nothing saved, so were left alone */
    class ApplyResult(val applied: List<DefaultsSection>, val withoutDefaults: List<DefaultsSection>)

    fun hasDefaults(section: DefaultsSection): Boolean = defaults.hasDefaults(section.id)

    /** Saves [project]'s current values of [selected] as the global defaults, replacing what was saved. */
    fun save(project: Project, selected: Collection<DefaultsSection>) {
        for (section in selected) defaults.replace(section.id, section.capture(project))
    }

    /** Makes [project] match the global defaults for each of [selected] that has some. */
    fun apply(project: Project, selected: Collection<DefaultsSection>): ApplyResult {
        val (withDefaults, without) = selected.partition { defaults.hasDefaults(it.id) }
        for (section in withDefaults) section.apply(project, defaults.entriesFor(section.id))
        return ApplyResult(withDefaults, without)
    }

    fun clear(selected: Collection<DefaultsSection>) {
        for (section in selected) defaults.clear(section.id)
    }
}

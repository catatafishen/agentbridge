package com.github.catatafishen.agentbridge.settings.defaults

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.security.MessageDigest

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
 * Keeps projects in step with the global defaults. Takes the sections, the store and the per-project store of
 * bookkeeping as parameters so it can be tested with fakes; [DefaultsSections.engine] wires the real ones.
 *
 * A project **inherits** a section (the default for a new project) until that section is **overridden**:
 * - inheriting: the project follows the global defaults, picking up a change when it next opens or when the
 *   defaults are saved from another project;
 * - overriding: the project's own values are never touched by the defaults.
 *
 * A project that inherits a section but whose values were then changed by hand has diverged from what the
 * defaults gave it. That is an override the user made without saying so, and it is recorded as one rather than
 * silently thrown away at the next sync. Divergence is detected by comparing a fingerprint of the section's
 * values with the one taken when the defaults were last applied.
 */
class DefaultsEngine(
    val sections: List<DefaultsSection>,
    private val defaults: GlobalDefaults,
    private val projectStore: (Project) -> KeyValueStore = { PropertiesStore(PropertiesComponent.getInstance(it)) },
) {

    /** @param withoutDefaults the requested sections that have nothing saved, so were left alone */
    class ApplyResult(val applied: List<DefaultsSection>, val withoutDefaults: List<DefaultsSection>)

    /**
     * @param applied sections that were brought up to date with the defaults
     * @param diverged sections that were edited in the project since the last sync, and so are now overridden
     * @param failed sections that could not be synced, with why; the rest were still synced
     */
    class SyncResult(
        val applied: List<DefaultsSection>,
        val diverged: List<DefaultsSection>,
        val failed: Map<DefaultsSection, Exception>,
    )

    fun hasDefaults(section: DefaultsSection): Boolean = defaults.hasDefaults(section.id)

    fun isOverridden(project: Project, section: DefaultsSection): Boolean =
        projectStore(project).get(overrideKey(section.id)) == "true"

    /** Pins every section to what [project] has now: used for a project that was configured before the defaults. */
    fun overrideAll(project: Project) {
        val store = projectStore(project)
        for (section in sections) store.set(overrideKey(section.id), "true")
    }

    /**
     * Switches [section] between following the defaults and keeping the project's own values. Following them
     * takes effect now, when there are defaults to follow.
     */
    fun setOverridden(project: Project, section: DefaultsSection, overridden: Boolean) {
        val store = projectStore(project)
        store.unset(syncedKey(section.id))
        if (overridden) {
            store.set(overrideKey(section.id), "true")
            return
        }
        store.unset(overrideKey(section.id))
        if (defaults.hasDefaults(section.id)) applyInherited(project, store, section)
    }

    /**
     * Saves [project]'s current values of [selected] as the global defaults, replacing what was saved. The
     * project then inherits them, which it trivially matches.
     */
    fun save(project: Project, selected: Collection<DefaultsSection>) {
        val store = projectStore(project)
        for (section in selected) {
            defaults.replace(section.id, section.capture(project))
            store.unset(overrideKey(section.id))
            recordSynced(project, store, section)
        }
    }

    /** Makes [project] match the global defaults for each of [selected] that has some, and inherit them again. */
    fun apply(project: Project, selected: Collection<DefaultsSection>): ApplyResult {
        val (withDefaults, without) = selected.partition { defaults.hasDefaults(it.id) }
        val store = projectStore(project)
        for (section in withDefaults) {
            store.unset(overrideKey(section.id))
            applyInherited(project, store, section)
        }
        return ApplyResult(withDefaults, without)
    }

    /** Deletes the saved defaults for [selected]. Projects keep the values they have. */
    fun clear(selected: Collection<DefaultsSection>) {
        for (section in selected) defaults.clear(section.id)
    }

    /** Brings every section that [project] inherits up to date with the defaults. */
    fun sync(project: Project): SyncResult {
        val store = projectStore(project)
        val applied = ArrayList<DefaultsSection>()
        val diverged = ArrayList<DefaultsSection>()
        val failed = LinkedHashMap<DefaultsSection, Exception>()
        for (section in sections) {
            if (isOverridden(project, section)) continue
            try {
                when (syncSection(project, store, section)) {
                    Synced.APPLIED -> applied += section
                    Synced.DIVERGED -> diverged += section
                    Synced.UNCHANGED -> Unit
                }
            } catch (e: Exception) {
                failed[section] = e
            }
        }
        return SyncResult(applied, diverged, failed)
    }

    /** [sync] for every project in [projects]: the defaults were just saved and the open ones should follow. */
    fun syncAll(projects: Collection<Project>): Map<Project, SyncResult> = projects.associateWith { sync(it) }

    private enum class Synced { APPLIED, DIVERGED, UNCHANGED }

    private fun syncSection(project: Project, store: KeyValueStore, section: DefaultsSection): Synced {
        val synced = store.get(syncedKey(section.id))
        if (!defaults.hasDefaults(section.id)) {
            // Nothing to follow yet. Remember what the project has, so that an edit made before defaults exist
            // is still told apart from a value the defaults gave it.
            if (synced == null) store.set(syncedKey(section.id), NO_GLOBAL + SYNC_SEPARATOR + fingerprint(section.capture(project)))
            return Synced.UNCHANGED
        }
        if (synced != null) {
            val (globalPrint, projectPrint) = synced.split(SYNC_SEPARATOR, limit = 2)
            if (fingerprint(section.capture(project)) != projectPrint) {
                store.set(overrideKey(section.id), "true")
                return Synced.DIVERGED
            }
            if (globalPrint == fingerprint(defaults.entriesFor(section.id))) return Synced.UNCHANGED
        }
        applyInherited(project, store, section)
        return Synced.APPLIED
    }

    private fun applyInherited(project: Project, store: KeyValueStore, section: DefaultsSection) {
        section.apply(project, defaults.entriesFor(section.id))
        recordSynced(project, store, section)
    }

    private fun recordSynced(project: Project, store: KeyValueStore, section: DefaultsSection) {
        val globalPrint = fingerprint(defaults.entriesFor(section.id))
        store.set(syncedKey(section.id), globalPrint + SYNC_SEPARATOR + fingerprint(section.capture(project)))
    }

    private companion object {
        const val NO_GLOBAL = "-"
        const val SYNC_SEPARATOR = ":"

        fun overrideKey(sectionId: String) = "agentbridge.globalDefaults.override.$sectionId"
        fun syncedKey(sectionId: String) = "agentbridge.globalDefaults.synced.$sectionId"
    }
}

/** A short, order-independent digest of a section's values. */
internal fun fingerprint(entries: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for ((key, value) in entries.toSortedMap()) digest.update("$key\u0000$value\u0001".toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }.take(24)
}

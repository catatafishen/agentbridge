package com.github.catatafishen.agentbridge.settings.defaults

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import java.io.StringReader

/**
 * One group of per-project settings that can be saved as a global default and copied into other projects.
 *
 * A section's values are exchanged as a flat map of strings, which is also how the global defaults are stored,
 * so every kind of setting (a state bean, a set of properties) goes through the same save/apply/clear logic.
 */
interface DefaultsSection {
    val id: String
    val title: String

    /** What is in the section and what is deliberately left out, shown next to its checkbox. */
    val description: String

    /** The section's current values in [project]. */
    fun capture(project: Project): Map<String, String>

    /** Makes [project] match [entries], which came from [capture]. */
    fun apply(project: Project, entries: Map<String, String>)
}

/**
 * A section backed by a platform state bean ([PersistentStateComponent]), copied whole through the platform's own
 * XML serializer, so a field added to the bean later is covered without touching this code.
 *
 * @param keep copies the values that must stay what they are in the target project (a port, say) from the
 *   [current][keep] state onto the incoming one, before it is loaded
 */
class StateDefaultsSection<S : Any>(
    override val id: String,
    override val title: String,
    override val description: String,
    /** The file under `.idea` the component is stored in; its presence tells that a project was configured. */
    val storageFile: String,
    private val component: (Project) -> PersistentStateComponent<S>,
    private val stateClass: Class<S>,
    private val keep: (current: S, incoming: S) -> Unit = { _, _ -> },
) : DefaultsSection {

    override fun capture(project: Project): Map<String, String> {
        val state = component(project).state ?: error("Section '$id' has no state to save")
        return mapOf(XML_KEY to StateXml.write(state))
    }

    override fun apply(project: Project, entries: Map<String, String>) {
        val xml = entries[XML_KEY] ?: error("Section '$id' has no saved state to apply")
        val target = component(project)
        val incoming = StateXml.read(xml, stateClass)
        target.state?.let { keep(it, incoming) }
        target.loadState(incoming)
    }

    private companion object {
        const val XML_KEY = "xml"
    }
}

/**
 * A section backed by individual properties of the project's [PropertiesComponent].
 *
 * Properties cannot be listed, so the section names its [keys] explicitly. That list is the whole contract of what
 * is copied: runtime state kept in the same store (resume ids, counters, dismissed banners) is simply not named.
 */
class PropertyDefaultsSection(
    override val id: String,
    override val title: String,
    override val description: String,
    private val keys: (Project) -> Collection<String>,
    private val store: (Project) -> KeyValueStore = { PropertiesStore(PropertiesComponent.getInstance(it)) },
) : DefaultsSection {

    override fun capture(project: Project): Map<String, String> =
        PropertyEntries.capture(store(project), keys(project))

    override fun apply(project: Project, entries: Map<String, String>) =
        PropertyEntries.apply(store(project), keys(project), entries)
}

/** The part of [PropertiesComponent] the defaults use, so the logic can be tested without an IDE. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun unset(key: String)
}

class PropertiesStore(private val properties: PropertiesComponent) : KeyValueStore {
    override fun get(key: String): String? = properties.getValue(key)
    override fun set(key: String, value: String) = properties.setValue(key, value)
    override fun unset(key: String) = properties.unsetValue(key)
}

internal object PropertyEntries {

    /** The keys that have a value; a key with none is left out, so applying it later resets the key. */
    fun capture(store: KeyValueStore, keys: Collection<String>): Map<String, String> {
        val captured = LinkedHashMap<String, String>()
        for (key in keys) store.get(key)?.let { captured[key] = it }
        return captured
    }

    /**
     * Sets every saved value, and unsets the [keys] that have none, so the project ends up matching the defaults
     * rather than keeping a leftover value the defaults do not have. Saved values are set even for keys that
     * cannot be listed yet (tool permissions before the tool registry is filled at startup).
     */
    fun apply(store: KeyValueStore, keys: Collection<String>, entries: Map<String, String>) {
        for (key in keys) if (key !in entries) store.unset(key)
        for ((key, value) in entries) store.set(key, value)
    }
}

internal object StateXml {
    fun <S : Any> write(state: S): String = JDOMUtil.write(XmlSerializer.serialize(state))

    fun <S : Any> read(xml: String, stateClass: Class<S>): S =
        XmlSerializer.deserialize(JDOMUtil.load(StringReader(xml)), stateClass)
}

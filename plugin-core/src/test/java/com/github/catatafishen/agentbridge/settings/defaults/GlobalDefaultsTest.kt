package com.github.catatafishen.agentbridge.settings.defaults

import com.github.catatafishen.agentbridge.custommcp.CustomMcpSettings
import com.github.catatafishen.agentbridge.memory.MemorySettings
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.CleanupSettings
import com.github.catatafishen.agentbridge.services.GenericSettings
import com.github.catatafishen.agentbridge.settings.ChatHistorySettings
import com.github.catatafishen.agentbridge.settings.DiagnosticFilterSettings
import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class GlobalDefaultsTest {

    /** Sections here never touch the project, so a stand-in that is never called is enough. */
    private val project: Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, _, _ -> null } as Project

    private class MapStore(val values: MutableMap<String, String> = LinkedHashMap()) : KeyValueStore {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }

        override fun unset(key: String) {
            values.remove(key)
        }
    }

    class Bean {
        var port = 1
        var name = "default"
        var tags: MutableSet<String> = LinkedHashSet()
    }

    private class FakeComponent(var current: Bean = Bean()) : PersistentStateComponent<Bean> {
        override fun getState() = current
        override fun loadState(state: Bean) {
            current = state
        }
    }

    @Nested
    inner class Store {
        @Test
        fun `values saved for a section come back for that section only`() {
            val defaults = GlobalDefaults()

            defaults.replace("mcp", mapOf("a" to "1"))
            defaults.replace("mcp-server", mapOf("a" to "2"))

            assertEquals(mapOf("a" to "1"), defaults.entriesFor("mcp"))
            assertEquals(mapOf("a" to "2"), defaults.entriesFor("mcp-server"))
        }

        @Test
        fun `saving again replaces the section, so a value that was dropped is dropped`() {
            val defaults = GlobalDefaults()
            defaults.replace("s", mapOf("a" to "1", "b" to "2"))

            defaults.replace("s", mapOf("a" to "3"))

            assertEquals(mapOf("a" to "3"), defaults.entriesFor("s"))
        }

        @Test
        fun `clearing one section leaves the others`() {
            val defaults = GlobalDefaults()
            defaults.replace("s", mapOf("a" to "1"))
            defaults.replace("t", mapOf("b" to "2"))

            defaults.clear("s")

            assertFalse(defaults.hasDefaults("s"))
            assertTrue(defaults.hasDefaults("t"))
        }

        @Test
        fun `a section with nothing saved has no defaults`() {
            assertFalse(GlobalDefaults().hasDefaults("s"))
            assertTrue(GlobalDefaults().entriesFor("s").isEmpty())
        }

        @Test
        fun `what is saved survives being written to disk and read back`() {
            val defaults = GlobalDefaults()
            defaults.replace("mcp-server", mapOf("xml" to "<State>\n  <option name=\"port\" value=\"8642\" />\n</State>"))
            defaults.replace("agent", mapOf("agent.activeProfileId" to "claude-cli"))

            val reloaded = GlobalDefaults()
            reloaded.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(defaults.state), GlobalDefaults.State::class.java))

            assertEquals(defaults.entriesFor("mcp-server"), reloaded.entriesFor("mcp-server"))
            assertEquals(mapOf("agent.activeProfileId" to "claude-cli"), reloaded.entriesFor("agent"))
        }
    }

    @Nested
    inner class Properties {
        @Test
        fun `capturing leaves out the keys that have no value`() {
            val store = MapStore(mutableMapOf("a" to "1"))

            assertEquals(mapOf("a" to "1"), PropertyEntries.capture(store, listOf("a", "b")))
        }

        @Test
        fun `applying sets the saved values and resets the keys the defaults do not have`() {
            val store = MapStore(mutableMapOf("a" to "old", "b" to "leftover"))

            PropertyEntries.apply(store, listOf("a", "b", "c"), mapOf("a" to "new"))

            assertEquals(mapOf("a" to "new"), store.values)
        }

        @Test
        fun `a saved value is applied even when its key cannot be listed yet`() {
            // Tool permissions are listed from the tool registry, which is still empty early in startup.
            val store = MapStore()

            PropertyEntries.apply(store, emptyList(), mapOf("tool.perm.read_file" to "DENY"))

            assertEquals("DENY", store.get("tool.perm.read_file"))
        }
    }

    @Nested
    inner class StateSections {
        private fun section(component: FakeComponent, keep: (Bean, Bean) -> Unit = { _, _ -> }) =
            StateDefaultsSection(
                id = "bean", title = "Bean", description = "", storageFile = "bean.xml",
                component = { component }, stateClass = Bean::class.java, keep = keep,
            )

        @Test
        fun `a state is copied whole, including collections`() {
            val source = FakeComponent(Bean().apply { port = 9; name = "x"; tags.addAll(listOf("a", "b")) })
            val target = FakeComponent()

            section(target).apply(project, section(source).capture(project))

            assertEquals(9, target.current.port)
            assertEquals("x", target.current.name)
            assertEquals(setOf("a", "b"), target.current.tags)
        }

        @Test
        fun `what the section keeps stays what the target project had`() {
            val source = FakeComponent(Bean().apply { port = 9; name = "from-defaults" })
            val target = FakeComponent(Bean().apply { port = 5000 })
            val keepPort = { current: Bean, incoming: Bean -> incoming.port = current.port }

            section(target, keepPort).apply(project, section(source).capture(project))

            assertEquals(5000, target.current.port)
            assertEquals("from-defaults", target.current.name)
        }

        @Test
        fun `applying without saved state is an error, not a silent no-op`() {
            assertThrows(IllegalStateException::class.java) { section(FakeComponent()).apply(project, emptyMap()) }
        }

        @Test
        fun `the MCP port and static-port flag stay with the project`() {
            val current = McpServerSettings.State().apply { port = 8700; isStaticPort = true }
            val incoming = McpServerSettings.State().apply { port = 8642; isStaticPort = false; isAutoStart = true }

            DefaultsSections.keepProjectPort(current, incoming)

            assertEquals(8700, incoming.port)
            assertTrue(incoming.isStaticPort)
            assertTrue(incoming.isAutoStart, "everything else comes from the defaults")
        }
    }

    private class FakeSection(override val id: String, var values: Map<String, String>) : DefaultsSection {
        override val title = id
        override val description = ""
        var applied: Map<String, String>? = null

        override fun capture(project: Project) = values
        override fun apply(project: Project, entries: Map<String, String>) {
            applied = entries
        }
    }

    @Nested
    inner class Engine {
        private val a = FakeSection("a", mapOf("k" to "1"))
        private val b = FakeSection("b", mapOf("k" to "2"))

        private fun engine(defaults: GlobalDefaults = GlobalDefaults()) = DefaultsEngine(listOf(a, b), defaults)

        @Test
        fun `saving stores the chosen sections and nothing else`() {
            val defaults = GlobalDefaults()

            engine(defaults).save(project, listOf(a))

            assertTrue(defaults.hasDefaults("a"))
            assertFalse(defaults.hasDefaults("b"))
        }

        @Test
        fun `applying copies the saved values into the sections that have some`() {
            val engine = engine()
            engine.save(project, listOf(a))

            val result = engine.apply(project, listOf(a, b))

            assertEquals(mapOf("k" to "1"), a.applied)
            assertNull(b.applied, "a section with no defaults is left alone")
            assertEquals(listOf(a), result.applied)
            assertEquals(listOf(b), result.withoutDefaults)
        }

        @Test
        fun `clearing removes the defaults of the chosen sections only`() {
            val engine = engine()
            engine.save(project, listOf(a, b))

            engine.clear(listOf(a))

            assertFalse(engine.hasDefaults(a))
            assertTrue(engine.hasDefaults(b))
        }
    }

    @Nested
    inner class NewProjects {
        @Test
        fun `a project with no AgentBridge settings file yet is new and gets the defaults`() {
            assertEquals(SeedDecision.SEED, FreshProject.decide(alreadyHandled = false, hasConfigDir = true, hasSettingsFile = false))
        }

        @Test
        fun `a project that already has settings is never overwritten`() {
            assertEquals(SeedDecision.MARK_ONLY, FreshProject.decide(alreadyHandled = false, hasConfigDir = true, hasSettingsFile = true))
        }

        @Test
        fun `a project without an idea directory cannot be judged and is left alone`() {
            assertEquals(SeedDecision.MARK_ONLY, FreshProject.decide(alreadyHandled = false, hasConfigDir = false, hasSettingsFile = false))
        }

        @Test
        fun `a project that was already looked at is not looked at again`() {
            assertEquals(SeedDecision.NOTHING, FreshProject.decide(alreadyHandled = true, hasConfigDir = true, hasSettingsFile = false))
        }
    }

    @Nested
    inner class Registry {
        @Test
        fun `section ids are unique and every section is described`() {
            val ids = DefaultsSections.all.map { it.id }

            assertEquals(ids.size, ids.toSet().size)
            DefaultsSections.all.forEach {
                assertTrue(it.id.isNotBlank() && it.title.isNotBlank() && it.description.isNotBlank(), it.id)
            }
        }

        @Test
        fun `the sections that may hold credentials exist`() {
            assertTrue(DefaultsSections.sensitiveIds.all { id -> DefaultsSections.all.any { it.id == id } })
        }

        @Test
        fun `a configured project is recognised by every file the sections write, and by the web server's`() {
            assertTrue(DefaultsSections.projectFiles.containsAll(listOf("mcpServer.xml", "customMcp.xml", "chatHistory.xml")))
            assertTrue("chatWebServer.xml" in DefaultsSections.projectFiles)
        }

        @Test
        fun `the web server settings are not a section, because its port belongs to one project`() {
            assertTrue(DefaultsSections.all.none { it.id.contains("web") })
        }
    }

    @Nested
    inner class WhatIsCopied {
        private val runtimeStateMarkers = listOf("resume", "thread", "monthly", "usageReset", "injectConversationHistory")

        @Test
        fun `the agent keys are user choices, never runtime state`() {
            ActiveAgentManager.USER_CHOICE_KEYS.forEach { key ->
                assertTrue(runtimeStateMarkers.none { key.contains(it, ignoreCase = true) }, key)
            }
            assertTrue("agent.activeProfileId" in ActiveAgentManager.USER_CHOICE_KEYS)
        }

        @Test
        fun `an agent's copied keys are its model, mode, effort and limits, not its resume id or billing counters`() {
            val keys = GenericSettings.userChoiceKeys("claude-cli")

            assertTrue("claude-cli.selectedModel" in keys)
            assertTrue("claude-cli.sessionOpt.effort" in keys)
            keys.forEach { key -> assertTrue(runtimeStateMarkers.none { key.contains(it, ignoreCase = true) }, key) }
        }

        @Test
        fun `tool permission keys are the ones GenericSettings reads and writes`() {
            assertEquals("tool.perm.read_file", GenericSettings.toolPermissionKey("read_file"))
            assertEquals("tool.outsideProjectAccess", GenericSettings.outsideProjectAccessKey())
        }
    }

    @Nested
    inner class StatesSurviveCopying {
        private fun <S : Any> roundTrips(state: S) {
            val xml = StateXml.write(state)
            assertEquals(xml, StateXml.write(StateXml.read(xml, state.javaClass)), state.javaClass.name)
        }

        @Test
        fun `every state a section copies serializes and reads back unchanged`() {
            roundTrips(McpServerSettings.State())
            roundTrips(CustomMcpSettings.State())
            roundTrips(ChatHistorySettings.State())
            roundTrips(MemorySettings.State())
            roundTrips(CodeGraphSettings.State())
            roundTrips(DiagnosticFilterSettings.State())
            roundTrips(CleanupSettings.State())
        }

        @Test
        fun `MCP server values the user changed are what the copy carries`() {
            val changed = McpServerSettings.State().apply {
                isAutoStart = true
                disabledToolIds = linkedSetOf("git_push", "run_command")
                maxOpenHttpSessions = 7
            }

            val copy = StateXml.read(StateXml.write(changed), McpServerSettings.State::class.java)

            assertTrue(copy.isAutoStart)
            assertEquals(setOf("git_push", "run_command"), copy.disabledToolIds)
            assertEquals(7, copy.maxOpenHttpSessions)
        }
    }
}

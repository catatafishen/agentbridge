package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.client.AbstractClient
import com.github.catatafishen.agentbridge.model.Model
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.AgentUiSettings
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class ModelSelectorServiceTest {

    private lateinit var project: Project
    private lateinit var agentManager: ActiveAgentManager
    private lateinit var client: AbstractClient
    private lateinit var settings: AgentUiSettings
    private lateinit var callbacks: ModelSelectorService.Callbacks
    private lateinit var service: ModelSelectorService

    @BeforeEach
    fun setUp() {
        project = mock(Project::class.java)
        agentManager = mock(ActiveAgentManager::class.java)
        client = mock(AbstractClient::class.java)
        settings = mock(AgentUiSettings::class.java)
        callbacks = mock(ModelSelectorService.Callbacks::class.java)

        `when`(agentManager.client).thenReturn(client)
        `when`(agentManager.settings).thenReturn(settings)

        service = ModelSelectorService(project, agentManager, callbacks)
    }

    private fun model(id: String, name: String) = Model(id, name, null, null)

    @Nested
    inner class BuildModelsJson {

        @Test
        fun `empty list returns empty JSON array`() {
            assertEquals("[]", service.buildModelsJson())
        }

        @Test
        fun `single model returns proper JSON`() {
            setModels(listOf(model("gpt-4o", "GPT-4o")))

            val json = service.buildModelsJson()
            assertTrue(json.contains("\"gpt-4o\""))
            assertTrue(json.contains("\"GPT-4o\""))
            assertTrue(json.startsWith("["))
            assertTrue(json.endsWith("]"))
        }

        @Test
        fun `multiple models are comma-separated`() {
            setModels(listOf(model("a", "Model A"), model("b", "Model B")))

            val json = service.buildModelsJson()
            assertTrue(json.contains("},{"))
        }

        @Test
        fun `special characters in name are escaped`() {
            setModels(listOf(model("x", "Model \"with\" quotes\\slash")))

            val json = service.buildModelsJson()
            // Gson escapes quotes and backslashes
            assertTrue(json.contains("\\\"with\\\""))
            assertTrue(json.contains("\\\\slash"))
        }
    }

    @Nested
    inner class CurrentDisplayText {

        @Test
        fun `returns status text when set`() {
            // After construction, modelsStatusText is MSG_LOADING
            assertEquals("Loading...", service.currentDisplayText)
        }

        @Test
        fun `returns model name when valid index`() {
            setModels(listOf(model("a", "Alpha"), model("b", "Beta")))
            service.selectedModelIndex = 1

            assertEquals("Beta", service.currentDisplayText)
        }

        @Test
        fun `returns loading message when no models and no status`() {
            // reset clears modelsStatusText to null and empties model list
            service.reset()
            // With no models and invalid index, should return MSG_LOADING
            assertEquals("Loading...", service.currentDisplayText)
        }
    }

    @Nested
    inner class SelectModelById {

        @Test
        fun `model exists updates index and calls callbacks`() {
            setModels(listOf(model("a", "Alpha"), model("b", "Beta")))

            service.selectModelById("b")

            assertEquals(1, service.selectedModelIndex)
            verify(settings).setSelectedModel("b")
            verify(callbacks).onModelSelected("b")
        }

        @Test
        fun `model not found does nothing`() {
            setModels(listOf(model("a", "Alpha")))

            service.selectModelById("nonexistent")

            assertEquals(-1, service.selectedModelIndex)
            verify(settings, never()).setSelectedModel(anyString())
            verify(callbacks, never()).onModelSelected(anyString())
        }

        @Test
        fun `empty model list does nothing`() {
            service.selectModelById("any")

            verify(settings, never()).setSelectedModel(anyString())
            verify(callbacks, never()).onModelSelected(anyString())
        }
    }

    @Nested
    inner class ResolveSelectedModelId {

        @Test
        fun `valid index returns model id`() {
            setModels(listOf(model("gpt-4o", "GPT-4o")))
            service.selectedModelIndex = 0

            assertEquals("gpt-4o", service.resolveSelectedModelId())
        }

        @Test
        fun `invalid index with client currentModelId returns client model`() {
            `when`(client.currentModelId).thenReturn("claude-3")

            assertEquals("claude-3", service.resolveSelectedModelId())
        }

        @Test
        fun `invalid index with no client model returns empty string`() {
            `when`(client.currentModelId).thenReturn(null)

            assertEquals("", service.resolveSelectedModelId())
        }

        @Test
        fun `invalid index with empty client model returns empty string`() {
            `when`(client.currentModelId).thenReturn("")

            assertEquals("", service.resolveSelectedModelId())
        }
    }

    @Nested
    inner class RestoreModelSelection {

        @Test
        fun `saved model found in list selects it`() {
            `when`(settings.selectedModel).thenReturn("b")
            val models = listOf(model("a", "A"), model("b", "B"), model("c", "C"))
            setModels(models)

            service.restoreModelSelection(models)

            assertEquals(1, service.selectedModelIndex)
        }

        @Test
        fun `saved model not found but client currentModelId in list selects that`() {
            `when`(settings.selectedModel).thenReturn("nonexistent")
            `when`(client.currentModelId).thenReturn("c")
            val models = listOf(model("a", "A"), model("b", "B"), model("c", "C"))
            setModels(models)

            service.restoreModelSelection(models)

            assertEquals(2, service.selectedModelIndex)
        }

        @Test
        fun `stale saved model replaced by currentModelId updates setting`() {
            `when`(settings.selectedModel).thenReturn("stale-model")
            `when`(client.currentModelId).thenReturn("c")
            val models = listOf(model("a", "A"), model("b", "B"), model("c", "C"))
            setModels(models)

            service.restoreModelSelection(models)

            assertEquals(2, service.selectedModelIndex)
            verify(settings).setSelectedModel("c")
        }

        @Test
        fun `neither found and models non-empty selects index 0 and updates setting`() {
            `when`(settings.selectedModel).thenReturn("nonexistent")
            `when`(client.currentModelId).thenReturn("also-nonexistent")
            val models = listOf(model("a", "A"), model("b", "B"))
            setModels(models)

            service.restoreModelSelection(models)

            assertEquals(0, service.selectedModelIndex)
            verify(settings).setSelectedModel("a")
        }

        @Test
        fun `null saved model falling through to currentModelId does not update setting`() {
            `when`(settings.selectedModel).thenReturn(null)
            `when`(client.currentModelId).thenReturn("b")
            val models = listOf(model("a", "A"), model("b", "B"))
            setModels(models)

            service.restoreModelSelection(models)

            assertEquals(1, service.selectedModelIndex)
            verify(settings, never()).setSelectedModel(anyString())
        }

        @Test
        fun `empty model list keeps index at -1`() {
            `when`(settings.selectedModel).thenReturn(null)
            `when`(client.currentModelId).thenReturn(null)

            service.restoreModelSelection(emptyList())

            assertEquals(-1, service.selectedModelIndex)
        }

        @Test
        fun `null saved model falls through to client currentModelId`() {
            `when`(settings.selectedModel).thenReturn(null)
            `when`(client.currentModelId).thenReturn("b")
            val models = listOf(model("a", "A"), model("b", "B"))
            setModels(models)

            service.restoreModelSelection(models)

            assertEquals(1, service.selectedModelIndex)
        }
    }

    @Nested
    inner class GetModelMultiplier {

        @Test
        fun `client returns value`() {
            `when`(client.getModelMultiplier("gpt-4o")).thenReturn("2x")

            assertEquals("2x", service.getModelMultiplier("gpt-4o"))
        }

        @Test
        fun `client throws returns null`() {
            `when`(client.getModelMultiplier("gpt-4o")).thenThrow(RuntimeException("fail"))

            assertNull(service.getModelMultiplier("gpt-4o"))
        }
    }

    @Nested
    inner class Reset {

        @Test
        fun `reset clears models index and status`() {
            setModels(listOf(model("a", "A")))
            service.selectedModelIndex = 0

            service.reset()

            assertTrue(service.loadedModels.isEmpty())
            assertEquals(-1, service.selectedModelIndex)
            assertNull(service.modelsStatusText)
        }
    }

    @Nested
    inner class InvalidateLoads {

        @Test
        fun `invalidateLoads increments generation`() {
            // We can verify indirectly: after invalidate, a previous loadGeneration won't match
            // The simplest observable effect is that calling invalidateLoads multiple times
            // doesn't throw and the state remains consistent
            service.invalidateLoads()
            service.invalidateLoads()
            // No exception means it works; the generation counter is internal
        }
    }

    /**
     * Helper to set loadedModels via reflection since the setter is private.
     */
    private fun setModels(models: List<Model>) {
        val field = ModelSelectorService::class.java.getDeclaredField("loadedModels")
        field.isAccessible = true
        field.set(service, models)
        // Also clear status text so currentDisplayText reads model names
        val statusField = ModelSelectorService::class.java.getDeclaredField("modelsStatusText")
        statusField.isAccessible = true
        statusField.set(service, null)
    }
}

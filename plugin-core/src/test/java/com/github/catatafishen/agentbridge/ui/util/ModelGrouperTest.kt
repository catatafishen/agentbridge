package com.github.catatafishen.agentbridge.ui.util

import com.github.catatafishen.agentbridge.acp.model.Model
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelGrouperTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun model(id: String, name: String): Model =
        Model(id, name, null, null)

    private fun grouper(vararg favoriteIds: String): ModelGrouper =
        ModelGrouper(favoriteIds.toSet())

    private fun ModelGrouper.groupModels(vararg modelSpecs: Pair<String, String>): List<ModelGrouper.Group> {
        val models = modelSpecs.map { (id, name) -> model(id, name) }
        return this.group(models)
    }

    // ── Test 1: Empty list → empty result ───────────────────────────────

    @Test
    fun `empty list returns empty groups`() {
        val grouper = grouper()
        val result = grouper.group(emptyList())
        assertTrue(result.isEmpty())
    }

    // ── Test 2: Favorited models go in Favorites group at top ───────────

    @Test
    fun `favorited models appear in Favorites group at top`() {
        val grouper = grouper("model1", "model3")
        val result = grouper.groupModels(
            "model1" to "Nvidia/Llama 3.1",
            "model2" to "OpenCode/Claude",
            "model3" to "Anthropic/Claude"
        )

        // Favorites (2: model1 + model3) + OpenCode (model2) = 2 groups
        assertEquals(2, result.size)
        assertEquals("Favorites", result[0].provider)
        assertEquals(2, result[0].models.size)
        assertTrue(result[0].models.all { it.isFavorite })
        assertEquals("OpenCode", result[1].provider)
    }

    // ── Test 3: Provider grouping ───────────────────────────────────────

    @Test
    fun `models with same provider are grouped together`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m1" to "Nvidia/Llama 3.1",
            "m2" to "Nvidia/Llama 3.2",
            "m3" to "OpenCode/Claude"
        )

        val nvidiaGroup = result.find { it.provider == "Nvidia" }
        assertNotNull(nvidiaGroup)
        assertEquals(2, nvidiaGroup!!.models.size)
        assertTrue(nvidiaGroup.models.all { it.providerName == "Nvidia" })
    }

    // ── Test 4: Provider ordering — alphabetical, Other last ────────────

    @Test
    fun `providers are sorted alphabetically with Other last`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m1" to "Zeta/Model-A",
            "m2" to "Alpha/Model-B",
            "m3" to "NoSlashModel1",  // "Other"
            "m4" to "Beta/Model-D"
        )

        // No favorites → Alpha + Beta + Zeta + Other (no Favorites group)
        assertEquals(4, result.size)
        assertEquals("Alpha", result[0].provider)
        assertEquals("Beta", result[1].provider)
        assertEquals("Zeta", result[2].provider)
        assertEquals("Other", result[3].provider)
        assertFalse(result.any { it.provider == "Favorites" })
    }

    // ── Test 5: displayName formatting ──────────────────────────────────

    @Test
    fun `displayName for favorite with providerName shows name with providerName in parentheses`() {
        val grouper = grouper("m1")
        val result = grouper.groupModels(
            "m1" to "Nvidia/Llama 3.1"
        )

        val favGroup = result.find { it.provider == "Favorites" }
        assertNotNull(favGroup)
        assertEquals(1, favGroup!!.models.size)
        val groupedModel = favGroup.models[0]
        assertTrue(groupedModel.isFavorite)
        assertEquals("Nvidia", groupedModel.providerName)
        assertEquals("Llama 3.1 (Nvidia)", groupedModel.displayName)
    }

    @Test
    fun `displayName for non-favorite shows just the name`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m1" to "Nvidia/Llama 3.1"
        )

        val nvidiaGroup = result.find { it.provider == "Nvidia" }
        assertNotNull(nvidiaGroup)
        val groupedModel = nvidiaGroup!!.models[0]
        assertFalse(groupedModel.isFavorite)
        assertEquals("Nvidia", groupedModel.providerName)
        assertEquals("Llama 3.1", groupedModel.displayName)
    }

    // ── Test 6: Favorites group only when non-empty ──────────────────────

    @Test
    fun `Favorites group is absent when there are no favorites`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m1" to "Nvidia/Llama 3.1"
        )

        // No favorites → only Nvidia group, no Favorites group
        assertEquals(1, result.size)
        assertEquals("Nvidia", result[0].provider)
        assertFalse(result.any { it.provider == "Favorites" })
    }

    // ── Test 7: Single provider ─────────────────────────────────────────

    @Test
    fun `single provider does not add Other group`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m1" to "Nvidia/Llama 3.1",
            "m2" to "Nvidia/Llama 3.2"
        )

        val providers = result.map { it.provider }
        assertFalse(providers.contains("Other"), "Should not have Other group with single provider")
        assertTrue(providers.contains("Nvidia"))
    }

    // ── Test 8: Multiple providers mixed with favorites ─────────────────

    @Test
    fun `multiple providers with favorites maintains correct ordering`() {
        val grouper = grouper("m2")
        val result = grouper.groupModels(
            "m1" to "Zeta/Model-A",
            "m2" to "Nvidia/Llama 3.1",  // favorite
            "m3" to "Alpha/Model-B"
        )

        // Favorites first, then providers alphabetically
        assertEquals("Favorites", result[0].provider)
        assertEquals(1, result[0].models.size)
        assertEquals("m2", result[0].models[0].modelId)

        // Provider groups: Alpha, Zeta (Nvidia model is in Favorites)
        val providers = result.drop(1).map { it.provider }
        assertEquals(listOf("Alpha", "Zeta"), providers)
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `model without slash uses Other provider`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m1" to "GPT-4o"
        )

        val otherGroup = result.find { it.provider == "Other" }
        assertNotNull(otherGroup)
        assertEquals(1, otherGroup!!.models.size)
    }

    @Test
    fun `model with leading slash uses Other provider`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m1" to "/LeadingSlash"
        )

        val otherGroup = result.find { it.provider == "Other" }
        assertNotNull(otherGroup)
    }

    @Test
    fun `null model name uses id as fallback`() {
        val grouper = grouper()
        val modelWithNullName = Model("model-id", null, null, null)
        val result = grouper.group(listOf(modelWithNullName))

        val otherGroup = result.find { it.provider == "Other" }
        assertNotNull(otherGroup)
        assertEquals(1, otherGroup!!.models.size)
        assertEquals("model-id", otherGroup.models[0].name)
    }

    @Test
    fun `indexes are preserved from original list`() {
        val grouper = grouper()
        val result = grouper.groupModels(
            "m0" to "First",
            "m1" to "Second",
            "m2" to "Third"
        )

        val otherGroup = result.find { it.provider == "Other" }
        assertNotNull(otherGroup)
        val indexes = otherGroup!!.models.map { it.index }
        assertEquals(listOf(0, 1, 2), indexes)
    }
}

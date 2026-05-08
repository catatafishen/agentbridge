package com.github.catatafishen.agentbridge.ui.util

import com.github.catatafishen.agentbridge.acp.model.Model

/**
 * Groups a flat model list by provider for the model picker popup.
 *
 * Pure logic — zero Swing/UI dependencies, fully unit-testable.
 */
class ModelGrouper(private val favorites: Set<String>) {

    data class Group(val provider: String, val models: List<GroupedModel>)
    data class GroupedModel(
        val index: Int,
        val modelId: String,
        val name: String,
        val isFavorite: Boolean,
        val providerName: String?
    ) {
        val displayName: String
            get() = if (isFavorite && providerName != null) "$name ($providerName)" else name
    }

    companion object {
        private const val OTHER = "Other"
    }

    /**
     * Returns ordered groups: Favorites (if non-empty), then providers
     * alphabetically with "Other" last. Each entry preserves the original
     * flat index so model selection still works via the existing `loadedModels` list.
     */
    fun group(models: List<Model>): List<Group> {
        if (models.isEmpty()) return emptyList()

        val indexed = models.mapIndexed { index, model ->
            val name = ModelProvider.getModelName(model)
            val provider = ModelProvider.getProvider(model)
            GroupedModel(index, model.id(), name, favorites.contains(model.id()), provider)
        }

        // Partition favorites
        val (favs, rest) = indexed.partition { it.isFavorite }

        // Group rest by provider
        val providerGroups: LinkedHashMap<String, MutableList<GroupedModel>> = linkedMapOf()
        for (item in rest) {
            val provider = ModelProvider.getProvider(models[item.index]) ?: OTHER
            providerGroups.getOrPut(provider) { mutableListOf() }.add(item)
        }

        // Sort providers alphabetically, Other last
        val sortedProviders = providerGroups.keys.sortedWith(
            compareBy<String> { it == OTHER }.thenBy { it.lowercase() }
        )

        val result = mutableListOf<Group>()

        // Always include Favorites group (even if empty)
        result.add(Group("Favorites", favs))

        for (provider in sortedProviders) {
            val groupModels = providerGroups[provider] ?: continue
            // Suppress provider header when single-provider + no favorites
            if (favs.isEmpty() && sortedProviders.size == 1 && provider != OTHER) {
                result.add(Group(provider, groupModels))
            } else {
                result.add(Group(provider, groupModels))
            }
        }

        return result
    }
}

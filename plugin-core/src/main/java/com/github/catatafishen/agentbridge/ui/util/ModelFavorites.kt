package com.github.catatafishen.agentbridge.ui.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * Persistent store for favorite model IDs, project-scoped.
 *
 * Backed by [PropertiesComponent], survives IDE restarts.
 * Storage format: JSON array `["providerId/modelId", ...]`.
 */
class ModelFavorites(private val project: Project) {

    companion object {
        private const val KEY = "agentbridge.favoriteModels"
        private val gson = Gson()
        private val setType = object : TypeToken<MutableSet<String>>() {}.type

        fun getInstance(project: Project): ModelFavorites = ModelFavorites(project)
    }

    private val props: PropertiesComponent = PropertiesComponent.getInstance(project)

    fun isFavorite(modelId: String): Boolean = toSet().contains(modelId)

    fun toggle(modelId: String) {
        val set = toSet().toMutableSet()
        if (set.contains(modelId)) set.remove(modelId) else set.add(modelId)
        props.setValue(KEY, gson.toJson(set.toList()))
    }

    fun toSet(): Set<String> {
        val raw = props.getValue(KEY) ?: return emptySet()
        return try {
            gson.fromJson<MutableSet<String>>(raw, setType)
        } catch (_: Exception) {
            emptySet()
        }
    }
}

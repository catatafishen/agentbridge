package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.model.Model
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.session.SessionSwitchService
import com.github.catatafishen.agentbridge.ui.ChatToolWindowContent.Companion.MSG_LOADING
import com.github.catatafishen.agentbridge.ui.ChatToolWindowContent.Companion.MSG_UNKNOWN_ERROR
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages model loading, selection, and multiplier resolution for the chat prompt.
 *
 * Owns: model list state, load generation counter, selected index, status text.
 * Communicates back to the UI via [Callbacks].
 */
class ModelSelectorService(
    private val project: Project,
    private val agentManager: ActiveAgentManager,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onModelSelected(modelId: String)
        fun onModelsLoadFailed(error: Exception)
    }

    private val log = Logger.getInstance(ModelSelectorService::class.java)

    @Volatile
    var loadedModels: List<Model> = emptyList()
        private set

    @Volatile
    var selectedModelIndex: Int = -1

    @Volatile
    var modelsStatusText: String? = MSG_LOADING
        private set

    private val loadGeneration = AtomicInteger(0)

    /** Invalidates in-flight load requests so they discard their results. */
    fun invalidateLoads() {
        loadGeneration.incrementAndGet()
    }

    /** Resets state for disconnect (clears models, index, status). */
    fun reset() {
        loadedModels = emptyList()
        selectedModelIndex = -1
        modelsStatusText = null
    }

    val currentDisplayText: String
        get() {
            modelsStatusText?.let { return it }
            return if (selectedModelIndex in loadedModels.indices) {
                loadedModels[selectedModelIndex].name()
            } else {
                MSG_LOADING
            }
        }

    fun buildModelsJson(): String {
        val array = JsonArray()
        for (m in loadedModels) {
            val obj = JsonObject()
            obj.addProperty("id", m.id())
            obj.addProperty("name", m.name())
            array.add(obj)
        }
        return array.toString()
    }

    fun selectModelById(modelId: String) {
        val idx = loadedModels.indexOfFirst { it.id() == modelId }
        if (idx < 0) return
        selectedModelIndex = idx
        agentManager.settings.setSelectedModel(modelId)
        callbacks.onModelSelected(modelId)
    }

    fun resolveSelectedModelId(): String {
        loadedModels.getOrNull(selectedModelIndex)?.id()?.takeIf { it.isNotEmpty() }?.let { return it }
        return agentManager.client.currentModelId?.takeIf { it.isNotEmpty() } ?: ""
    }

    fun getModelMultiplier(modelId: String): String? {
        return try {
            agentManager.client.getModelMultiplier(modelId)
        } catch (_: Exception) {
            null
        }
    }

    fun loadModelsAsync(
        onSuccess: (List<Model>) -> Unit,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val generation = loadGeneration.incrementAndGet()
        ApplicationManager.getApplication().invokeLater {
            loadedModels = emptyList()
            modelsStatusText = MSG_LOADING
            selectedModelIndex = -1
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val models = fetchModelsWithRetry(generation)
                ApplicationManager.getApplication().invokeLater {
                    if (generation == loadGeneration.get()) {
                        onModelsLoaded(models, onSuccess)
                    } else {
                        log.info("Discarding stale model load (gen $generation, current ${loadGeneration.get()})")
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: MSG_UNKNOWN_ERROR
                log.warn("Failed to load models: $errorMsg")
                ApplicationManager.getApplication().invokeLater {
                    if (generation == loadGeneration.get()) {
                        modelsStatusText = "Unavailable"
                        callbacks.onModelsLoadFailed(e)
                        onFailure?.invoke(e)
                    }
                }
            }
        }
    }

    fun restoreModelSelection(models: List<Model>) {
        val savedModel = agentManager.settings.selectedModel
        log.debug("Restoring model selection: saved='$savedModel', current='${agentManager.client.currentModelId}', available=${models.map { it.id() }}")
        if (savedModel != null) {
            val idx = models.indexOfFirst { it.id() == savedModel }
            if (idx >= 0) {
                selectedModelIndex = idx; log.debug("Restored model index=$idx"); return
            }
            log.debug("Saved model '$savedModel' not found in available models")
        }
        val currentModelId = agentManager.client.currentModelId
        if (currentModelId != null) {
            val idx = models.indexOfFirst { it.id() == currentModelId }
            if (idx >= 0) {
                selectedModelIndex = idx; log.debug("Selected agent-reported model index=$idx"); return
            }
        }
        if (models.isNotEmpty()) selectedModelIndex = 0
    }

    private fun fetchModelsWithRetry(startGeneration: Int): List<Model> {
        SessionSwitchService.getInstance(project).awaitPendingExport(10_000)

        var lastError: Exception? = null
        for (attempt in 1..3) {
            if (attempt > 1) {
                Thread.sleep(2000L)
                if (loadGeneration.get() != startGeneration) return emptyList()
            }
            try {
                return agentManager.client.getAvailableModels()
            } catch (e: Exception) {
                lastError = e
                if (AuthLoginService(project).isAuthenticationError(e.message ?: "") ||
                    PromptErrorClassifier.isCLINotFoundError(e)
                ) break
            }
        }
        throw lastError ?: RuntimeException(MSG_UNKNOWN_ERROR)
    }

    private fun onModelsLoaded(models: List<Model>, onSuccess: (List<Model>) -> Unit) {
        loadedModels = models
        modelsStatusText = null
        restoreModelSelection(models)
        onSuccess(models)
    }
}

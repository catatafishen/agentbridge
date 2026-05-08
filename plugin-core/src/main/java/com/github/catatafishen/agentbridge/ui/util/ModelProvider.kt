package com.github.catatafishen.agentbridge.ui.util

import com.github.catatafishen.agentbridge.acp.model.Model

/**
 * Extracts provider information from ACP [Model] names.
 *
 * OpenCode's model format is `"ProviderName/ModelName"` — the provider prefix
 * is used for grouping in the model picker dropdown. Other ACP agents use flat
 * model names without slashes and are not grouped.
 *
 * Pure logic — zero UI dependencies, unit-testable in isolation.
 */
object ModelProvider {

    /**
     * Returns the provider display name, or `null` if the model name does not
     * contain a slash-separated provider prefix.
     *
     * Examples:
     * - `"Nvidia/Llama 3.1 Nemotron"` → `"Nvidia"`
     * - `"OpenCode/Claude Max"`       → `"OpenCode"`
     * - `"GPT-4o"`                    → `null`
     * - `"/LeadingSlash"`             → `null`
     * - `null` name                   → `null`
     */
    fun getProvider(model: Model): String? {
        val name = model.name() ?: return null
        val slash = name.indexOf('/')
        if (slash <= 0) return null
        return name.substring(0, slash)
    }

    /**
     * Returns the model name without the provider prefix.
     * For flat names, returns the name as-is.
     *
     * Examples:
     * - `"Nvidia/Llama 3.1 Nemotron"` → `"Llama 3.1 Nemotron"`
     * - `"GPT-4o"`                    → `"GPT-4o"`
     * - `null` name                   → `model.id()`
     */
    fun getModelName(model: Model): String {
        val name = model.name() ?: return model.id()
        val slash = name.indexOf('/')
        return if (slash >= 0) name.substring(slash + 1).trim() else name
    }
}

package com.github.catatafishen.agentbridge.ui.util

import com.github.catatafishen.agentbridge.acp.model.Model
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModelProviderTest {

    // ── getProvider ────────────────────────────────────────────────────

    @Test
    fun getProvider_slashSeparatedName_returnsProviderPrefix() {
        val model = Model("nvidia/llama-3.1-nemotron", "Nvidia/Llama 3.1 Nemotron", null, null)
        assertEquals("Nvidia", ModelProvider.getProvider(model))
    }

    @Test
    fun getProvider_multiSlash_returnsFirstSegment() {
        val model = Model("opencode/gpt-plus/variant", "OpenCode/GPT Plus/Variant", null, null)
        assertEquals("OpenCode", ModelProvider.getProvider(model))
    }

    @Test
    fun getProvider_noSlash_returnsNull() {
        val model = Model("gpt-4o", "GPT-4o", null, null)
        assertNull(ModelProvider.getProvider(model))
    }

    @Test
    fun getProvider_nullName_returnsNull() {
        val model = Model("some-id", null, null, null)
        assertNull(ModelProvider.getProvider(model))
    }

    @Test
    fun getProvider_leadingSlash_returnsNull() {
        // slash at position 0 means no provider prefix
        val model = Model("/LeadingSlash", "/LeadingSlash", null, null)
        assertNull(ModelProvider.getProvider(model))
    }

    @Test
    fun getProvider_emptyName_returnsNull() {
        val model = Model("some-id", "", null, null)
        assertNull(ModelProvider.getProvider(model))
    }

    @Test
    fun getProvider_metaProviderNotUsed() {
        // _meta.provider is ignored by the implementation; it uses only model.name()
        val meta = JsonObject().apply { addProperty("provider", "ShouldNotBeUsed") }
        val model = Model("some-id", "Actual/Provider", null, meta)
        assertEquals("Actual", ModelProvider.getProvider(model))
    }

    // ── getModelName ──────────────────────────────────────────────────

    @Test
    fun getModelName_slashSeparated_returnsSuffixAfterSlash() {
        val model = Model("nvidia/llama-3.1-nemotron", "Nvidia/Llama 3.1 Nemotron", null, null)
        assertEquals("Llama 3.1 Nemotron", ModelProvider.getModelName(model))
    }

    @Test
    fun getModelName_multiSlash_returnsAfterFirstSlash() {
        val model = Model("opencode/gpt-plus/variant", "OpenCode/GPT Plus/Variant", null, null)
        assertEquals("GPT Plus/Variant", ModelProvider.getModelName(model))
    }

    @Test
    fun getModelName_noSlash_returnsFullName() {
        val model = Model("gpt-4o", "GPT-4o", null, null)
        assertEquals("GPT-4o", ModelProvider.getModelName(model))
    }

    @Test
    fun getModelName_nullName_returnsId() {
        val model = Model("fallback-id-123", null, null, null)
        assertEquals("fallback-id-123", ModelProvider.getModelName(model))
    }

    @Test
    fun getModelName_emptyName_returnsEmptyString() {
        val model = Model("fallback-id-456", "", null, null)
        assertEquals("", ModelProvider.getModelName(model))
    }

    @Test
    fun getModelName_slashWithNoSuffix_returnsEmptyString() {
        // slash at end of name — substring after it is empty
        val model = Model("provider/", "Provider/", null, null)
        assertEquals("", ModelProvider.getModelName(model))
    }

    @Test
    fun getModelName_trimsWhitespaceAfterSlash() {
        val model = Model("openai/ GPT-4o", "OpenAI/ GPT-4o", null, null)
        assertEquals("GPT-4o", ModelProvider.getModelName(model))
    }
}

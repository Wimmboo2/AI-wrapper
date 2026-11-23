package dev.wimmboo.omni.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomModel(
    val id: String = "",
    val name: String = "",
    val ctx: Int = 8192,
)

@Serializable
data class QuickCustomModel(
    val provider: String = "",
    val id: String = "",
    val name: String = "",
    val ctx: Int = 8192,
)

/**
 * Exact 1:1 field-name parity with DEFAULT_SETTINGS in index.html so settings
 * exported from the web app import cleanly here and vice versa.
 */
@Serializable
data class OmniSettings(
    val apiEndpoint: String = OmniCatalog.DEFAULT_WORKER_ENDPOINT,
    val appOrigin: String = OmniCatalog.DEFAULT_APP_ORIGIN,
    val provider: String = "openai",
    val apiKey: String = "",
    val apiKeys: Map<String, String> = emptyMap(),
    val model: String = OmniCatalog.DEFAULT_MODEL_ID,
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val thinking: Boolean = true,
    val effort: String = "high",
    val searchMode: String = "auto",
    val searchBackend: String = "auto",
    val searchDepth: String = "auto",
    val tavilyKey: String = "",
    val proxyKey: String = "",
    val customBaseURL: String = "",
    val customModels: List<CustomModel> = emptyList(),
    val customModelId: String = "",
    val customModelCtx: Int = 8192,
    val systemPrompt: String = "",
    val quickCustomModels: List<QuickCustomModel> = emptyList(),
    val promptTemplates: List<PromptTemplate> = OmniCatalog.DEFAULT_TEMPLATES,
    val e2bKey: String = "",
    val sandboxProvider: String = "e2b",
    val sandboxKeys: Map<String, String> = emptyMap(),
    val implicitThinkModels: Map<String, Boolean> = emptyMap(),
    val agentMaxSteps: Int = 8,
    val agentSandboxTimeout: Int = 120,
) {
    /** Never leak secrets into logs or crash reports. */
    override fun toString(): String =
        "OmniSettings(apiEndpoint=$apiEndpoint, provider=$provider, model=$model, thinking=$thinking, effort=$effort, searchMode=$searchMode)"

    fun effectiveApiKey(forProvider: String = provider): String {
        val stored = apiKeys[forProvider]?.trim()
        if (!stored.isNullOrEmpty()) return stored
        return if (forProvider == provider) apiKey.trim() else ""
    }

    fun activeSandboxProviderId(): String =
        if (OmniCatalog.SANDBOX_PROVIDERS.containsKey(sandboxProvider)) sandboxProvider else "e2b"

    fun activeSandboxKey(forSandbox: String = activeSandboxProviderId()): String {
        val stored = sandboxKeys[forSandbox]?.trim()
        if (!stored.isNullOrEmpty()) return stored
        return if (forSandbox == "e2b") e2bKey.trim() else ""
    }

    fun actualModelId(): String =
        if (model == "__custom__") customModelId else model

    fun thinkModelKey(): String = "$provider:${actualModelId()}"

    fun availablePresetModels(providerId: String = provider): List<ModelPreset> {
        if (providerId == "custom") {
            return customModels.filter { it.id.isNotBlank() }.map {
                ModelPreset(
                    id = it.id,
                    name = it.name.ifBlank { it.id },
                    ctx = if (it.ctx > 0) it.ctx else 8192,
                    effort = OmniCatalog.CUSTOM_DEFAULT_EFFORT,
                    effortDefault = "medium",
                )
            }
        }
        return OmniCatalog.getProviderConfig(providerId)?.models ?: emptyList()
    }

    fun customModelsForCurrentProvider(): List<ModelPreset> {
        if (provider == "custom") return availablePresetModels("custom")
        return quickCustomModels.filter { it.provider == provider && it.id.isNotBlank() }.map {
            ModelPreset(
                id = it.id,
                name = it.name.ifBlank { it.id },
                ctx = if (it.ctx > 0) it.ctx else 8192,
                effort = OmniCatalog.CUSTOM_DEFAULT_EFFORT,
                effortDefault = "medium",
            )
        }
    }

    fun allModelsForComposer(): List<ModelPreset> {
        val presets = availablePresetModels(provider)
        if (provider == "custom") return presets
        return presets + customModelsForCurrentProvider()
    }

    fun activeModelConfig(): ModelPreset? {
        if (model == "__custom__") {
            return ModelPreset(
                id = customModelId.ifBlank { "__custom__" },
                name = "Custom",
                ctx = if (customModelCtx > 0) customModelCtx else 8192,
                effort = OmniCatalog.CUSTOM_DEFAULT_EFFORT,
                effortDefault = "medium",
            )
        }
        val presets = availablePresetModels(provider)
        presets.firstOrNull { it.id == model }?.let { return it }
        customModelsForCurrentProvider().firstOrNull { it.id == model }?.let { return it }
        return presets.firstOrNull() ?: customModelsForCurrentProvider().firstOrNull()
    }

    fun effortTiers(): List<String>? {
        val m = activeModelConfig()
        if (m != null && !m.effort.isNullOrEmpty()) return m.effort
        val pCfg = OmniCatalog.getProviderConfig(provider)
        if (pCfg != null && !pCfg.effort.isNullOrEmpty()) return pCfg.effort
        if (provider == "custom") return OmniCatalog.CUSTOM_DEFAULT_EFFORT
        return null
    }

    fun effortDefault(): String? {
        val m = activeModelConfig()
        if (m?.effortDefault != null) return m.effortDefault
        val pCfg = OmniCatalog.getProviderConfig(provider)
        if (pCfg?.effortDefault != null) return pCfg.effortDefault
        return effortTiers()?.lastOrNull()
    }

    fun supportsVision(): Boolean {
        val m = activeModelConfig()
        return m == null || m.vision
    }

    fun contextWindow(): Int = activeModelConfig()?.ctx ?: 8192

    fun agentAvailable(): Boolean {
        val cfg = activeModelConfig()
        return activeSandboxKey().isNotBlank() &&
            apiEndpoint.isNotBlank() &&
            (cfg == null || cfg.tools)
    }

    fun normalized(): OmniSettings {
        var nextProvider = provider.ifBlank { "openai" }
        var nextModel = model
        if (nextProvider == "cerebras" || nextProvider == "groq") {
            nextProvider = "openai"
            nextModel = OmniCatalog.DEFAULT_MODEL_ID
        }
        if (!OmniCatalog.PROVIDERS.containsKey(nextProvider)) {
            nextProvider = "openai"
            nextModel = OmniCatalog.DEFAULT_MODEL_ID
        }

        val nextEndpoint = apiEndpoint.trim().ifEmpty { OmniCatalog.DEFAULT_WORKER_ENDPOINT }
        val nextSearchMode = if (searchMode in setOf("auto", "on", "off")) searchMode else "auto"
        val nextSearchBackend = if (searchBackend == "tavily") "tavily" else "auto"
        val nextSearchDepth = if (searchDepth in setOf("auto", "basic", "advanced")) searchDepth else "auto"
        val nextSandboxProvider = if (OmniCatalog.SANDBOX_PROVIDERS.containsKey(sandboxProvider)) sandboxProvider else "e2b"
        val nextTemplates = ensureSearchTemplate(promptTemplates.ifEmpty { OmniCatalog.DEFAULT_TEMPLATES })

        val base = copy(
            apiEndpoint = nextEndpoint,
            provider = nextProvider,
            model = nextModel,
            temperature = temperature.coerceIn(0.0, 2.0),
            maxTokens = maxTokens.coerceIn(64, 32768),
            searchMode = nextSearchMode,
            searchBackend = nextSearchBackend,
            searchDepth = nextSearchDepth,
            sandboxProvider = nextSandboxProvider,
            promptTemplates = nextTemplates,
            agentMaxSteps = agentMaxSteps.coerceIn(1, 25),
            agentSandboxTimeout = agentSandboxTimeout.coerceIn(30, 300),
            customModelCtx = if (customModelCtx > 0) customModelCtx else 8192,
        )

        // Validate model belongs to provider or fallback
        val all = base.allModelsForComposer()
        val resolvedModel = when {
            base.model == "__custom__" -> base.model
            all.any { it.id == base.model } -> base.model
            all.isNotEmpty() -> all.first().id
            else -> base.model
        }

        val withModel = base.copy(model = resolvedModel)
        val tiers = withModel.effortTiers()
        val resolvedEffort = if (tiers != null && withModel.effort !in tiers) {
            withModel.effortDefault() ?: tiers.last()
        } else {
            withModel.effort
        }
        return withModel.copy(effort = resolvedEffort)
    }

    companion object {
        private fun ensureSearchTemplate(list: List<PromptTemplate>): List<PromptTemplate> {
            if (list.any { it.key == "search" }) return list
            val searchTpl = OmniCatalog.DEFAULT_TEMPLATES.firstOrNull { it.key == "search" } ?: return list
            val out = list.toMutableList()
            val idx = out.indexOfFirst { it.key == "web" }.let { if (it >= 0) it else 0 }
            out.add(idx, searchTpl)
            return out
        }
    }
}

@Serializable
data class SecretsBundle(
    val apiKey: String = "",
    val apiKeys: Map<String, String> = emptyMap(),
    val tavilyKey: String = "",
    val proxyKey: String = "",
    val e2bKey: String = "",
    val sandboxKeys: Map<String, String> = emptyMap(),
)

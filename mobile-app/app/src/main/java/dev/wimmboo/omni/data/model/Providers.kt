package dev.wimmboo.omni.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ModelPreset(
    val id: String,
    val name: String,
    val ctx: Int,
    val effort: List<String>? = null,
    val effortDefault: String? = null,
    val vision: Boolean = true,
    val tools: Boolean = true,
    val effortInfo: Map<String, String>? = null,
)

data class ProviderConfig(
    val id: String,
    val name: String,
    val apiKeyPlaceholder: String,
    val defaultEndpoint: String = "",
    val models: List<ModelPreset>,
    val effort: List<String>? = null,
    val effortDefault: String? = null,
)

data class SandboxProviderMeta(
    val id: String,
    val label: String,
    val placeholder: String,
    val site: String,
)

@Serializable
data class PromptTemplate(
    val key: String,
    val label: String,
    val color: String = "amber",
    val prompt: String = "",
    val hint: String? = null,
    val prefix: String? = null,
    val enableCodeMode: Boolean = false,
    val enableWebSearch: Boolean = false,
)

object OmniCatalog {
    const val DEFAULT_WORKER_ENDPOINT = "https://omni-api.wimmboo.workers.dev"
    const val DEFAULT_APP_ORIGIN = "https://claude-like-ai-wrapper.wimmboo.workers.dev"
    const val DEFAULT_MODEL_ID = "gpt-6.1-sol"

    val CUSTOM_DEFAULT_EFFORT = listOf("low", "medium", "high", "xhigh", "max")

    val EFFORT_LABELS: Map<String, String> = mapOf(
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "max" to "Max",
        "standard" to "Standard",
        "xhigh" to "X-High",
        "minimal" to "Minimal",
        "none" to "None",
        "default" to "Default",
    )

    fun effortLabel(tier: String): String = EFFORT_LABELS[tier] ?: tier

    val SANDBOX_PROVIDERS: Map<String, SandboxProviderMeta> = linkedMapOf(
        "e2b" to SandboxProviderMeta("e2b", "E2B", "e2b_...", "e2b.dev"),
        "novita" to SandboxProviderMeta("novita", "Novita", "nvta_...", "novita.ai"),
    )

    val DEFAULT_TEMPLATES: List<PromptTemplate> = listOf(
        PromptTemplate(key = "code", label = "Code mode", color = "purple", enableCodeMode = true),
        PromptTemplate(
            key = "search",
            label = "Search the web",
            color = "blue",
            hint = "Runs the rest of the line",
            enableWebSearch = true,
        ),
        PromptTemplate(key = "web", label = "Web search", color = "blue", enableWebSearch = true),
        PromptTemplate(
            key = "explain",
            label = "Simple Explainer",
            color = "amber",
            prefix = "Explain this topic simply and clearly, step by step: ",
        ),
        PromptTemplate(
            key = "summarize",
            label = "Summarize Text",
            color = "emerald",
            prefix = "Summarize the key takeaways and main points of the following text: ",
        ),
        PromptTemplate(
            key = "refactor",
            label = "Code Refactor",
            color = "purple",
            prefix = "Refactor and optimize the following code: ",
            enableCodeMode = true,
        ),
        PromptTemplate(
            key = "translate",
            label = "Language Translation",
            color = "pink",
            prefix = "Translate the following text into natural English: ",
        ),
    )

    val SUGGESTION_PROMPTS: Map<String, String> = linkedMapOf(
        "write" to "Help me write something clear and natural about ",
        "learn" to "Explain this carefully, starting simple then going deeper: ",
        "code" to "Help me write clean, well-structured code for ",
        "life" to "I want a thoughtful take on ",
        "choice" to "Surprise me with something interesting to think about today.",
    )

    const val CODE_MODE_SYSTEM_PROMPT =
        "You are an expert lead software engineer. Write clean, complete, production-ready code. " +
            "Do not use placeholders like \"// rest of code here\". Include proper imports, error handling, and concise comments."

    const val AGENT_SYSTEM_PROMPT =
        "You have access to a sandboxed Linux VM with Python 3 and bash. Prefer running code over reasoning about what code would produce — " +
            "actually execute it and report what really happened. Each run_python call starts a fresh process, so write self-contained snippets and use files to carry state between them. " +
            "Print the results you want to keep. When you have the answer, state it plainly rather than only showing the code."

    val LENGTH_HINTS: Map<String, String> = mapOf(
        "long" to "The user requested a longer, more detailed response. Expand your answer thoroughly — cover all relevant aspects, include examples and reasoning, and do not truncate or summarize.",
        "short" to "The user requested a concise response. Keep your answer short and to the point — brief, direct, and without unnecessary detail.",
    )

    val AGENT_TOOLS_JSON: JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("name", "run_python")
                put(
                    "description",
                    "Execute Python 3 code in a sandboxed Linux VM and return its stdout and stderr. " +
                        "Use this for calculations, data processing, and anything you would otherwise have to reason about step by step. " +
                        "Packages can be installed with run_bash and pip. State does NOT persist between calls, so each snippet must be self-contained.",
                )
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "code",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The Python source to run. Print anything you want to see.")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("code")) })
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("name", "run_bash")
                put(
                    "description",
                    "Execute a bash script in the same sandboxed Linux VM and return its stdout and stderr. " +
                        "Use for installing packages, inspecting the filesystem, and running command line tools.",
                )
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "script",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The bash script to run.")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("script")) })
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("name", "write_file")
                put(
                    "description",
                    "Write a text file inside the sandbox. Files persist for the lifetime of the sandbox, so this is how you carry state between run_python calls.",
                )
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Absolute path, e.g. /home/user/data.csv")
                                    },
                                )
                                put(
                                    "content",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The full file contents.")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("path"))
                                add(JsonPrimitive("content"))
                            },
                        )
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("name", "read_file")
                put("description", "Read a text file back from the sandbox.")
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Absolute path to read.")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    },
                )
            },
        )
    }

    val PROVIDERS: Map<String, ProviderConfig> = linkedMapOf(
        "openai" to ProviderConfig(
            id = "openai",
            name = "OpenAI",
            apiKeyPlaceholder = "sk-proj-...",
            defaultEndpoint = "https://api.openai.com/v1",
            models = listOf(
                ModelPreset("gpt-6.1-sol", "GPT-6.1 Sol", 1050000, listOf("low", "medium", "high", "xhigh", "max"), "medium"),
                ModelPreset("gpt-6-astra", "GPT-6 Astra", 1050000, listOf("low", "medium", "high", "xhigh", "max"), "medium"),
                ModelPreset("gpt-6-luna", "GPT-6 Luna", 1050000, listOf("none", "low", "medium", "high", "xhigh", "max"), "medium"),
            ),
        ),
        "anthropic" to ProviderConfig(
            id = "anthropic",
            name = "Anthropic",
            apiKeyPlaceholder = "sk-ant-api03-...",
            defaultEndpoint = "https://api.anthropic.com/v1",
            models = listOf(
                ModelPreset("claude-fable-5-1", "Claude Fable 5.1", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-opus-5-5", "Claude Opus 5.5", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-sonnet-5-5", "Claude Sonnet 5.5", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-haiku-5-5", "Claude Haiku 5.5", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-fable-5", "Claude Fable 5 (legacy)", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-opus-5", "Claude Opus 5 (legacy)", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-opus-4-8", "Claude Opus 4.8 (legacy)", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-sonnet-5", "Claude Sonnet 5 (legacy)", 1000000, listOf("standard", "high", "max"), "standard"),
                ModelPreset("claude-haiku-4-5", "Claude Haiku 4.5 (legacy)", 200000, listOf("standard", "high", "max"), "standard"),
            ),
        ),
        "google" to ProviderConfig(
            id = "google",
            name = "Google AI Studio",
            apiKeyPlaceholder = "AQ...",
            defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta",
            models = listOf(
                ModelPreset("gemini-4-argon", "Gemini 4 Argon", 1048576, listOf("standard", "high", "max"), "standard"),
                ModelPreset("gemini-3.8-flash", "Gemini 3.8 Flash", 1048576, listOf("standard", "high", "max"), "standard"),
                ModelPreset("gemini-3.6-flash", "Gemini 3.6 Flash", 1048576, listOf("standard", "high", "max"), "standard"),
                ModelPreset("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", 1048576, listOf("standard", "high", "max"), "standard"),
                ModelPreset("gemini-3.1-pro-preview", "Gemini 3.1 Pro (preview)", 1048576, listOf("standard", "high", "max"), "standard"),
                ModelPreset("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", 1048576, listOf("standard", "high", "max"), "standard"),
            ),
        ),
        "deepinfra" to ProviderConfig(
            id = "deepinfra",
            name = "DeepInfra",
            apiKeyPlaceholder = "Enter DeepInfra API key...",
            defaultEndpoint = "https://api.deepinfra.com/v1/openai",
            models = listOf(
                ModelPreset("deepseek-ai/DeepSeek-V4-Pro", "DeepSeek V4 Pro", 1048576, listOf("none", "low", "medium", "high"), "high", vision = false),
                ModelPreset("deepseek-ai/DeepSeek-V4.1-Flash-1017", "DeepSeek V4.1 Flash", 1048576, listOf("none", "low", "medium", "high"), "high"),
                ModelPreset("moonshotai/Kimi-K3", "Kimi K3", 1048576, listOf("none", "low", "medium", "high"), "high"),
                ModelPreset("zai-org/GLM-5.3", "GLM-5.3", 1048576, listOf("none", "low", "medium", "high"), "high", vision = false),
                ModelPreset("Qwen/Qwen3.6-35B-A3B", "Qwen3.6 35B A3B", 262144, vision = false),
                ModelPreset("google/gemma-4-31B-it", "Gemma 4 31B", 262144),
                ModelPreset("nvidia/NVIDIA-Nemotron-3-Ultra-550B-A55B", "Nemotron 3 Ultra 550B", 262144, vision = false),
                ModelPreset("XiaomiMiMo/MiMo-V2.6-Pro", "MiMo V2.6 Pro", 1048576),
            ),
        ),
        "nvidia" to ProviderConfig(
            id = "nvidia",
            name = "NVIDIA NIM",
            apiKeyPlaceholder = "nvapi-...",
            defaultEndpoint = "https://integrate.api.nvidia.com/v1",
            models = listOf(
                ModelPreset("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron 3 Ultra 550B", 262144, vision = false),
                ModelPreset("nvidia/nemotron-3-super-120b-a12b", "Nemotron 3 Super 120B", 262144, vision = false),
                ModelPreset("nvidia/nemotron-3-nano-30b-a3b", "Nemotron 3 Nano 30B", 131072, vision = false),
                ModelPreset("nvidia/nvidia-nemotron-nano-9b-v2", "Nemotron Nano 9B v2", 131072, vision = false),
                ModelPreset("deepseek-ai/deepseek-v4-pro", "DeepSeek V4 Pro", 1048576, listOf("low", "high", "max"), "high", vision = false),
                ModelPreset("deepseek-ai/deepseek-v4-flash", "DeepSeek V4 Flash", 1048576, listOf("low", "high", "max"), "high"),
                ModelPreset("openai/gpt-oss-120b", "GPT-OSS 120B", 131072, listOf("low", "medium", "high"), "medium", vision = false),
                ModelPreset("openai/gpt-oss-20b", "GPT-OSS 20B", 131072, listOf("low", "medium", "high"), "medium", vision = false),
                ModelPreset("moonshotai/kimi-k3", "Kimi K3", 1048576),
                ModelPreset("z-ai/glm-5.3", "GLM-5.3", 1048576, vision = false),
                ModelPreset("z-ai/glm-5.3-flash", "GLM-5.3 Flash", 1048576),
                ModelPreset("mistralai/mistral-nemotron", "Mistral Nemotron", 131072, vision = false),
                ModelPreset("qwen/qwen3-next-80b-a3b-instruct", "Qwen3 Next 80B", 131072, vision = false),
                ModelPreset("thinkingmachines/inkling", "Inkling", 524288),
            ),
        ),
        "gmicloud" to ProviderConfig(
            id = "gmicloud",
            name = "GMI Cloud",
            apiKeyPlaceholder = "Enter GMI Cloud API key...",
            defaultEndpoint = "https://api.gmi-serving.com/v1",
            models = listOf(
                ModelPreset("openai/gpt-5.5", "GPT-5.5", 409600),
                ModelPreset("openai/gpt-5.4", "GPT-5.4", 409600),
                ModelPreset("anthropic/claude-opus-4.7", "Claude Opus 4.7", 409600),
                ModelPreset("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", 409600),
                ModelPreset("deepseek-ai/DeepSeek-V4-Pro", "DeepSeek V4 Pro", 1048576, vision = false),
                ModelPreset("deepseek-ai/DeepSeek-V4-Flash", "DeepSeek V4 Flash", 1048576),
                ModelPreset("moonshotai/Kimi-K2.6", "Kimi K2.6", 262144),
                ModelPreset("zai-org/GLM-5.1-FP8", "GLM-5.1 FP8", 202752, vision = false),
                ModelPreset("Qwen/Qwen3.7-Max", "Qwen3.7 Max", 1048576, vision = false),
                ModelPreset("Qwen/Qwen3.5-397B-A17B", "Qwen3.5 397B A17B", 262144, vision = false),
                ModelPreset("google/gemini-3.1-pro-preview", "Gemini 3.1 Pro (preview)", 1048576),
                ModelPreset("MiniMaxAI/MiniMax-M2.7", "MiniMax M2.7", 196608),
                ModelPreset("google/gemma-4-31b-it", "Gemma 4 31B", 262144),
            ),
        ),
        "moonshot" to ProviderConfig(
            id = "moonshot",
            name = "Moonshot AI",
            apiKeyPlaceholder = "Enter Kimi API key...",
            defaultEndpoint = "https://api.moonshot.ai/v1",
            models = listOf(
                ModelPreset("kimi-k3.1-pro", "Kimi K3.1 Pro", 1048576, listOf("low", "high", "max"), "max"),
                ModelPreset("kimi-k3.1-pro-highspeed", "Kimi K3.1 Pro Highspeed", 1048576, listOf("low", "high", "max"), "max"),
                ModelPreset("kimi-k3", "Kimi K3", 1048576, listOf("low", "high", "max"), "max"),
                ModelPreset("kimi-k2.7-code", "Kimi K2.7 Code", 262144),
            ),
        ),
        "together" to ProviderConfig(
            id = "together",
            name = "Together AI",
            apiKeyPlaceholder = "Enter Together API key...",
            defaultEndpoint = "https://api.together.xyz/v1",
            models = listOf(
                ModelPreset("deepseek-ai/DeepSeek-V4-Pro-0813", "DeepSeek V4 Pro", 1048576, listOf("low", "high", "max"), "high", vision = false),
                ModelPreset("deepseek-ai/DeepSeek-V4.1-Flash-1017", "DeepSeek V4.1 Flash", 1000000, listOf("low", "high", "max"), "high"),
                ModelPreset("moonshotai/Kimi-K3", "Kimi K3", 1048576),
                ModelPreset("zai-org/GLM-5.3", "GLM-5.3", 1048575, listOf("low", "high", "max"), "high", vision = false),
                ModelPreset("zai-org/GLM-5.3-Flash", "GLM-5.3 Flash", 1048575, listOf("low", "high", "max"), "high"),
                ModelPreset("MiniMaxAI/MiniMax-M3", "MiniMax M3", 524288),
                ModelPreset("nvidia/NVIDIA-Nemotron-3.5-Super-VL", "Nemotron 3.5 Super VL", 262144),
                ModelPreset("openai/gpt-oss-120b", "GPT-OSS 120B", 131072, listOf("low", "medium", "high"), "medium", vision = false),
                ModelPreset("Qwen/Qwen3.5-9B", "Qwen3.5 9B", 262144, vision = false),
                ModelPreset("Qwen/Qwen3.6-Plus", "Qwen3.6 Plus", 1000000, vision = false),
                ModelPreset("Qwen/Qwen3.7-Plus", "Qwen3.7 Plus", 1000000, vision = false),
                ModelPreset("Qwen/Qwen3.8-Flash", "Qwen3.8 Flash", 1000000),
                ModelPreset("thinkingmachines/Inkling", "Inkling", 524288),
                ModelPreset("meta-models/Muse-Spark-Open-816B-A42B", "Muse Spark Open 816B", 1048576),
                ModelPreset("reflection/Beam-1.0-501B-A23B", "Beam 1.0 501B", 1048576),
                ModelPreset("Prism-ML/Ternary-Bonsai-27B", "Ternary Bonsai 27B", 262144),
            ),
        ),
        "deepseek" to ProviderConfig(
            id = "deepseek",
            name = "DeepSeek",
            apiKeyPlaceholder = "sk-...",
            defaultEndpoint = "https://api.deepseek.com",
            models = listOf(
                ModelPreset("deepseek-v4-pro", "DeepSeek V4 Pro", 1048576, listOf("low", "high", "max"), "high", vision = false),
                ModelPreset("deepseek-flash", "DeepSeek Flash", 1048576, listOf("low", "high", "max"), "high"),
            ),
        ),
        "xai" to ProviderConfig(
            id = "xai",
            name = "xAI Grok",
            apiKeyPlaceholder = "xai-...",
            defaultEndpoint = "https://api.x.ai/v1",
            models = listOf(
                ModelPreset("grok-4.7", "Grok 4.7", 500000, listOf("low", "medium", "high"), "high"),
                ModelPreset("grok-4.6", "Grok 4.6", 500000, listOf("low", "medium", "high"), "high"),
                ModelPreset("grok-4.3", "Grok 4.3", 1000000, listOf("low", "medium", "high"), "high"),
                ModelPreset("grok-4.20-0309-reasoning", "Grok 4.20 Reasoning", 1000000, listOf("low", "medium", "high"), "high"),
            ),
        ),
        "openrouter" to ProviderConfig(
            id = "openrouter",
            name = "OpenRouter",
            apiKeyPlaceholder = "sk-or-...",
            defaultEndpoint = "https://openrouter.ai/api/v1",
            models = listOf(
                ModelPreset("openai/gpt-6.1-sol", "GPT-6.1 Sol", 1050000, listOf("low", "medium", "high", "xhigh", "max"), "medium"),
                ModelPreset("openai/gpt-6-astra", "GPT-6 Astra", 1050000, listOf("low", "medium", "high", "xhigh", "max"), "medium"),
                ModelPreset("openai/gpt-6-luna", "GPT-6 Luna", 1050000, listOf("none", "low", "medium", "high", "xhigh", "max"), "medium"),
                ModelPreset("anthropic/claude-fable-5.1", "Claude Fable 5.1", 1000000, listOf("low", "medium", "high", "xhigh", "max"), "high"),
                ModelPreset("anthropic/claude-opus-5.5", "Claude Opus 5.5", 1000000, listOf("low", "medium", "high", "xhigh", "max"), "high"),
                ModelPreset("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", 1000000, listOf("low", "medium", "high", "xhigh", "max"), "high"),
                ModelPreset("anthropic/claude-haiku-5.5", "Claude Haiku 5.5", 1000000, listOf("none", "low", "medium", "high", "xhigh", "max"), "medium"),
                ModelPreset("google/gemini-4-argon", "Gemini 4 Argon", 1048576, listOf("low", "medium", "high"), "medium"),
                ModelPreset("google/gemini-3.8-flash", "Gemini 3.8 Flash", 1048576, listOf("low", "medium", "high"), "medium"),
                ModelPreset("google/gemini-3.1-pro-preview", "Gemini 3.1 Pro (preview)", 1048576, listOf("low", "medium", "high"), "medium"),
                ModelPreset("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro", 1048576, listOf("none", "high", "xhigh"), "high", vision = false),
                ModelPreset("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 1048576, listOf("none", "low", "high", "max"), "high"),
                ModelPreset("x-ai/grok-4.7", "Grok 4.7", 500000, listOf("low", "medium", "high", "xhigh"), "high"),
                ModelPreset("x-ai/grok-4.3", "Grok 4.3", 1000000, listOf("none", "low", "medium", "high"), "low"),
                ModelPreset("moonshotai/kimi-k3.1-pro", "Kimi K3.1 Pro", 1048576, listOf("none", "low", "high", "max"), "max"),
                ModelPreset("moonshotai/kimi-k3", "Kimi K3", 1048576, listOf("none", "low", "high", "max"), "max"),
                ModelPreset("z-ai/glm-5.3", "GLM-5.3", 1048576, listOf("low", "high", "max"), "max", vision = false),
                ModelPreset("z-ai/glm-5.3-flash", "GLM-5.3 Flash", 1048576, listOf("low", "high", "max"), "max"),
                ModelPreset("openai/gpt-oss-120b", "GPT-OSS 120B", 131072, listOf("low", "medium", "high"), "medium", vision = false),
                ModelPreset("qwen/qwen3.7-max", "Qwen3.7 Max", 1000000, vision = false),
                ModelPreset("qwen/qwen3.8-flash", "Qwen3.8 Flash", 1000000),
                ModelPreset("qwen/qwen3.8-2.4t-a95b", "Qwen3.8 2.4T A95B", 1048576, listOf("low", "medium", "xhigh"), "xhigh", vision = false),
            ),
        ),
        "custom" to ProviderConfig(
            id = "custom",
            name = "Custom",
            apiKeyPlaceholder = "Enter API key...",
            defaultEndpoint = "",
            models = emptyList(),
            effort = CUSTOM_DEFAULT_EFFORT,
            effortDefault = "medium",
        ),
    )

    // Matches the order in <select id="settingProvider"> in index.html
    val SETTINGS_PROVIDER_ORDER: List<String> = listOf(
        "openai",
        "anthropic",
        "google",
        "deepinfra",
        "nvidia",
        "moonshot",
        "gmicloud",
        "together",
        "deepseek",
        "xai",
        "openrouter",
        "custom",
    )

    fun isNativeSearchProvider(providerId: String): Boolean =
        providerId == "anthropic" || providerId == "google" || providerId == "openrouter"

    fun getProviderConfig(providerId: String): ProviderConfig? = PROVIDERS[providerId]

    fun getProviderDisplayName(providerId: String): String =
        if (providerId == "custom") "Custom (OpenAI-compatible)" else PROVIDERS[providerId]?.name ?: providerId
}

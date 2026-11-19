//! Provider and model catalog. Mirrors the lists in the web client's `PROVIDERS`.

use crate::settings::Settings;

#[derive(Clone, Copy, Debug)]
pub struct ModelInfo {
    pub id: &'static str,
    pub name: &'static str,
    pub ctx: u32,
    pub effort: &'static [&'static str],
    pub effort_default: &'static str,
    pub vision: bool,
    pub tools: bool,
}

#[derive(Clone, Copy, Debug)]
pub struct ProviderInfo {
    pub id: &'static str,
    pub name: &'static str,
    pub key_hint: &'static str,
    pub models: &'static [ModelInfo],
    pub effort: &'static [&'static str],
    pub effort_default: &'static str,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ModelChoice {
    pub id: String,
    pub name: String,
    pub ctx: u32,
    pub effort: Vec<String>,
    pub effort_default: String,
    pub vision: bool,
    pub tools: bool,
    pub custom: bool,
}

const OPENAI_EFFORT: &[&str] = &["low", "medium", "high", "xhigh", "max"];
const OPENAI_LUNA_EFFORT: &[&str] = &["none", "low", "medium", "high", "xhigh", "max"];
const ANTHROPIC_EFFORT: &[&str] = &["standard", "high", "max"];
const GOOGLE_EFFORT: &[&str] = &["standard", "high", "max"];
const OR_HIGH: &[&str] = &["low", "medium", "high", "xhigh", "max"];
const OR_MED: &[&str] = &["none", "low", "medium", "high", "xhigh", "max"];
const DS_NONE: &[&str] = &["none", "low", "medium", "high"];
const DS_LH: &[&str] = &["low", "high", "max"];
const DS_LMH: &[&str] = &["low", "medium", "high"];
const XAI_EFFORT: &[&str] = &["low", "medium", "high"];
const CUSTOM_EFFORT: &[&str] = &["low", "medium", "high", "xhigh", "max"];

const fn m(id: &'static str, name: &'static str, ctx: u32) -> ModelInfo {
    ModelInfo {
        id,
        name,
        ctx,
        effort: &[],
        effort_default: "",
        vision: true,
        tools: true,
    }
}

const fn me(
    id: &'static str,
    name: &'static str,
    ctx: u32,
    effort: &'static [&'static str],
    effort_default: &'static str,
) -> ModelInfo {
    ModelInfo {
        id,
        name,
        ctx,
        effort,
        effort_default,
        vision: true,
        tools: true,
    }
}

const fn mev(
    id: &'static str,
    name: &'static str,
    ctx: u32,
    effort: &'static [&'static str],
    effort_default: &'static str,
    vision: bool,
) -> ModelInfo {
    ModelInfo {
        id,
        name,
        ctx,
        effort,
        effort_default,
        vision,
        tools: true,
    }
}

const OPENAI: &[ModelInfo] = &[
    me(
        "gpt-6.1-sol",
        "GPT-6.1 Sol",
        1_050_000,
        OPENAI_EFFORT,
        "medium",
    ),
    me(
        "gpt-6-astra",
        "GPT-6 Astra",
        1_050_000,
        OPENAI_EFFORT,
        "medium",
    ),
    me(
        "gpt-6-luna",
        "GPT-6 Luna",
        1_050_000,
        OPENAI_LUNA_EFFORT,
        "medium",
    ),
];

const ANTHROPIC: &[ModelInfo] = &[
    me(
        "claude-fable-5-1",
        "Claude Fable 5.1",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-opus-5-5",
        "Claude Opus 5.5",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-sonnet-5-5",
        "Claude Sonnet 5.5",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-haiku-5-5",
        "Claude Haiku 5.5",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-fable-5",
        "Claude Fable 5 (legacy)",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-opus-5",
        "Claude Opus 5 (legacy)",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-opus-4-8",
        "Claude Opus 4.8 (legacy)",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-sonnet-5",
        "Claude Sonnet 5 (legacy)",
        1_000_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
    me(
        "claude-haiku-4-5",
        "Claude Haiku 4.5 (legacy)",
        200_000,
        ANTHROPIC_EFFORT,
        "standard",
    ),
];

const GOOGLE: &[ModelInfo] = &[
    me(
        "gemini-4-argon",
        "Gemini 4 Argon",
        1_048_576,
        GOOGLE_EFFORT,
        "standard",
    ),
    me(
        "gemini-3.8-flash",
        "Gemini 3.8 Flash",
        1_048_576,
        GOOGLE_EFFORT,
        "standard",
    ),
    me(
        "gemini-3.6-flash",
        "Gemini 3.6 Flash",
        1_048_576,
        GOOGLE_EFFORT,
        "standard",
    ),
    me(
        "gemini-3.5-flash-lite",
        "Gemini 3.5 Flash-Lite",
        1_048_576,
        GOOGLE_EFFORT,
        "standard",
    ),
    me(
        "gemini-3.1-pro-preview",
        "Gemini 3.1 Pro (preview)",
        1_048_576,
        GOOGLE_EFFORT,
        "standard",
    ),
    me(
        "gemini-3.1-flash-lite",
        "Gemini 3.1 Flash-Lite",
        1_048_576,
        GOOGLE_EFFORT,
        "standard",
    ),
];

const DEEPINFRA: &[ModelInfo] = &[
    mev(
        "deepseek-ai/DeepSeek-V4-Pro",
        "DeepSeek V4 Pro",
        1_048_576,
        DS_NONE,
        "high",
        false,
    ),
    me(
        "deepseek-ai/DeepSeek-V4.1-Flash-1017",
        "DeepSeek V4.1 Flash",
        1_048_576,
        DS_NONE,
        "high",
    ),
    me("moonshotai/Kimi-K3", "Kimi K3", 1_048_576, DS_NONE, "high"),
    mev(
        "zai-org/GLM-5.3",
        "GLM-5.3",
        1_048_576,
        DS_NONE,
        "high",
        false,
    ),
    mev(
        "Qwen/Qwen3.6-35B-A3B",
        "Qwen3.6 35B A3B",
        262_144,
        &[],
        "",
        false,
    ),
    m("google/gemma-4-31B-it", "Gemma 4 31B", 262_144),
    mev(
        "nvidia/NVIDIA-Nemotron-3-Ultra-550B-A55B",
        "Nemotron 3 Ultra 550B",
        262_144,
        &[],
        "",
        false,
    ),
    m("XiaomiMiMo/MiMo-V2.6-Pro", "MiMo V2.6 Pro", 1_048_576),
];

const NVIDIA: &[ModelInfo] = &[
    mev(
        "nvidia/nemotron-3-ultra-550b-a55b",
        "Nemotron 3 Ultra 550B",
        262_144,
        &[],
        "",
        false,
    ),
    mev(
        "nvidia/nemotron-3-super-120b-a12b",
        "Nemotron 3 Super 120B",
        262_144,
        &[],
        "",
        false,
    ),
    mev(
        "nvidia/nemotron-3-nano-30b-a3b",
        "Nemotron 3 Nano 30B",
        131_072,
        &[],
        "",
        false,
    ),
    mev(
        "nvidia/nvidia-nemotron-nano-9b-v2",
        "Nemotron Nano 9B v2",
        131_072,
        &[],
        "",
        false,
    ),
    mev(
        "deepseek-ai/deepseek-v4-pro",
        "DeepSeek V4 Pro",
        1_048_576,
        DS_LH,
        "high",
        false,
    ),
    me(
        "deepseek-ai/deepseek-v4-flash",
        "DeepSeek V4 Flash",
        1_048_576,
        DS_LH,
        "high",
    ),
    mev(
        "openai/gpt-oss-120b",
        "GPT-OSS 120B",
        131_072,
        DS_LMH,
        "medium",
        false,
    ),
    mev(
        "openai/gpt-oss-20b",
        "GPT-OSS 20B",
        131_072,
        DS_LMH,
        "medium",
        false,
    ),
    m("moonshotai/kimi-k3", "Kimi K3", 1_048_576),
    mev("z-ai/glm-5.3", "GLM-5.3", 1_048_576, &[], "", false),
    m("z-ai/glm-5.3-flash", "GLM-5.3 Flash", 1_048_576),
    mev(
        "mistralai/mistral-nemotron",
        "Mistral Nemotron",
        131_072,
        &[],
        "",
        false,
    ),
    mev(
        "qwen/qwen3-next-80b-a3b-instruct",
        "Qwen3 Next 80B",
        131_072,
        &[],
        "",
        false,
    ),
    m("thinkingmachines/inkling", "Inkling", 524_288),
];

const GMICLOUD: &[ModelInfo] = &[
    m("openai/gpt-5.5", "GPT-5.5", 409_600),
    m("openai/gpt-5.4", "GPT-5.4", 409_600),
    m("anthropic/claude-opus-4.7", "Claude Opus 4.7", 409_600),
    m("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", 409_600),
    mev(
        "deepseek-ai/DeepSeek-V4-Pro",
        "DeepSeek V4 Pro",
        1_048_576,
        &[],
        "",
        false,
    ),
    m(
        "deepseek-ai/DeepSeek-V4-Flash",
        "DeepSeek V4 Flash",
        1_048_576,
    ),
    m("moonshotai/Kimi-K2.6", "Kimi K2.6", 262_144),
    mev(
        "zai-org/GLM-5.1-FP8",
        "GLM-5.1 FP8",
        202_752,
        &[],
        "",
        false,
    ),
    mev("Qwen/Qwen3.7-Max", "Qwen3.7 Max", 1_048_576, &[], "", false),
    mev(
        "Qwen/Qwen3.5-397B-A17B",
        "Qwen3.5 397B A17B",
        262_144,
        &[],
        "",
        false,
    ),
    m(
        "google/gemini-3.1-pro-preview",
        "Gemini 3.1 Pro (preview)",
        1_048_576,
    ),
    m("MiniMaxAI/MiniMax-M2.7", "MiniMax M2.7", 196_608),
    m("google/gemma-4-31b-it", "Gemma 4 31B", 262_144),
];

const MOONSHOT: &[ModelInfo] = &[
    me("kimi-k3.1-pro", "Kimi K3.1 Pro", 1_048_576, DS_LH, "max"),
    me(
        "kimi-k3.1-pro-highspeed",
        "Kimi K3.1 Pro Highspeed",
        1_048_576,
        DS_LH,
        "max",
    ),
    me("kimi-k3", "Kimi K3", 1_048_576, DS_LH, "max"),
    m("kimi-k2.7-code", "Kimi K2.7 Code", 262_144),
];

const TOGETHER: &[ModelInfo] = &[
    mev(
        "deepseek-ai/DeepSeek-V4-Pro-0813",
        "DeepSeek V4 Pro",
        1_048_576,
        DS_LH,
        "high",
        false,
    ),
    me(
        "deepseek-ai/DeepSeek-V4.1-Flash-1017",
        "DeepSeek V4.1 Flash",
        1_000_000,
        DS_LH,
        "high",
    ),
    m("moonshotai/Kimi-K3", "Kimi K3", 1_048_576),
    mev(
        "zai-org/GLM-5.3",
        "GLM-5.3",
        1_048_575,
        DS_LH,
        "high",
        false,
    ),
    me(
        "zai-org/GLM-5.3-Flash",
        "GLM-5.3 Flash",
        1_048_575,
        DS_LH,
        "high",
    ),
    m("MiniMaxAI/MiniMax-M3", "MiniMax M3", 524_288),
    m(
        "nvidia/NVIDIA-Nemotron-3.5-Super-VL",
        "Nemotron 3.5 Super VL",
        262_144,
    ),
    mev(
        "openai/gpt-oss-120b",
        "GPT-OSS 120B",
        131_072,
        DS_LMH,
        "medium",
        false,
    ),
    mev("Qwen/Qwen3.5-9B", "Qwen3.5 9B", 262_144, &[], "", false),
    mev(
        "Qwen/Qwen3.6-Plus",
        "Qwen3.6 Plus",
        1_000_000,
        &[],
        "",
        false,
    ),
    mev(
        "Qwen/Qwen3.7-Plus",
        "Qwen3.7 Plus",
        1_000_000,
        &[],
        "",
        false,
    ),
    m("Qwen/Qwen3.8-Flash", "Qwen3.8 Flash", 1_000_000),
    m("thinkingmachines/Inkling", "Inkling", 524_288),
    m(
        "meta-models/Muse-Spark-Open-816B-A42B",
        "Muse Spark Open 816B",
        1_048_576,
    ),
    m("reflection/Beam-1.0-501B-A23B", "Beam 1.0 501B", 1_048_576),
    m("Prism-ML/Ternary-Bonsai-27B", "Ternary Bonsai 27B", 262_144),
];

const DEEPSEEK: &[ModelInfo] = &[
    mev(
        "deepseek-v4-pro",
        "DeepSeek V4 Pro",
        1_048_576,
        DS_LH,
        "high",
        false,
    ),
    me("deepseek-flash", "DeepSeek Flash", 1_048_576, DS_LH, "high"),
];

const XAI: &[ModelInfo] = &[
    me("grok-4.7", "Grok 4.7", 500_000, XAI_EFFORT, "high"),
    me("grok-4.6", "Grok 4.6", 500_000, XAI_EFFORT, "high"),
    me("grok-4.3", "Grok 4.3", 1_000_000, XAI_EFFORT, "high"),
    me(
        "grok-4.20-0309-reasoning",
        "Grok 4.20 Reasoning",
        1_000_000,
        XAI_EFFORT,
        "high",
    ),
];

const OPENROUTER: &[ModelInfo] = &[
    me(
        "openai/gpt-6.1-sol",
        "GPT-6.1 Sol",
        1_050_000,
        OPENAI_EFFORT,
        "medium",
    ),
    me(
        "openai/gpt-6-astra",
        "GPT-6 Astra",
        1_050_000,
        OPENAI_EFFORT,
        "medium",
    ),
    me(
        "openai/gpt-6-luna",
        "GPT-6 Luna",
        1_050_000,
        OPENAI_LUNA_EFFORT,
        "medium",
    ),
    me(
        "anthropic/claude-fable-5.1",
        "Claude Fable 5.1",
        1_000_000,
        OR_HIGH,
        "high",
    ),
    me(
        "anthropic/claude-opus-5.5",
        "Claude Opus 5.5",
        1_000_000,
        OR_HIGH,
        "high",
    ),
    me(
        "anthropic/claude-sonnet-5.5",
        "Claude Sonnet 5.5",
        1_000_000,
        OR_HIGH,
        "high",
    ),
    me(
        "anthropic/claude-haiku-5.5",
        "Claude Haiku 5.5",
        1_000_000,
        OR_MED,
        "medium",
    ),
    me(
        "google/gemini-4-argon",
        "Gemini 4 Argon",
        1_048_576,
        XAI_EFFORT,
        "medium",
    ),
    me(
        "google/gemini-3.8-flash",
        "Gemini 3.8 Flash",
        1_048_576,
        XAI_EFFORT,
        "medium",
    ),
    me(
        "google/gemini-3.1-pro-preview",
        "Gemini 3.1 Pro (preview)",
        1_048_576,
        XAI_EFFORT,
        "medium",
    ),
    mev(
        "deepseek/deepseek-v4-pro",
        "DeepSeek V4 Pro",
        1_048_576,
        &["none", "high", "xhigh"],
        "high",
        false,
    ),
    me(
        "deepseek/deepseek-v4.1-flash",
        "DeepSeek V4.1 Flash",
        1_048_576,
        &["none", "low", "high", "max"],
        "high",
    ),
    me(
        "x-ai/grok-4.7",
        "Grok 4.7",
        500_000,
        &["low", "medium", "high", "xhigh"],
        "high",
    ),
    me(
        "x-ai/grok-4.3",
        "Grok 4.3",
        1_000_000,
        &["none", "low", "medium", "high"],
        "low",
    ),
    me(
        "moonshotai/kimi-k3.1-pro",
        "Kimi K3.1 Pro",
        1_048_576,
        &["none", "low", "high", "max"],
        "max",
    ),
    me(
        "moonshotai/kimi-k3",
        "Kimi K3",
        1_048_576,
        &["none", "low", "high", "max"],
        "max",
    ),
    mev("z-ai/glm-5.3", "GLM-5.3", 1_048_576, DS_LH, "max", false),
    me(
        "z-ai/glm-5.3-flash",
        "GLM-5.3 Flash",
        1_048_576,
        DS_LH,
        "max",
    ),
    mev(
        "openai/gpt-oss-120b",
        "GPT-OSS 120B",
        131_072,
        DS_LMH,
        "medium",
        false,
    ),
    mev("qwen/qwen3.7-max", "Qwen3.7 Max", 1_000_000, &[], "", false),
    m("qwen/qwen3.8-flash", "Qwen3.8 Flash", 1_000_000),
    mev(
        "qwen/qwen3.8-2.4t-a95b",
        "Qwen3.8 2.4T A95B",
        1_048_576,
        &["low", "medium", "xhigh"],
        "xhigh",
        false,
    ),
];

const PROVIDERS: &[ProviderInfo] = &[
    ProviderInfo {
        id: "openai",
        name: "OpenAI",
        key_hint: "sk-proj-...",
        models: OPENAI,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "anthropic",
        name: "Anthropic",
        key_hint: "sk-ant-api03-...",
        models: ANTHROPIC,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "google",
        name: "Google AI Studio",
        key_hint: "AQ...",
        models: GOOGLE,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "deepinfra",
        name: "DeepInfra",
        key_hint: "DeepInfra API key",
        models: DEEPINFRA,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "nvidia",
        name: "NVIDIA NIM",
        key_hint: "nvapi-...",
        models: NVIDIA,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "gmicloud",
        name: "GMI Cloud",
        key_hint: "GMI Cloud API key",
        models: GMICLOUD,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "moonshot",
        name: "Moonshot AI",
        key_hint: "Kimi API key",
        models: MOONSHOT,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "together",
        name: "Together AI",
        key_hint: "Together API key",
        models: TOGETHER,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "deepseek",
        name: "DeepSeek",
        key_hint: "sk-...",
        models: DEEPSEEK,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "xai",
        name: "xAI Grok",
        key_hint: "xai-...",
        models: XAI,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "openrouter",
        name: "OpenRouter",
        key_hint: "sk-or-...",
        models: OPENROUTER,
        effort: &[],
        effort_default: "",
    },
    ProviderInfo {
        id: "custom",
        name: "Custom",
        key_hint: "API key",
        models: &[],
        effort: CUSTOM_EFFORT,
        effort_default: "medium",
    },
];

pub fn all_providers() -> &'static [ProviderInfo] {
    PROVIDERS
}

pub fn provider(id: &str) -> Option<&'static ProviderInfo> {
    PROVIDERS.iter().find(|p| p.id == id)
}

pub fn native_search(provider_id: &str) -> bool {
    matches!(provider_id, "anthropic" | "google" | "openrouter")
}

fn choice_from_static(model: &ModelInfo, custom: bool) -> ModelChoice {
    ModelChoice {
        id: model.id.to_string(),
        name: model.name.to_string(),
        ctx: model.ctx,
        effort: model.effort.iter().map(|s| (*s).to_string()).collect(),
        effort_default: model.effort_default.to_string(),
        vision: model.vision,
        tools: model.tools,
        custom,
    }
}

pub fn available_models(settings: &Settings) -> Vec<ModelChoice> {
    let Some(info) = provider(&settings.provider) else {
        return Vec::new();
    };
    if settings.provider == "custom" {
        let mut out: Vec<ModelChoice> = settings
            .custom_models
            .iter()
            .map(|model| ModelChoice {
                id: model.id.clone(),
                name: if model.name.trim().is_empty() {
                    model.id.clone()
                } else {
                    model.name.clone()
                },
                ctx: model.ctx.max(1),
                effort: info.effort.iter().map(|s| (*s).to_string()).collect(),
                effort_default: info.effort_default.to_string(),
                vision: true,
                tools: true,
                custom: true,
            })
            .collect();
        if out.is_empty() && !settings.model.trim().is_empty() {
            out.push(ModelChoice {
                id: settings.model.clone(),
                name: settings.model.clone(),
                ctx: 8192,
                effort: info.effort.iter().map(|s| (*s).to_string()).collect(),
                effort_default: info.effort_default.to_string(),
                vision: true,
                tools: true,
                custom: true,
            });
        }
        return out;
    }
    let mut out: Vec<ModelChoice> = info
        .models
        .iter()
        .map(|model| choice_from_static(model, false))
        .collect();
    for extra in &settings.extra_models {
        if extra.provider == settings.provider && !extra.id.trim().is_empty() {
            out.push(ModelChoice {
                id: extra.id.clone(),
                name: if extra.name.trim().is_empty() {
                    extra.id.clone()
                } else {
                    extra.name.clone()
                },
                ctx: extra.ctx.max(1),
                effort: Vec::new(),
                effort_default: String::new(),
                vision: true,
                tools: true,
                custom: true,
            });
        }
    }
    out
}

pub fn allows_vision(settings: &Settings) -> bool {
    find_model(settings)
        .map(|model| model.vision)
        .unwrap_or(true)
}

pub fn context_window(settings: &Settings) -> u32 {
    find_model(settings)
        .map(|model| model.ctx.max(1))
        .unwrap_or(8192)
}

pub fn find_model(settings: &Settings) -> Option<ModelChoice> {
    available_models(settings)
        .into_iter()
        .find(|model| model.id == settings.model)
}

impl ModelChoice {
    pub fn supports_tools(&self) -> bool {
        self.tools
    }
}

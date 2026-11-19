//! Device settings. API keys live in this struct and on disk, never in the binary.

use std::collections::BTreeMap;
use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::providers::{self, provider};

pub const DEFAULT_ENDPOINT: &str = "https://omni-api.wimmboo.workers.dev";

#[derive(Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub api_endpoint: String,
    /// Origin header sent to a Worker locked with `ALLOWED_ORIGINS`.
    /// A native app has no browser origin of its own.
    pub app_origin: String,
    pub provider: String,
    pub api_key: String,
    pub api_keys: BTreeMap<String, String>,
    pub model: String,
    pub temperature: f64,
    pub max_tokens: u32,
    pub thinking: bool,
    pub effort: String,
    pub search_mode: String,
    pub search_backend: String,
    pub search_depth: String,
    pub tavily_key: String,
    pub proxy_key: String,
    pub custom_base_url: String,
    pub custom_models: Vec<CustomModel>,
    pub extra_models: Vec<ExtraModel>,
    pub system_prompt: String,
    pub sandbox_provider: String,
    pub sandbox_keys: BTreeMap<String, String>,
    pub e2b_key: String,
    pub implicit_think_models: BTreeMap<String, bool>,
    pub agent_max_steps: u32,
    pub agent_sandbox_timeout: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct CustomModel {
    pub id: String,
    pub name: String,
    pub ctx: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct ExtraModel {
    pub provider: String,
    pub id: String,
    pub name: String,
    pub ctx: u32,
}

impl Default for CustomModel {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            ctx: 8192,
        }
    }
}

impl Default for ExtraModel {
    fn default() -> Self {
        Self {
            provider: String::new(),
            id: String::new(),
            name: String::new(),
            ctx: 8192,
        }
    }
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            api_endpoint: DEFAULT_ENDPOINT.to_string(),
            app_origin: String::new(),
            provider: "openai".into(),
            api_key: String::new(),
            api_keys: BTreeMap::new(),
            model: "gpt-6.1-sol".into(),
            temperature: 0.7,
            max_tokens: 2048,
            thinking: true,
            effort: "high".into(),
            search_mode: "auto".into(),
            search_backend: "auto".into(),
            search_depth: "auto".into(),
            tavily_key: String::new(),
            proxy_key: String::new(),
            custom_base_url: String::new(),
            custom_models: Vec::new(),
            extra_models: Vec::new(),
            system_prompt: String::new(),
            sandbox_provider: "e2b".into(),
            sandbox_keys: BTreeMap::new(),
            e2b_key: String::new(),
            implicit_think_models: BTreeMap::new(),
            agent_max_steps: 8,
            agent_sandbox_timeout: 120,
        }
    }
}

impl fmt::Debug for Settings {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Settings")
            .field("api_endpoint", &self.api_endpoint)
            .field("provider", &self.provider)
            .field("model", &self.model)
            .field("api_key", &redacted(&self.api_key))
            .field("api_keys", &redacted_map(&self.api_keys))
            .field("tavily_key", &redacted(&self.tavily_key))
            .field("proxy_key", &redacted(&self.proxy_key))
            .field("sandbox_keys", &redacted_map(&self.sandbox_keys))
            .field("e2b_key", &redacted(&self.e2b_key))
            .field("search_mode", &self.search_mode)
            .finish()
    }
}

fn redacted(value: &str) -> &'static str {
    if value.is_empty() {
        "(empty)"
    } else {
        "[redacted]"
    }
}

fn redacted_map(map: &BTreeMap<String, String>) -> BTreeMap<&str, &'static str> {
    map.iter().map(|(k, v)| (k.as_str(), redacted(v))).collect()
}

impl Settings {
    pub fn from_json(raw: &str) -> Result<Self, String> {
        let mut value: Value = serde_json::from_str(raw)
            .map_err(|_| "Settings file is not valid JSON.".to_string())?;
        strip_nulls(&mut value);
        let mut settings: Settings = serde_json::from_value(value)
            .map_err(|_| "Settings file could not be read.".to_string())?;
        settings.normalize();
        Ok(settings)
    }

    pub fn normalize(&mut self) {
        if self.api_endpoint.trim().is_empty() {
            self.api_endpoint = DEFAULT_ENDPOINT.to_string();
        }
        self.api_endpoint = self.api_endpoint.trim().trim_end_matches('/').to_string();
        if self.provider == "cerebras"
            || self.provider == "groq"
            || provider(&self.provider).is_none()
        {
            self.provider = "openai".into();
            self.model = Settings::default().model;
        }
        self.temperature = self.temperature.clamp(0.0, 2.0);
        self.max_tokens = self.max_tokens.clamp(1, 32_768);
        self.agent_max_steps = self.agent_max_steps.clamp(1, 25);
        self.agent_sandbox_timeout = self.agent_sandbox_timeout.clamp(30, 300);
        if !matches!(self.search_mode.as_str(), "on" | "off" | "auto") {
            self.search_mode = "auto".into();
        }
        if self.search_backend != "tavily" {
            self.search_backend = "auto".into();
        }
        if !matches!(self.search_depth.as_str(), "basic" | "advanced" | "auto") {
            self.search_depth = "auto".into();
        }
        if !matches!(self.sandbox_provider.as_str(), "e2b" | "novita") {
            self.sandbox_provider = "e2b".into();
        }
        let models = providers::available_models(self);
        if !models.iter().any(|model| model.id == self.model) {
            if let Some(first) = models.first() {
                self.model = first.id.clone();
                if !first.effort_default.is_empty() {
                    self.effort = first.effort_default.clone();
                }
            }
        }
        if let Some(model) = providers::find_model(self) {
            if !model.effort.is_empty() && !model.effort.iter().any(|tier| tier == &self.effort) {
                self.effort = if model.effort_default.is_empty() {
                    model.effort[0].clone()
                } else {
                    model.effort_default.clone()
                };
            }
        }
        let current = self.api_key_for(&self.provider);
        if !current.is_empty() {
            self.api_keys.insert(self.provider.clone(), current);
        }
    }

    pub fn api_key_for(&self, provider_id: &str) -> String {
        if let Some(key) = self.api_keys.get(provider_id) {
            let trimmed = key.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }
        if provider_id == self.provider {
            return self.api_key.trim().to_string();
        }
        String::new()
    }

    pub fn set_api_key(&mut self, provider_id: &str, key: String) {
        let trimmed = key.trim().to_string();
        if trimmed.is_empty() {
            self.api_keys.remove(provider_id);
        } else {
            self.api_keys
                .insert(provider_id.to_string(), trimmed.clone());
        }
        if provider_id == self.provider {
            self.api_key = trimmed;
        }
    }

    pub fn sandbox_key(&self) -> String {
        let provider_id = if self.sandbox_provider == "novita" {
            "novita"
        } else {
            "e2b"
        };
        if let Some(key) = self.sandbox_keys.get(provider_id) {
            let trimmed = key.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }
        if provider_id == "e2b" {
            return self.e2b_key.trim().to_string();
        }
        String::new()
    }

    pub fn set_sandbox_key(&mut self, key: String) {
        let provider_id = if self.sandbox_provider == "novita" {
            "novita"
        } else {
            "e2b"
        };
        let trimmed = key.trim().to_string();
        if trimmed.is_empty() {
            self.sandbox_keys.remove(provider_id);
        } else {
            self.sandbox_keys
                .insert(provider_id.to_string(), trimmed.clone());
        }
        if provider_id == "e2b" {
            self.e2b_key = trimmed;
        }
    }

    pub fn think_model_key(&self) -> String {
        format!("{}:{}", self.provider, self.model)
    }
}

fn strip_nulls(value: &mut Value) {
    match value {
        Value::Object(map) => {
            map.retain(|_, child| !child.is_null());
            for child in map.values_mut() {
                strip_nulls(child);
            }
        }
        Value::Array(items) => {
            for child in items {
                strip_nulls(child);
            }
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_endpoint_and_removed_providers_fall_back() {
        let settings =
            Settings::from_json(r#"{"apiEndpoint":"  ","provider":"groq","model":"old"}"#).unwrap();
        assert_eq!(settings.api_endpoint, DEFAULT_ENDPOINT);
        assert_eq!(settings.provider, "openai");
        assert_eq!(settings.model, "gpt-6.1-sol");
    }

    #[test]
    fn debug_does_not_print_secrets() {
        let mut settings = Settings::default();
        settings.set_api_key("openai", "sk-test-secret-value".into());
        settings.tavily_key = "tvly-secret".into();
        settings.proxy_key = "proxy-secret".into();
        settings.set_sandbox_key("e2b_secret".into());
        let rendered = format!("{settings:?}");
        assert!(rendered.contains("[redacted]"));
        assert!(!rendered.contains("sk-test-secret-value"));
        assert!(!rendered.contains("tvly-secret"));
        assert!(!rendered.contains("proxy-secret"));
        assert!(!rendered.contains("e2b_secret"));
    }
}

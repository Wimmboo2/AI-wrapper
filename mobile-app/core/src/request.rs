//! Builds the JSON body the web client posts to `{endpoint}/api/chat`.

use serde_json::{json, Value};

use crate::providers::{self, find_model};
use crate::sandbox::{self, AGENT_PROMPT};
use crate::settings::Settings;

pub const CODE_PROMPT: &str = "You are an expert lead software engineer. Write clean, complete, production-ready code. Do not use placeholders like \"// rest of code here\". Include proper imports, error handling, and concise comments.";

#[derive(Clone, Debug)]
pub struct Outbound {
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Value,
}

#[derive(Clone, Debug, Default)]
pub struct SendOpts {
    /// `Some(true)` forces search on, `Some(false)` forces it off.
    /// `None` uses the saved search mode, unless `search_chip` is set.
    pub search_override: Option<bool>,
    pub search_chip: bool,
    pub code_mode: bool,
}

#[derive(Clone, Debug)]
pub struct BuiltRequest {
    pub outbound: Outbound,
    pub notice: Option<String>,
    pub status: String,
    /// True when the body includes the agent tool list.
    #[allow(dead_code)]
    pub agent: bool,
}

pub fn worker_headers(settings: &Settings) -> Vec<(String, String)> {
    let mut headers = vec![
        ("Content-Type".into(), "application/json".into()),
        (
            "Accept".into(),
            "application/x-ndjson, application/json".into(),
        ),
    ];
    let proxy = settings.proxy_key.trim();
    if !proxy.is_empty() {
        headers.push(("X-Omni-Proxy-Key".into(), proxy.to_string()));
    }
    let origin = settings.app_origin.trim();
    if !origin.is_empty() {
        headers.push(("Origin".into(), origin.to_string()));
    }
    headers
}

pub fn endpoint_url(settings: &Settings) -> Result<String, String> {
    let endpoint = settings.api_endpoint.trim().trim_end_matches('/');
    if endpoint.is_empty() {
        return Err("Set your API endpoint in Settings first.".into());
    }
    if !(endpoint.starts_with("https://") || endpoint.starts_with("http://"))
        || endpoint.contains(' ')
    {
        return Err("The API endpoint must be an http or https URL.".into());
    }
    Ok(endpoint.to_string())
}

pub fn build_chat_request(
    settings: &Settings,
    history: Vec<Value>,
    opts: &SendOpts,
    agent_mode: bool,
) -> Result<BuiltRequest, String> {
    let endpoint = endpoint_url(settings)?;
    let api_key = settings.api_key_for(&settings.provider);
    if api_key.is_empty() {
        return Err("Set your API key in Settings first.".into());
    }
    if settings.model.trim().is_empty() {
        return Err("Choose a model in Settings first.".into());
    }
    if settings.provider == "custom" && settings.custom_base_url.trim().is_empty() {
        return Err("Set the custom base URL in Settings first.".into());
    }

    let model = find_model(settings);
    let agent = agent_mode
        && !settings.sandbox_key().is_empty()
        && model.as_ref().map(|m| m.supports_tools()).unwrap_or(true);

    let mut notice = None;
    let mut search_mode = if opts.search_override == Some(false) {
        "off"
    } else if opts.search_override == Some(true) || opts.search_chip {
        "on"
    } else if matches!(settings.search_mode.as_str(), "on" | "off" | "auto") {
        settings.search_mode.as_str()
    } else {
        "auto"
    };

    let native = providers::native_search(&settings.provider);
    let has_tavily = !settings.tavily_key.trim().is_empty();
    let mut backend = if settings.search_backend == "tavily" {
        "tavily"
    } else {
        "auto"
    };
    if backend == "tavily" && !has_tavily && native {
        backend = "auto";
    }
    let uses_tavily = backend == "tavily" || !native;
    if search_mode != "off" && uses_tavily && !has_tavily {
        if search_mode == "on" {
            notice = Some("Add your Tavily API key in Settings to use web search.".into());
        }
        search_mode = "off";
    }

    let mut messages = history;
    if opts.code_mode {
        messages.insert(0, json!({ "role": "system", "content": CODE_PROMPT }));
    }
    if !settings.system_prompt.trim().is_empty() {
        messages.insert(
            0,
            json!({ "role": "system", "content": settings.system_prompt.trim() }),
        );
    }
    if agent {
        messages.insert(0, json!({ "role": "system", "content": AGENT_PROMPT }));
    }

    let mut body = json!({
        "provider": settings.provider,
        "model": settings.model,
        "apiKey": api_key,
        "messages": messages,
        "temperature": settings.temperature,
        "max_tokens": settings.max_tokens,
        "stream": true,
        "thinking": settings.thinking,
        "searchMode": search_mode,
        "effort": settings.effort,
    });
    if search_mode != "off" && !agent {
        body["searchBackend"] = json!(backend);
        let depth = if matches!(settings.search_depth.as_str(), "basic" | "advanced") {
            settings.search_depth.as_str()
        } else {
            "auto"
        };
        body["searchDepth"] = json!(depth);
        if uses_tavily {
            body["tavilyKey"] = json!(settings.tavily_key.trim());
        }
    }
    if agent {
        body["tools"] = sandbox::agent_tools();
        // The Worker turns search off when tools are present. Leave the key out
        // so it is not sent on a request that cannot search.
        if let Some(object) = body.as_object_mut() {
            object.remove("tavilyKey");
        }
    }
    if settings.provider == "custom" {
        body["baseURL"] = json!(settings.custom_base_url.trim());
    }
    if !settings.proxy_key.trim().is_empty() {
        body["proxyKey"] = json!(settings.proxy_key.trim());
    }

    let status = if search_mode == "on" && !agent {
        "Searching web.".into()
    } else if opts.code_mode {
        "Writing code.".into()
    } else {
        "Thinking.".into()
    };

    Ok(BuiltRequest {
        outbound: Outbound {
            url: format!("{endpoint}/api/chat"),
            headers: worker_headers(settings),
            body,
        },
        notice,
        status,
        agent,
    })
}

pub fn slash_search(text: &str) -> Option<Result<String, String>> {
    let trimmed = text.trim();
    for prefix in ["/search", "/web"] {
        if let Some(rest) = strip_command(trimmed, prefix) {
            let query = rest.trim();
            if query.is_empty() {
                return Some(Err("Type a query after /search.".into()));
            }
            return Some(Ok(query.to_string()));
        }
    }
    None
}

fn strip_command<'a>(text: &'a str, prefix: &str) -> Option<&'a str> {
    let rest = text.strip_prefix(prefix)?;
    if rest.is_empty() || rest.starts_with(char::is_whitespace) {
        Some(rest)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn ready() -> Settings {
        let mut settings = Settings::default();
        settings.set_api_key("openai", "sk-test".into());
        settings
    }

    #[test]
    fn missing_tavily_key_degrades_to_a_normal_answer() {
        let mut settings = ready();
        settings.search_mode = "on".into();
        let built = build_chat_request(
            &settings,
            vec![json!({"role":"user","content":"hi"})],
            &SendOpts::default(),
            false,
        )
        .unwrap();
        assert_eq!(built.outbound.body["searchMode"], "off");
        assert!(built.outbound.body.get("tavilyKey").is_none());
        assert!(built.notice.is_some());
        let rendered = built.outbound.body.to_string();
        assert!(!rendered.contains("tvly"));
    }

    #[test]
    fn anthropic_search_does_not_send_a_tavily_key() {
        let mut settings = ready();
        settings.provider = "anthropic".into();
        settings.model = "claude-haiku-5-5".into();
        settings.set_api_key("anthropic", "sk-ant".into());
        settings.tavily_key = "tvly-secret".into();
        settings.search_mode = "on".into();
        let built = build_chat_request(
            &settings,
            vec![json!({"role":"user","content":"news"})],
            &SendOpts::default(),
            false,
        )
        .unwrap();
        assert_eq!(built.outbound.body["searchMode"], "on");
        assert!(built.outbound.body.get("tavilyKey").is_none());
        assert!(!built.outbound.body.to_string().contains("tvly-secret"));
    }

    #[test]
    fn openai_search_sends_the_tavily_key_only_on_the_request() {
        let mut settings = ready();
        settings.tavily_key = "tvly-secret".into();
        settings.search_mode = "auto".into();
        let built = build_chat_request(
            &settings,
            vec![json!({"role":"user","content":"hi"})],
            &SendOpts::default(),
            false,
        )
        .unwrap();
        assert_eq!(built.outbound.body["tavilyKey"], "tvly-secret");
        assert_eq!(built.outbound.body["searchMode"], "auto");
    }

    #[test]
    fn proxy_key_is_a_header_and_a_body_field() {
        let mut settings = ready();
        settings.proxy_key = "proxy-secret".into();
        settings.app_origin = "https://chat.example".into();
        let built = build_chat_request(
            &settings,
            vec![json!({"role":"user","content":"hi"})],
            &SendOpts::default(),
            false,
        )
        .unwrap();
        assert!(built
            .outbound
            .headers
            .iter()
            .any(|(k, v)| k == "X-Omni-Proxy-Key" && v == "proxy-secret"));
        assert!(built
            .outbound
            .headers
            .iter()
            .any(|(k, v)| k == "Origin" && v == "https://chat.example"));
        assert_eq!(built.outbound.body["proxyKey"], "proxy-secret");
    }

    #[test]
    fn agent_mode_attaches_tools_and_drops_the_search_key() {
        let mut settings = ready();
        settings.tavily_key = "tvly-secret".into();
        settings.search_mode = "on".into();
        settings.set_sandbox_key("e2b_test".into());
        let built = build_chat_request(
            &settings,
            vec![json!({"role":"user","content":"hi"})],
            &SendOpts::default(),
            true,
        )
        .unwrap();
        assert!(built.agent);
        assert!(built.outbound.body.get("tools").is_some());
        assert!(built.outbound.body.get("tavilyKey").is_none());
        assert!(built.outbound.body["messages"][0]["content"]
            .as_str()
            .unwrap()
            .contains("sandboxed"));
    }
}

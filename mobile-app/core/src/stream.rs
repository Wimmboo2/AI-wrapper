//! NDJSON events emitted by the Worker at `/api/chat`.

use serde_json::Value;

use crate::chat::{SearchInfo, SearchSource};

#[derive(Clone, Debug, PartialEq, Default)]
pub struct Usage {
    pub input_tokens: Option<u64>,
    pub output_tokens: Option<u64>,
    pub cached_tokens: Option<u64>,
    pub cache_write_tokens: Option<u64>,
    pub reasoning_tokens: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Default)]
pub struct ServerTiming {
    pub source: Option<String>,
    pub prompt_tokens: Option<u64>,
    pub completion_tokens: Option<u64>,
    pub prompt_ms: Option<u64>,
    pub gen_ms: Option<u64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ToolStart {
    pub index: u32,
    pub id: String,
    pub name: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct StreamEvent {
    pub delta: Option<String>,
    pub thinking_delta: Option<String>,
    pub thinking_done: bool,
    pub tool_start: Option<ToolStart>,
    pub tool_args: Option<(u32, String)>,
    pub search: Option<SearchInfo>,
    pub usage: Option<Usage>,
    pub server_timing: Option<ServerTiming>,
    pub stop_reason: Option<String>,
    pub error: Option<String>,
}

#[derive(Clone, Debug, Default)]
pub struct NdjsonParser {
    buf: String,
}

impl NdjsonParser {
    pub fn push(&mut self, chunk: &str) -> Vec<StreamEvent> {
        self.buf.push_str(chunk);
        let mut events = Vec::new();
        while let Some(index) = self.buf.find('\n') {
            let line = self.buf[..index].to_string();
            self.buf.drain(..=index);
            if let Some(event) = parse_line(&line) {
                events.push(event);
            }
        }
        events
    }

    pub fn finish(&mut self) -> Vec<StreamEvent> {
        let rest = std::mem::take(&mut self.buf);
        parse_line(&rest).into_iter().collect()
    }
}

pub fn parse_line(line: &str) -> Option<StreamEvent> {
    let mut payload = line.trim();
    if payload.is_empty() {
        return None;
    }
    if let Some(rest) = payload.strip_prefix("data:") {
        payload = rest.trim();
    }
    if payload.is_empty() || payload == "[DONE]" {
        return None;
    }
    let value: Value = serde_json::from_str(payload).ok()?;
    Some(event_from_value(&value))
}

fn event_from_value(value: &Value) -> StreamEvent {
    let delta = first_string(value, &["delta", "content", "token", "text"]);
    let thinking_delta = first_string(value, &["thinking_delta", "thinking", "reasoning"]);
    let thinking_done = value
        .get("thinking_done")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
        || value
            .get("thinking_complete")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
    let tool_start = value.get("tool_start").and_then(parse_tool_start);
    let tool_args = value.get("tool_args").and_then(|args| {
        let index = args.get("i")?.as_u64()? as u32;
        let piece = args.get("d").and_then(|v| v.as_str()).unwrap_or("");
        Some((index, piece.to_string()))
    });
    let search = value.get("search").and_then(parse_search);
    let usage = value.get("usage").map(parse_usage);
    let server_timing = value.get("server_timing").map(parse_server_timing);
    let stop_reason = value
        .get("stop_reason")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let error = value.get("error").map(|err| {
        if let Some(message) = err.get("message").and_then(|v| v.as_str()) {
            message.to_string()
        } else if let Some(message) = err.as_str() {
            message.to_string()
        } else {
            "The provider returned an error.".into()
        }
    });
    StreamEvent {
        delta,
        thinking_delta,
        thinking_done,
        tool_start,
        tool_args,
        search,
        usage,
        server_timing,
        stop_reason,
        error,
    }
}

fn first_string(value: &Value, keys: &[&str]) -> Option<String> {
    for key in keys {
        if let Some(text) = value.get(*key).and_then(|v| v.as_str()) {
            if !text.is_empty() {
                return Some(text.to_string());
            }
        }
    }
    None
}

fn parse_tool_start(value: &Value) -> Option<ToolStart> {
    Some(ToolStart {
        index: value.get("i")?.as_u64()? as u32,
        id: value
            .get("id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        name: value
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
    })
}

fn parse_search(value: &Value) -> Option<SearchInfo> {
    let sources = value
        .get("sources")
        .and_then(|v| v.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| {
                    Some(SearchSource {
                        title: item
                            .get("title")
                            .and_then(|v| v.as_str())
                            .unwrap_or("Untitled")
                            .to_string(),
                        url: item
                            .get("url")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        domain: item
                            .get("domain")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        date: item
                            .get("date")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        snippet: item
                            .get("snippet")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    Some(SearchInfo {
        query: value
            .get("query")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        sources,
        error: value
            .get("error")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string()),
        cached: value
            .get("cached")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
    })
}

fn parse_usage(value: &Value) -> Usage {
    Usage {
        input_tokens: number_field(value, &["prompt_tokens", "input_tokens"]),
        output_tokens: number_field(value, &["completion_tokens", "output_tokens"]),
        cached_tokens: number_field(value, &["cached_tokens"]),
        cache_write_tokens: number_field(value, &["cache_write_tokens"]),
        reasoning_tokens: number_field(value, &["reasoning_tokens"]),
    }
}

fn parse_server_timing(value: &Value) -> ServerTiming {
    ServerTiming {
        source: value
            .get("source")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string()),
        prompt_tokens: number_field(value, &["prompt_tokens"]),
        completion_tokens: number_field(value, &["completion_tokens"]),
        prompt_ms: number_field(value, &["prompt_ms"]),
        gen_ms: number_field(value, &["gen_ms"]),
    }
}

pub fn number_field(value: &Value, keys: &[&str]) -> Option<u64> {
    for key in keys {
        let Some(raw) = value.get(*key) else { continue };
        let number = raw.as_u64().or_else(|| {
            raw.as_f64()
                .filter(|n| n.is_finite() && *n >= 0.0)
                .map(|n| n as u64)
        });
        if number.is_some() {
            return number;
        }
    }
    None
}

pub fn keep_count(prev: Option<u64>, next: Option<u64>) -> Option<u64> {
    match (prev, next) {
        (Some(left), Some(right)) => Some(left.max(right)),
        (None, next) => next,
        (prev, None) => prev,
    }
}

pub fn friendly_error(raw: &str) -> String {
    let msg = raw.split_whitespace().collect::<Vec<_>>().join(" ");
    if msg.is_empty() {
        return "The provider returned an error.".into();
    }
    if let Some(rest) = msg.strip_prefix("HTTP ") {
        if let Some(code) = rest.split(|c: char| !c.is_ascii_digit()).next() {
            if let Ok(status) = code.parse::<u16>() {
                return provider_status(status);
            }
        }
    }
    if msg.starts_with('{') || msg.starts_with('[') || msg.chars().count() > 240 {
        return "The provider returned an error.".into();
    }
    msg
}

pub fn provider_status(status: u16) -> String {
    match status {
        401 | 403 => "The provider rejected the API key.".into(),
        404 => "The provider could not find that model.".into(),
        408 => "The provider timed out. Try again.".into(),
        413 => "The request was too large for the provider.".into(),
        429 => "The provider rate limit was reached. Try again in a moment.".into(),
        402 => "The provider billing limit was reached.".into(),
        400 | 422 => "The provider rejected the request.".into(),
        500..=599 => "The provider is temporarily unavailable.".into(),
        _ => format!("The provider returned an error (HTTP {status})."),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_partial_lines_and_ignores_done() {
        let mut parser = NdjsonParser::default();
        assert!(parser.push("{\"delta\":\"Hi").is_empty());
        let events = parser.push("\"}\ndata: [DONE]\n{\"thinking_delta\":\"hmm\"}\n");
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].delta.as_deref(), Some("Hi"));
        assert_eq!(events[1].thinking_delta.as_deref(), Some("hmm"));
    }

    #[test]
    fn reads_a_search_event_and_a_tool_start() {
        let mut parser = NdjsonParser::default();
        let events = parser.push(
            "{\"search\":{\"query\":\"weather\",\"sources\":[{\"title\":\"Forecast\",\"url\":\"https://example.com\"}]}}\n{\"tool_start\":{\"i\":0,\"id\":\"call_1\",\"name\":\"run_python\"}}\n{\"tool_args\":{\"i\":0,\"d\":\"{\\\"code\\\":1}\"}}\n",
        );
        assert_eq!(events[0].search.as_ref().unwrap().sources.len(), 1);
        assert_eq!(events[1].tool_start.as_ref().unwrap().name, "run_python");
        assert_eq!(events[2].tool_args.as_ref().unwrap().0, 0);
    }
}

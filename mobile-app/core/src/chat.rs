//! Chats, messages, and the branch snapshots the web client stores on each reply.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::id::now_ms;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TurnMetrics {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stop_reason: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ToolCallRecord {
    pub id: String,
    pub name: String,
    pub args_text: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ToolResultRecord {
    pub id: String,
    pub name: String,
    pub content: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AgentStep {
    pub text: String,
    pub calls: Vec<ToolCallRecord>,
    pub results: Vec<ToolResultRecord>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct SearchSource {
    pub title: String,
    pub url: String,
    #[serde(default)]
    pub domain: String,
    #[serde(default)]
    pub date: String,
    #[serde(default)]
    pub snippet: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct SearchInfo {
    #[serde(default)]
    pub query: String,
    #[serde(default)]
    pub sources: Vec<SearchSource>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default)]
    pub cached: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub role: String,
    pub content: String,
    #[serde(default)]
    pub thinking: String,
    #[serde(default)]
    pub thinking_complete: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub search: Option<SearchInfo>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub steps: Vec<AgentStep>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metrics: Option<TurnMetrics>,
}

impl Message {
    pub fn user(content: String) -> Self {
        Self {
            role: "user".into(),
            content,
            thinking: String::new(),
            thinking_complete: false,
            search: None,
            steps: Vec::new(),
            metrics: None,
        }
    }

    pub fn assistant() -> Self {
        Self {
            role: "assistant".into(),
            content: String::new(),
            thinking: String::new(),
            thinking_complete: false,
            search: None,
            steps: Vec::new(),
            metrics: None,
        }
    }

    pub fn snapshot(&self) -> BranchSnap {
        BranchSnap {
            content: self.content.clone(),
            thinking: self.thinking.clone(),
            thinking_complete: self.thinking_complete,
            search: self.search.clone(),
            steps: self.steps.clone(),
            metrics: self.metrics.clone(),
        }
    }

    pub fn restore(&mut self, snap: &BranchSnap) {
        self.content = snap.content.clone();
        self.thinking = snap.thinking.clone();
        self.thinking_complete = snap.thinking_complete;
        self.search = snap.search.clone();
        self.steps = snap.steps.clone();
        self.metrics = snap.metrics.clone();
    }

    /// One stored message becomes the wire messages the Worker expects.
    /// An agent reply expands into the assistant tool call plus each result.
    pub fn to_api(&self) -> Vec<Value> {
        if self.role == "assistant" && !self.steps.is_empty() {
            let mut out = Vec::new();
            for step in &self.steps {
                out.extend(step_to_api(step));
            }
            if !self.content.is_empty() {
                out.push(json!({ "role": "assistant", "content": self.content }));
            }
            return out;
        }
        vec![json!({ "role": self.role, "content": self.content })]
    }
}

pub fn step_to_api(step: &AgentStep) -> Vec<Value> {
    let tool_calls: Vec<Value> = step
        .calls
        .iter()
        .map(|call| {
            json!({
                "id": call.id,
                "type": "function",
                "function": { "name": call.name, "arguments": call.args_text }
            })
        })
        .collect();
    let mut out = vec![json!({
        "role": "assistant",
        "content": if step.text.is_empty() { Value::Null } else { Value::String(step.text.clone()) },
        "tool_calls": tool_calls,
    })];
    for result in &step.results {
        out.push(json!({
            "role": "tool",
            "tool_call_id": result.id,
            "name": result.name,
            "content": result.content,
        }));
    }
    out
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BranchSnap {
    pub content: String,
    #[serde(default)]
    pub thinking: String,
    #[serde(default)]
    pub thinking_complete: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub search: Option<SearchInfo>,
    #[serde(default)]
    pub steps: Vec<AgentStep>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metrics: Option<TurnMetrics>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Chat {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub messages: Vec<Message>,
    pub created_at: u64,
    pub updated_at: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pinned_at: Option<u64>,
    #[serde(default)]
    pub incognito: bool,
    #[serde(default)]
    pub agent_mode: bool,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub branches: BTreeMap<usize, Vec<BranchSnap>>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub branch_active: BTreeMap<usize, usize>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub live_replies: BTreeMap<usize, BranchSnap>,
}

impl Chat {
    pub fn new(incognito: bool) -> Self {
        let now = now_ms();
        Self {
            id: crate::id::uid(),
            title: if incognito {
                "Incognito chat".into()
            } else {
                "New chat".into()
            },
            messages: Vec::new(),
            created_at: now,
            updated_at: now,
            pinned_at: None,
            incognito,
            agent_mode: false,
            branches: BTreeMap::new(),
            branch_active: BTreeMap::new(),
            live_replies: BTreeMap::new(),
        }
    }

    pub fn touch(&mut self) {
        self.updated_at = now_ms();
    }

    pub fn note_title_from_first_message(&mut self, text: &str) {
        if self.title != "New chat" && self.title != "Incognito chat" {
            return;
        }
        let clean = text.split_whitespace().collect::<Vec<_>>().join(" ");
        if clean.is_empty() {
            return;
        }
        self.title = if clean.chars().count() > 42 {
            let trimmed: String = clean.chars().take(42).collect();
            format!("{trimmed}…")
        } else {
            clean
        };
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct ChatListItem {
    pub id: String,
    pub title: String,
    pub snippet: Option<String>,
    pub pinned: bool,
    pub active: bool,
    pub incognito: bool,
    pub streaming: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BranchUi {
    pub parent: usize,
    pub active: usize,
    pub total: usize,
}

pub fn branch_ui(chat: &Chat, assistant_index: usize) -> Option<BranchUi> {
    if assistant_index == 0 {
        return None;
    }
    let parent = assistant_index - 1;
    let parent_msg = chat.messages.get(parent)?;
    let current = chat.messages.get(assistant_index)?;
    if parent_msg.role != "user" || current.role != "assistant" {
        return None;
    }
    let branches = chat.branches.get(&parent)?;
    if branches.is_empty() {
        return None;
    }
    let active = chat
        .branch_active
        .get(&parent)
        .copied()
        .unwrap_or(branches.len());
    Some(BranchUi {
        parent,
        active,
        total: branches.len() + 1,
    })
}

pub fn derive_snippet(content: &str, query: &str) -> String {
    let lower = content.to_lowercase();
    let Some(idx) = lower.find(query) else {
        return String::new();
    };
    // Lowercasing can change byte length (for example ß), so the window is cut
    // from the string we actually searched.
    let source = if content.len() == lower.len() {
        content
    } else {
        lower.as_str()
    };
    let start = floor_char(source, idx.saturating_sub(20));
    let end = ceil_char(source, (idx + query.len() + 30).min(source.len()));
    let mut snippet = String::new();
    if start > 0 {
        snippet.push_str("...");
    }
    snippet.push_str(
        &source[start..end]
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" "),
    );
    if end < source.len() {
        snippet.push_str("...");
    }
    snippet
}

fn floor_char(text: &str, mut index: usize) -> usize {
    if index >= text.len() {
        return text.len();
    }
    while index > 0 && !text.is_char_boundary(index) {
        index -= 1;
    }
    index
}

fn ceil_char(text: &str, mut index: usize) -> usize {
    if index >= text.len() {
        return text.len();
    }
    while index < text.len() && !text.is_char_boundary(index) {
        index += 1;
    }
    index
}

//! The chat session: list, send, stream, stop, regenerate, and branches.
//!
//! HTTP stays outside this type. Callers feed NDJSON chunks in and get the
//! next request back when the model asks for a tool.

use std::collections::BTreeMap;

use serde_json::Value;

use crate::chat::{
    branch_ui, derive_snippet, step_to_api, AgentStep, BranchUi, Chat, ChatListItem, Message,
    ToolCallRecord, ToolResultRecord, TurnMetrics,
};
use crate::id::now_ms;
use crate::persist;
use crate::request::{self, BuiltRequest, Outbound, SendOpts};
use crate::settings::Settings;
use crate::stream::{self, NdjsonParser, StreamEvent};
use crate::think::{self, ThinkSplitter};

#[derive(Clone, Debug, PartialEq)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub args_text: String,
    pub args: Value,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ToolResult {
    pub id: String,
    pub name: String,
    pub content: String,
}

#[derive(Clone, Debug)]
pub struct Prepared {
    pub outbound: Outbound,
    pub notice: Option<String>,
}

#[derive(Clone, Debug)]
pub enum StreamEnd {
    Reply,
    Tools(Vec<ToolCall>),
    Failed(String),
}

struct PendingTool {
    id: String,
    name: String,
    args_text: String,
}

#[derive(Default)]
struct UsageAccum {
    input: Option<u64>,
    output: Option<u64>,
}

struct TurnState {
    chat_id: String,
    url: String,
    headers: Vec<(String, String)>,
    body: Value,
    parser: NdjsonParser,
    think: ThinkSplitter,
    tools: BTreeMap<u32, PendingTool>,
    tool_order: Vec<u32>,
    turn_text_start: usize,
    step: u32,
    max_steps: u32,
    think_key: String,
    usage_turn: UsageAccum,
    usage_total: UsageAccum,
    stop_reason: Option<String>,
    status: String,
    errored: Option<String>,
    settled: bool,
}

pub struct Store {
    pub settings: Settings,
    chats: Vec<Chat>,
    active_id: Option<String>,
    pub incognito: bool,
    incognito_chat: Option<Chat>,
    turn: Option<TurnState>,
}

impl Store {
    pub fn fresh() -> Self {
        Self {
            settings: Settings::default(),
            chats: Vec::new(),
            active_id: None,
            incognito: false,
            incognito_chat: None,
            turn: None,
        }
    }

    pub fn load(dir: &std::path::Path) -> Self {
        persist::load_into(dir)
    }

    pub fn save(&self, dir: &std::path::Path) -> Result<(), String> {
        persist::save_store(self, dir)
    }

    pub(crate) fn replace_chats(&mut self, chats: Vec<Chat>, active_id: Option<String>) {
        self.chats = chats;
        if let Some(id) = active_id {
            if self.chats.iter().any(|chat| chat.id == id) {
                self.active_id = Some(id);
                return;
            }
        }
        self.active_id = self.chats.first().map(|chat| chat.id.clone());
    }

    pub(crate) fn saved_chats(&self) -> Vec<Chat> {
        self.chats
            .iter()
            .filter(|chat| !chat.incognito)
            .cloned()
            .collect()
    }

    pub(crate) fn saved_active_id(&self) -> Option<String> {
        let id = self.active_id.clone()?;
        if self
            .incognito_chat
            .as_ref()
            .map(|chat| chat.id == id)
            .unwrap_or(false)
        {
            return self.chats.first().map(|chat| chat.id.clone());
        }
        if self.chats.iter().any(|chat| chat.id == id) {
            Some(id)
        } else {
            None
        }
    }

    pub fn active(&self) -> Option<&Chat> {
        let id = self.active_id.as_deref()?;
        self.chat(id)
    }

    pub fn chat(&self, id: &str) -> Option<&Chat> {
        if self
            .incognito_chat
            .as_ref()
            .map(|chat| chat.id == id)
            .unwrap_or(false)
        {
            return self.incognito_chat.as_ref();
        }
        self.chats.iter().find(|chat| chat.id == id)
    }

    pub fn is_streaming(&self) -> bool {
        self.turn.is_some()
    }

    pub fn streaming_id(&self) -> Option<&str> {
        self.turn.as_ref().map(|turn| turn.chat_id.as_str())
    }

    pub fn status(&self) -> String {
        self.turn
            .as_ref()
            .map(|turn| turn.status.clone())
            .unwrap_or_default()
    }

    pub fn set_status(&mut self, status: impl Into<String>) {
        if let Some(turn) = self.turn.as_mut() {
            turn.status = status.into();
        }
    }

    pub fn turn_step(&self) -> u32 {
        self.turn.as_ref().map(|turn| turn.step).unwrap_or(0)
    }

    pub fn max_steps(&self) -> u32 {
        self.turn
            .as_ref()
            .map(|turn| turn.max_steps)
            .unwrap_or(self.settings.agent_max_steps)
    }

    pub fn new_chat(&mut self) -> String {
        let chat = Chat::new(self.incognito);
        let id = chat.id.clone();
        if self.incognito {
            self.incognito_chat = Some(chat);
        } else {
            self.chats.insert(0, chat);
        }
        self.active_id = Some(id.clone());
        id
    }

    pub fn open_chat(&mut self, id: &str) {
        if self
            .incognito_chat
            .as_ref()
            .map(|chat| chat.id == id)
            .unwrap_or(false)
        {
            self.incognito = true;
            self.active_id = Some(id.to_string());
            return;
        }
        if self.chats.iter().any(|chat| chat.id == id) {
            self.incognito = false;
            self.incognito_chat = None;
            self.active_id = Some(id.to_string());
        }
    }

    pub fn delete_chat(&mut self, id: &str) {
        if self
            .turn
            .as_ref()
            .map(|turn| turn.chat_id == id)
            .unwrap_or(false)
        {
            self.turn = None;
        }
        if self
            .incognito_chat
            .as_ref()
            .map(|chat| chat.id == id)
            .unwrap_or(false)
        {
            self.incognito_chat = None;
            if self.incognito {
                self.new_chat();
            }
            return;
        }
        self.chats.retain(|chat| chat.id != id);
        if self.active_id.as_deref() == Some(id) {
            self.active_id = self.chat_list("").first().map(|item| item.id.clone());
        }
    }

    pub fn toggle_pin(&mut self, id: &str) {
        if let Some(chat) = self.chats.iter_mut().find(|chat| chat.id == id) {
            chat.pinned_at = if chat.pinned_at.is_some() {
                None
            } else {
                Some(now_ms())
            };
            chat.touch();
        }
    }

    pub fn rename_chat(&mut self, id: &str, title: &str) -> Result<(), String> {
        let title = title.split_whitespace().collect::<Vec<_>>().join(" ");
        if title.is_empty() {
            return Err("The chat needs a title.".into());
        }
        let chat = self
            .chat_mut(id)
            .ok_or_else(|| "That chat is gone.".to_string())?;
        chat.title = title;
        chat.touch();
        Ok(())
    }

    pub fn toggle_incognito(&mut self) {
        if self.incognito {
            self.incognito = false;
            self.incognito_chat = None;
            if self
                .active_id
                .as_ref()
                .map(|id| self.chats.iter().all(|chat| &chat.id != id))
                .unwrap_or(true)
            {
                self.active_id = self.chat_list("").first().map(|item| item.id.clone());
            }
        } else {
            self.incognito = true;
            if self.incognito_chat.is_none() {
                self.new_chat();
            } else if let Some(chat) = &self.incognito_chat {
                self.active_id = Some(chat.id.clone());
            }
        }
    }

    pub fn toggle_agent(&mut self) -> Result<bool, String> {
        if self.active().is_none() {
            return Err("Start a chat first.".into());
        }
        let has_key = !self.settings.sandbox_key().is_empty();
        let chat = self
            .active_chat_mut()
            .ok_or_else(|| "Start a chat first.".to_string())?;
        if !chat.agent_mode && !has_key {
            return Err("Add a sandbox API key in Settings to run code.".into());
        }
        chat.agent_mode = !chat.agent_mode;
        Ok(chat.agent_mode)
    }

    pub fn set_provider(&mut self, id: &str) {
        let current_provider = self.settings.provider.clone();
        let current_key = self.settings.api_key_for(&current_provider);
        if !current_key.is_empty() {
            self.settings.set_api_key(&current_provider, current_key);
        }
        self.settings.provider = id.to_string();
        let key = self.settings.api_key_for(id);
        self.settings.api_key = key;
        self.settings.normalize();
    }

    pub fn set_model(&mut self, id: &str) {
        self.settings.model = id.to_string();
        self.settings.normalize();
    }

    pub fn set_effort(&mut self, effort: &str) {
        self.settings.effort = effort.to_string();
        self.settings.normalize();
    }

    pub fn normalize(&mut self) {
        self.settings.normalize();
    }

    pub fn chat_list(&self, query: &str) -> Vec<ChatListItem> {
        let q = query.trim().to_lowercase();
        let streaming = self.streaming_id().map(|id| id.to_string());
        if self.incognito {
            return self
                .incognito_chat
                .as_ref()
                .map(|chat| {
                    vec![list_item(
                        chat,
                        &q,
                        self.active_id.as_deref(),
                        streaming.as_deref(),
                    )]
                })
                .unwrap_or_default();
        }
        let mut items: Vec<&Chat> = self
            .chats
            .iter()
            .filter(|chat| !chat.incognito && chat_matches(chat, &q))
            .collect();
        items.sort_by(|a, b| {
            let pin = b.pinned_at.is_some().cmp(&a.pinned_at.is_some());
            if pin != std::cmp::Ordering::Equal {
                return pin;
            }
            b.updated_at
                .cmp(&a.updated_at)
                .then(b.created_at.cmp(&a.created_at))
        });
        items
            .into_iter()
            .map(|chat| list_item(chat, &q, self.active_id.as_deref(), streaming.as_deref()))
            .collect()
    }

    pub fn branch_state(&self, assistant_index: usize) -> Option<BranchUi> {
        branch_ui(self.active()?, assistant_index)
    }

    pub fn ensure_ready(&self) -> Result<(), String> {
        request::endpoint_url(&self.settings)?;
        if self
            .settings
            .api_key_for(&self.settings.provider)
            .is_empty()
        {
            return Err("Set your API key in Settings first.".into());
        }
        if self.settings.model.trim().is_empty() {
            return Err("Choose a model in Settings first.".into());
        }
        if self.settings.provider == "custom" && self.settings.custom_base_url.trim().is_empty() {
            return Err("Set the custom base URL in Settings first.".into());
        }
        Ok(())
    }

    pub fn begin_send(&mut self, text: &str, mut opts: SendOpts) -> Result<Prepared, String> {
        if self.turn.is_some() {
            return Err("Wait for the current reply to finish.".into());
        }
        self.settings.normalize();
        let mut text = text.trim().to_string();
        if let Some(slash) = request::slash_search(&text) {
            text = slash?;
            opts.search_override = Some(true);
        }
        if text.is_empty() {
            return Err("Write a message first.".into());
        }
        let agent_mode = self.active().map(|chat| chat.agent_mode).unwrap_or(false);
        let mut history = self
            .active()
            .map(|chat| history_of(&chat.messages))
            .unwrap_or_default();
        history.push(serde_json::json!({ "role": "user", "content": text }));
        let built = request::build_chat_request(&self.settings, history, &opts, agent_mode)?;
        let notice = built.notice.clone();
        if self.active().is_none() {
            self.new_chat();
        }
        let chat_id = self
            .active_id
            .clone()
            .ok_or_else(|| "Start a chat first.".to_string())?;
        if let Some(chat) = self.chat_mut(&chat_id) {
            chat.note_title_from_first_message(&text);
            chat.messages.push(Message::user(text));
            chat.messages.push(Message::assistant());
            chat.touch();
        }
        self.install_turn(&chat_id, built);
        let outbound = self
            .outbound()
            .ok_or_else(|| "Could not start the reply.".to_string())?;
        Ok(Prepared { outbound, notice })
    }

    pub fn apply_stream_chunk(&mut self, chunk: &str) -> bool {
        let events = match self.turn.as_mut() {
            Some(turn) => turn.parser.push(chunk),
            None => return false,
        };
        self.apply_events(events)
    }

    pub fn finish_stream(&mut self) -> StreamEnd {
        let events = self
            .turn
            .as_mut()
            .map(|turn| turn.parser.finish())
            .unwrap_or_default();
        let fatal = self.apply_events(events);
        let already_failed = self.turn.as_ref().and_then(|turn| turn.errored.clone());
        if fatal || already_failed.is_some() {
            let message = already_failed
                .or_else(|| self.turn.as_ref().and_then(|turn| turn.errored.clone()))
                .unwrap_or_else(|| "The provider returned an error.".into());
            let friendly = stream::friendly_error(&message);
            self.complete_error(&friendly);
            return StreamEnd::Failed(friendly);
        }
        self.settle(false);
        self.fold_usage();
        let calls = self.collect_tools();
        if calls.is_empty() {
            self.finish_reply();
            StreamEnd::Reply
        } else {
            StreamEnd::Tools(calls)
        }
    }

    pub fn record_tool_round(
        &mut self,
        calls: &[ToolCall],
        results: &[ToolResult],
        capped: bool,
    ) -> Result<Outbound, String> {
        let chat_id = self
            .turn
            .as_ref()
            .map(|turn| turn.chat_id.clone())
            .ok_or_else(|| "Nothing is running.".to_string())?;
        let start = self
            .turn
            .as_ref()
            .map(|turn| turn.turn_text_start)
            .unwrap_or(0);
        let text = self
            .chat(&chat_id)
            .and_then(|chat| chat.messages.last())
            .map(|message| {
                let start = start.min(message.content.len());
                message.content[floor_boundary(&message.content, start)..].to_string()
            })
            .unwrap_or_default();
        if let Some(chat) = self.chat_mut(&chat_id) {
            if let Some(message) = chat.messages.last_mut() {
                let start = start.min(message.content.len());
                let start = floor_boundary(&message.content, start);
                message.content.truncate(start);
                message.steps.push(AgentStep {
                    text,
                    calls: calls
                        .iter()
                        .map(|call| ToolCallRecord {
                            id: call.id.clone(),
                            name: call.name.clone(),
                            args_text: if call.args_text.is_empty() {
                                "{}".into()
                            } else {
                                call.args_text.clone()
                            },
                        })
                        .collect(),
                    results: results
                        .iter()
                        .map(|result| ToolResultRecord {
                            id: result.id.clone(),
                            name: result.name.clone(),
                            content: result.content.clone(),
                        })
                        .collect(),
                });
            }
        }
        let wire = self
            .chat(&chat_id)
            .and_then(|chat| chat.messages.last())
            .and_then(|message| message.steps.last())
            .cloned();
        if let Some(turn) = self.turn.as_mut() {
            if let Some(step) = wire {
                if let Some(messages) = turn.body.get_mut("messages").and_then(|v| v.as_array_mut())
                {
                    messages.extend(step_to_api(&step));
                }
            }
            if capped {
                if let Some(object) = turn.body.as_object_mut() {
                    object.remove("tools");
                }
            }
            turn.step += 1;
            turn.parser = NdjsonParser::default();
            turn.tools.clear();
            turn.tool_order.clear();
            turn.usage_total.input = add_opt(turn.usage_total.input, turn.usage_turn.input);
            turn.usage_total.output = add_opt(turn.usage_total.output, turn.usage_turn.output);
            turn.usage_turn = UsageAccum::default();
            turn.errored = None;
            turn.settled = false;
            turn.status = "Thinking.".into();
            let presume = false;
            turn.think = think::make_splitter(presume);
        }
        let presume = self
            .settings
            .implicit_think_models
            .get(&self.settings.think_model_key())
            .copied()
            .unwrap_or(false);
        if let Some(turn) = self.turn.as_mut() {
            turn.think = think::make_splitter(presume);
            turn.think_key = self.settings.think_model_key();
        }
        let next_start = self
            .chat(&chat_id)
            .and_then(|chat| chat.messages.last())
            .map(|message| message.content.len())
            .unwrap_or(0);
        if let Some(turn) = self.turn.as_mut() {
            turn.turn_text_start = next_start;
        }
        self.outbound()
            .ok_or_else(|| "Nothing is running.".to_string())
    }

    pub fn complete_stopped(&mut self) {
        let events = self
            .turn
            .as_mut()
            .map(|turn| turn.parser.finish())
            .unwrap_or_default();
        // A partial last line can still hold a whole event. Ignore a fatal flag:
        // the user already asked to stop.
        let _ = self.apply_events(events);
        self.settle(true);
        let chat_id = self.turn.as_ref().map(|turn| turn.chat_id.clone());
        let metrics = self.metrics_now();
        if let Some(chat_id) = chat_id {
            if let Some(chat) = self.chat_mut(&chat_id) {
                if let Some(message) = chat
                    .messages
                    .last_mut()
                    .filter(|message| message.role == "assistant")
                {
                    if message.content.is_empty() && message.steps.is_empty() {
                        message.content = "*(Generation stopped)*".into();
                    }
                    if message.thinking.is_empty() || message.thinking_complete {
                        message.thinking_complete = true;
                    }
                    message.metrics = metrics;
                }
                chat.touch();
            }
        }
        self.turn = None;
    }

    pub fn complete_error(&mut self, message: &str) {
        self.settle(true);
        let friendly = stream::friendly_error(message);
        let chat_id = self.turn.as_ref().map(|turn| turn.chat_id.clone());
        let metrics = self.metrics_now();
        if let Some(chat_id) = chat_id {
            if let Some(chat) = self.chat_mut(&chat_id) {
                if let Some(message_slot) = chat
                    .messages
                    .last_mut()
                    .filter(|message| message.role == "assistant")
                {
                    if message_slot.content.is_empty() {
                        message_slot.content = format!("Error: {friendly}");
                    }
                    message_slot.thinking_complete = true;
                    message_slot.metrics = metrics;
                }
                chat.touch();
            }
        }
        self.turn = None;
    }

    pub fn prepare_regenerate(&mut self, assistant_index: usize) -> Result<String, String> {
        if self.turn.is_some() {
            return Err("Wait for the current reply to finish.".into());
        }
        self.settings.normalize();
        self.ensure_ready()?;
        if assistant_index == 0 {
            return Err("Nothing to regenerate.".into());
        }
        let parent = assistant_index - 1;
        let chat = self
            .active_chat_mut()
            .ok_or_else(|| "Start a chat first.".to_string())?;
        let parent_msg = chat
            .messages
            .get(parent)
            .ok_or_else(|| "Nothing to regenerate.".to_string())?;
        if parent_msg.role != "user" {
            return Err("Nothing to regenerate.".into());
        }
        let user_text = parent_msg.content.clone();
        let old = chat
            .messages
            .get(assistant_index)
            .cloned()
            .ok_or_else(|| "Nothing to regenerate.".to_string())?;
        if old.role != "assistant" {
            return Err("Nothing to regenerate.".into());
        }
        let live = chat.live_replies.remove(&parent);
        let branches = chat.branches.entry(parent).or_default();
        if let Some(live) = live {
            branches.push(live);
        }
        let duplicate = branches.iter().any(|branch| {
            branch.content == old.content && branch.steps.is_empty() && old.steps.is_empty()
        });
        if !duplicate {
            branches.push(old.snapshot());
        }
        let len = branches.len();
        chat.branch_active.insert(parent, len);
        chat.messages.truncate(parent);
        chat.touch();
        Ok(user_text)
    }

    pub fn cycle_branch(&mut self, parent: usize, direction: i32) -> Result<(), String> {
        if self.turn.is_some() {
            return Err("Wait for the current reply to finish.".into());
        }
        let chat = self
            .active_chat_mut()
            .ok_or_else(|| "Start a chat first.".to_string())?;
        let branches_len = chat
            .branches
            .get(&parent)
            .map(|items| items.len())
            .unwrap_or(0);
        if branches_len == 0 {
            return Err("No other replies.".into());
        }
        let current = chat
            .messages
            .get(parent + 1)
            .ok_or_else(|| "No other replies.".to_string())?;
        if current.role != "assistant" {
            return Err("No other replies.".into());
        }
        let snapshot = current.snapshot();
        let mut active = chat
            .branch_active
            .get(&parent)
            .copied()
            .unwrap_or(branches_len);
        if active > branches_len {
            active = branches_len;
        }
        if active < branches_len {
            chat.branches.get_mut(&parent).expect("branches")[active] = snapshot;
        } else {
            chat.live_replies.insert(parent, snapshot);
        }
        let total = branches_len + 1;
        let next = (active as i32 + direction).rem_euclid(total as i32) as usize;
        if next < branches_len {
            let snap = chat.branches.get(&parent).expect("branches")[next].clone();
            chat.messages[parent + 1].restore(&snap);
        } else if let Some(stashed) = chat.live_replies.remove(&parent) {
            chat.messages[parent + 1].restore(&stashed);
        }
        chat.branch_active.insert(parent, next);
        chat.touch();
        Ok(())
    }

    fn install_turn(&mut self, chat_id: &str, built: BuiltRequest) {
        let presume = self
            .settings
            .implicit_think_models
            .get(&self.settings.think_model_key())
            .copied()
            .unwrap_or(false);
        self.turn = Some(TurnState {
            chat_id: chat_id.to_string(),
            url: built.outbound.url,
            headers: built.outbound.headers,
            body: built.outbound.body,
            parser: NdjsonParser::default(),
            think: think::make_splitter(presume),
            tools: BTreeMap::new(),
            tool_order: Vec::new(),
            turn_text_start: 0,
            step: 0,
            max_steps: self.settings.agent_max_steps,
            think_key: self.settings.think_model_key(),
            usage_turn: UsageAccum::default(),
            usage_total: UsageAccum::default(),
            stop_reason: None,
            status: built.status,
            errored: None,
            settled: false,
        });
        // `begin_send` reports the notice through the built request. Stash it on
        // the status line only when it is a search warning the bubble should show.
        if let Some(notice) = built.notice {
            if let Some(turn) = self.turn.as_mut() {
                if turn.status == "Thinking." {
                    turn.status = notice;
                }
            }
        }
    }

    pub fn current_request(&self) -> Option<Outbound> {
        self.outbound()
    }

    fn outbound(&self) -> Option<Outbound> {
        self.turn.as_ref().map(|turn| Outbound {
            url: turn.url.clone(),
            headers: turn.headers.clone(),
            body: turn.body.clone(),
        })
    }

    fn apply_events(&mut self, events: Vec<StreamEvent>) -> bool {
        for event in events {
            if self.apply_one(event) {
                return true;
            }
        }
        false
    }

    fn apply_one(&mut self, event: StreamEvent) -> bool {
        if event.error.is_some() {
            if let Some(turn) = self.turn.as_mut() {
                turn.errored = event.error;
            }
            return true;
        }
        let show_thinking = self.settings.thinking;
        let chat_id = match self.turn.as_ref() {
            Some(turn) => turn.chat_id.clone(),
            None => return false,
        };
        if let Some(delta) = event.thinking_delta {
            if show_thinking {
                if let Some(chat) = self.chat_mut(&chat_id) {
                    if let Some(message) = assistant_mut(chat) {
                        message.thinking.push_str(&delta);
                    }
                }
            }
        }
        if event.thinking_done {
            if let Some(chat) = self.chat_mut(&chat_id) {
                if let Some(message) = assistant_mut(chat) {
                    message.thinking_complete = true;
                }
            }
        }
        if let Some(delta) = event.delta {
            self.apply_visible(&chat_id, &delta);
        }
        if let Some(start) = event.tool_start {
            if let Some(turn) = self.turn.as_mut() {
                let slot = turn.tools.entry(start.index).or_insert_with(|| {
                    turn.tool_order.push(start.index);
                    PendingTool {
                        id: String::new(),
                        name: String::new(),
                        args_text: String::new(),
                    }
                });
                if !start.id.is_empty() {
                    slot.id = start.id;
                }
                if !start.name.is_empty() {
                    slot.name = start.name.clone();
                }
                let label = if start.name.is_empty() {
                    "tool".into()
                } else {
                    start.name
                };
                turn.status = format!("Preparing {label}…");
            }
        }
        if let Some((index, piece)) = event.tool_args {
            if let Some(turn) = self.turn.as_mut() {
                if let Some(slot) = turn.tools.get_mut(&index) {
                    slot.args_text.push_str(&piece);
                }
            }
        }
        if let Some(usage) = event.usage {
            if let Some(turn) = self.turn.as_mut() {
                turn.usage_turn.input =
                    stream::keep_count(turn.usage_turn.input, usage.input_tokens);
                turn.usage_turn.output =
                    stream::keep_count(turn.usage_turn.output, usage.output_tokens);
            }
        }
        if let Some(reason) = event.stop_reason {
            if let Some(turn) = self.turn.as_mut() {
                turn.stop_reason = Some(reason);
            }
        }
        if let Some(info) = event.search {
            let status = search_status(&info);
            let show_status = self
                .chat(&chat_id)
                .and_then(|chat| chat.messages.last())
                .map(|message| message.content.is_empty())
                .unwrap_or(true);
            if let Some(chat) = self.chat_mut(&chat_id) {
                if let Some(message) = assistant_mut(chat) {
                    message.search = Some(info);
                }
            }
            if show_status {
                if let Some(turn) = self.turn.as_mut() {
                    if let Some(status) = status {
                        turn.status = status;
                    }
                }
            }
        }
        false
    }

    fn apply_visible(&mut self, chat_id: &str, raw: &str) {
        let show_thinking = self.settings.thinking;
        let fed = match self.turn.as_mut() {
            Some(turn) => turn.think.feed(raw),
            None => return,
        };
        let key = self
            .turn
            .as_ref()
            .map(|turn| turn.think_key.clone())
            .unwrap_or_default();
        if fed.reclaim {
            self.settings.implicit_think_models.insert(key, true);
        }
        if let Some(chat) = self.chat_mut(chat_id) {
            if let Some(message) = assistant_mut(chat) {
                if fed.reclaim && !message.content.is_empty() {
                    if show_thinking {
                        message.thinking.push_str(&message.content);
                    }
                    message.content.clear();
                }
                if show_thinking && !fed.think.is_empty() {
                    message.thinking.push_str(&fed.think);
                }
                if fed.closed {
                    message.thinking_complete = true;
                }
                if !fed.text.is_empty() {
                    if !message.thinking.is_empty() && !message.thinking_complete {
                        message.thinking_complete = true;
                    }
                    if message.content.is_empty() {
                        message.content.push_str(fed.text.trim_start());
                    } else {
                        message.content.push_str(&fed.text);
                    }
                }
            }
        }
    }

    fn settle(&mut self, interrupted: bool) {
        let already = self.turn.as_ref().map(|turn| turn.settled).unwrap_or(true);
        if already {
            return;
        }
        let flushed = match self.turn.as_mut() {
            Some(turn) => {
                turn.settled = true;
                turn.think.flush(interrupted)
            }
            None => return,
        };
        let show_thinking = self.settings.thinking;
        let key = self
            .turn
            .as_ref()
            .map(|turn| turn.think_key.clone())
            .unwrap_or_default();
        let chat_id = self
            .turn
            .as_ref()
            .map(|turn| turn.chat_id.clone())
            .unwrap_or_default();
        if flushed.unpresume {
            self.settings.implicit_think_models.remove(&key);
        }
        if let Some(chat) = self.chat_mut(&chat_id) {
            if let Some(message) = assistant_mut(chat) {
                if flushed.unpresume {
                    let mut restored = String::new();
                    restored.push_str(&message.thinking);
                    restored.push_str(&flushed.text);
                    message.thinking.clear();
                    if message.content.is_empty() {
                        restored = restored.trim_start().to_string();
                    }
                    message.content.push_str(&restored);
                } else {
                    if show_thinking && !flushed.think.is_empty() {
                        message.thinking.push_str(&flushed.think);
                    }
                    if !flushed.text.is_empty() {
                        message.content.push_str(&flushed.text);
                    }
                }
            }
        }
    }

    fn fold_usage(&mut self) {
        if let Some(turn) = self.turn.as_mut() {
            turn.usage_total.input = add_opt(turn.usage_total.input, turn.usage_turn.input);
            turn.usage_total.output = add_opt(turn.usage_total.output, turn.usage_turn.output);
            turn.usage_turn = UsageAccum::default();
        }
    }

    fn metrics_now(&mut self) -> Option<TurnMetrics> {
        self.fold_usage();
        self.turn.as_ref().map(|turn| TurnMetrics {
            input_tokens: turn.usage_total.input,
            output_tokens: turn.usage_total.output,
            stop_reason: turn.stop_reason.clone(),
        })
    }

    fn collect_tools(&self) -> Vec<ToolCall> {
        let Some(turn) = self.turn.as_ref() else {
            return Vec::new();
        };
        turn.tool_order
            .iter()
            .filter_map(|index| {
                let slot = turn.tools.get(index)?;
                if slot.name.is_empty() {
                    return None;
                }
                let args_text = if slot.args_text.is_empty() {
                    "{}".into()
                } else {
                    slot.args_text.clone()
                };
                let args = serde_json::from_str(&args_text)
                    .ok()
                    .filter(|value: &Value| value.is_object())
                    .unwrap_or_else(|| serde_json::json!({}));
                Some(ToolCall {
                    id: if slot.id.is_empty() {
                        format!("call_{index}")
                    } else {
                        slot.id.clone()
                    },
                    name: slot.name.clone(),
                    args_text,
                    args,
                })
            })
            .collect()
    }

    fn finish_reply(&mut self) {
        let chat_id = self.turn.as_ref().map(|turn| turn.chat_id.clone());
        let metrics = self.metrics_now();
        if let Some(chat_id) = chat_id {
            if let Some(chat) = self.chat_mut(&chat_id) {
                if let Some(message) = assistant_mut(chat) {
                    if message.content.is_empty() && message.steps.is_empty() {
                        message.content = "(No response received)".into();
                    }
                    message.thinking_complete = true;
                    message.metrics = metrics;
                }
                chat.touch();
            }
        }
        self.turn = None;
    }

    fn active_chat_mut(&mut self) -> Option<&mut Chat> {
        let id = self.active_id.clone()?;
        self.chat_mut(&id)
    }

    fn chat_mut(&mut self, id: &str) -> Option<&mut Chat> {
        if self
            .incognito_chat
            .as_ref()
            .map(|chat| chat.id == id)
            .unwrap_or(false)
        {
            return self.incognito_chat.as_mut();
        }
        self.chats.iter_mut().find(|chat| chat.id == id)
    }
}

fn assistant_mut(chat: &mut Chat) -> Option<&mut Message> {
    chat.messages
        .last_mut()
        .filter(|message| message.role == "assistant")
}

fn history_of(messages: &[Message]) -> Vec<Value> {
    let mut out = Vec::new();
    for (index, message) in messages.iter().enumerate() {
        let placeholder = index + 1 == messages.len()
            && message.role == "assistant"
            && message.content.is_empty()
            && message.steps.is_empty()
            && message.thinking.is_empty();
        if placeholder {
            continue;
        }
        let keep = message.role == "user"
            || (message.role == "assistant"
                && (!message.content.is_empty() || !message.steps.is_empty()));
        if keep {
            out.extend(message.to_api());
        }
    }
    out
}

fn list_item(
    chat: &Chat,
    query: &str,
    active: Option<&str>,
    streaming: Option<&str>,
) -> ChatListItem {
    let snippet = if query.is_empty() || chat.title.to_lowercase().contains(query) {
        None
    } else {
        chat.messages.iter().find_map(|message| {
            if message.content.to_lowercase().contains(query) {
                let snippet = derive_snippet(&message.content, query);
                if snippet.is_empty() {
                    None
                } else {
                    Some(snippet)
                }
            } else {
                None
            }
        })
    };
    ChatListItem {
        id: chat.id.clone(),
        title: chat.title.clone(),
        snippet,
        pinned: chat.pinned_at.is_some(),
        active: active == Some(chat.id.as_str()),
        incognito: chat.incognito,
        streaming: streaming == Some(chat.id.as_str()),
    }
}

fn chat_matches(chat: &Chat, query: &str) -> bool {
    if query.is_empty() {
        return true;
    }
    if chat.title.to_lowercase().contains(query) {
        return true;
    }
    chat.messages
        .iter()
        .any(|message| message.content.to_lowercase().contains(query))
}

fn search_status(info: &crate::chat::SearchInfo) -> Option<String> {
    if !info.sources.is_empty() {
        let count = info.sources.len();
        let noun = if count == 1 { "source" } else { "sources" };
        let query = if info.query.is_empty() {
            String::new()
        } else {
            let trimmed: String = info.query.chars().take(60).collect();
            format!("“{trimmed}” · ")
        };
        let cached = if info.cached { " · cached" } else { "" };
        return Some(format!("{query}{count} {noun}{cached}"));
    }
    info.error.clone()
}

fn add_opt(left: Option<u64>, right: Option<u64>) -> Option<u64> {
    match (left, right) {
        (Some(a), Some(b)) => Some(a.saturating_add(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    }
}

fn floor_boundary(text: &str, mut index: usize) -> usize {
    if index > text.len() {
        index = text.len();
    }
    while index > 0 && !text.is_char_boundary(index) {
        index -= 1;
    }
    index
}

impl std::fmt::Debug for Store {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Store")
            .field("settings", &self.settings)
            .field("chats", &self.chats.len())
            .field("streaming", &self.turn.is_some())
            .finish()
    }
}

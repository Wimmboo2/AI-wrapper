//! One in-flight reply. The HTTP client lives here; the chat rules live in `omni-core`.

use std::sync::Mutex;
use std::time::Duration;

use dioxus::prelude::{spawn, ReadableExt, Signal, WritableExt};
use futures_util::StreamExt;
use omni_core::{
    format_tool_result, friendly_error, parse_session, sandbox_request, tool_invocation, Outbound,
    SandboxOp, SandboxSession, StreamEnd, ToolCall, ToolResult,
};
use tokio::sync::watch;

use crate::state::AppState;

enum Flow {
    Cancelled,
    Ended,
}

/// Stops the keepalive task when this generation's task ends.
struct EndKeepalive {
    tx: Option<watch::Sender<bool>>,
}

impl Drop for EndKeepalive {
    fn drop(&mut self) {
        if let Some(tx) = self.tx.take() {
            let _ = tx.send(true);
        }
    }
}

pub fn spawn_generation(mut app: Signal<AppState>) {
    if !app.read().store.is_streaming() {
        return;
    }
    let (tx, rx) = watch::channel(false);
    let generation = {
        let mut state = app.write();
        state.generation = state.generation.wrapping_add(1);
        state.cancel = Some(tx.clone());
        state.generation
    };
    spawn(async move {
        let _end = EndKeepalive { tx: Some(tx) };
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(20))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        if let Err(err) = conversation(&client, app, rx, generation).await {
            let current = app.read().generation == generation && app.read().store.is_streaming();
            if current {
                app.write().store.complete_error(&err);
                app.write().ui.toast = friendly_error(&err);
            }
        }
        if app.read().generation == generation {
            app.write().cancel = None;
        }
        app.write().persist();
    });
}

pub fn request_stop(app: Signal<AppState>) {
    if let Some(tx) = app.read().cancel.clone() {
        let _ = tx.send(true);
    }
}

async fn conversation(
    client: &reqwest::Client,
    mut app: Signal<AppState>,
    mut cancel: watch::Receiver<bool>,
    generation: u64,
) -> Result<(), String> {
    let slot = Mutex::new(None::<SandboxSession>);
    let mut tick = tokio::time::interval(Duration::from_secs(25));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    tick.tick().await;
    let mut forced_final = false;
    loop {
        if *cancel.borrow() {
            if app.read().generation == generation && app.read().store.is_streaming() {
                app.write().store.complete_stopped();
            }
            break;
        }
        let outbound = {
            app.read()
                .store
                .current_request()
                .ok_or_else(|| "Nothing is running.".to_string())?
        };
        match read_stream(client, app, &outbound, &mut cancel, &slot, &mut tick).await? {
            Flow::Cancelled => {
                if app.read().generation == generation && app.read().store.is_streaming() {
                    app.write().store.complete_stopped();
                }
                break;
            }
            Flow::Ended => {
                let ended = app.write().store.finish_stream();
                match ended {
                    StreamEnd::Reply => break,
                    StreamEnd::Failed(message) => {
                        app.write().ui.toast = message;
                        break;
                    }
                    StreamEnd::Tools(calls) => {
                        if *cancel.borrow() {
                            app.write().store.complete_stopped();
                            break;
                        }
                        let capped = app.read().store.turn_step() >= app.read().store.max_steps();
                        if capped && forced_final {
                            app.write().store.complete_stopped();
                            break;
                        }
                        let max = app.read().store.max_steps();
                        let mut results = Vec::new();
                        let mut stopped = false;
                        if capped {
                            for call in &calls {
                                results.push(stopped_or_cap(call, &format!(
                                    "Error: maximum agent steps ({max}) reached. Answer with what you have."
                                )));
                            }
                        } else {
                            for call in &calls {
                                if *cancel.borrow() {
                                    stopped = true;
                                    break;
                                }
                                app.write()
                                    .store
                                    .set_status(format!("Running {}…", call.name));
                                keepalive(client, &app, &slot).await;
                                let content = run_one_tool(client, app, &slot, call).await;
                                results.push(ToolResult {
                                    id: call.id.clone(),
                                    name: call.name.clone(),
                                    content,
                                });
                                if *cancel.borrow() {
                                    stopped = true;
                                    break;
                                }
                            }
                        }
                        if stopped {
                            while results.len() < calls.len() {
                                let call = &calls[results.len()];
                                results.push(stopped_or_cap(call, "Error: generation stopped."));
                            }
                            let _ = app.write().store.record_tool_round(&calls, &results, false);
                            app.write().store.complete_stopped();
                            app.write().persist();
                            break;
                        }
                        app.write()
                            .store
                            .record_tool_round(&calls, &results, capped)?;
                        app.write().persist();
                        if capped {
                            forced_final = true;
                        }
                    }
                }
            }
        }
    }
    if let Some(session) = session_of(&slot) {
        let settings = app.read().store.settings.clone();
        if let Ok(outbound) = sandbox_request(&settings, &SandboxOp::Kill(session)) {
            let _ = post_json(client, &outbound).await;
        }
    }
    Ok(())
}

fn stopped_or_cap(call: &ToolCall, content: &str) -> ToolResult {
    ToolResult {
        id: call.id.clone(),
        name: call.name.clone(),
        content: content.to_string(),
    }
}

async fn keepalive(
    client: &reqwest::Client,
    app: &Signal<AppState>,
    slot: &Mutex<Option<SandboxSession>>,
) {
    let Some(session) = session_of(slot) else {
        return;
    };
    let settings = app.read().store.settings.clone();
    if let Ok(outbound) = sandbox_request(&settings, &SandboxOp::Keepalive(session)) {
        let _ = post_json(client, &outbound).await;
    }
}

async fn run_one_tool(
    client: &reqwest::Client,
    app: Signal<AppState>,
    slot: &Mutex<Option<SandboxSession>>,
    call: &ToolCall,
) -> String {
    let invocation = match tool_invocation(&call.name, &call.args) {
        Ok(invocation) => invocation,
        Err(err) => return err,
    };
    if invocation.code.trim().is_empty() {
        return "Error: the tool call did not include any code.".into();
    }
    if session_of(slot).is_none() {
        let settings = app.read().store.settings.clone();
        let outbound = match sandbox_request(
            &settings,
            &SandboxOp::Create {
                timeout_sec: settings.agent_sandbox_timeout,
            },
        ) {
            Ok(outbound) => outbound,
            Err(err) => return format!("Error: {err}"),
        };
        let data = match post_json(client, &outbound).await {
            Ok(data) => data,
            Err(err) => return format!("Error: {err}"),
        };
        let provider = if settings.sandbox_provider == "novita" {
            "novita"
        } else {
            "e2b"
        };
        match parse_session(&data, provider) {
            Ok(session) => {
                if let Ok(mut guard) = slot.lock() {
                    *guard = Some(session);
                }
            }
            Err(err) => return format!("Error: {err}"),
        }
    }
    let Some(session) = session_of(slot) else {
        return "Error: the sandbox is not running.".into();
    };
    let settings = app.read().store.settings.clone();
    let outbound = match sandbox_request(
        &settings,
        &SandboxOp::Exec {
            session,
            code: invocation.code,
            language: invocation.language,
        },
    ) {
        Ok(outbound) => outbound,
        Err(err) => return format!("Error: {err}"),
    };
    match post_json(client, &outbound).await {
        Ok(data) => format_tool_result(&data),
        Err(err) => format!("Error: {err}"),
    }
}

fn session_of(slot: &Mutex<Option<SandboxSession>>) -> Option<SandboxSession> {
    slot.lock().ok().and_then(|guard| guard.clone())
}

async fn read_stream(
    client: &reqwest::Client,
    mut app: Signal<AppState>,
    outbound: &Outbound,
    cancel: &mut watch::Receiver<bool>,
    slot: &Mutex<Option<SandboxSession>>,
    tick: &mut tokio::time::Interval,
) -> Result<Flow, String> {
    let response = send(client, outbound).await?;
    let status = response.status().as_u16();
    if !(200..300).contains(&status) {
        let text = response.text().await.unwrap_or_default();
        return Err(http_error(status, &text));
    }
    let mut bytes = response.bytes_stream();
    loop {
        tokio::select! {
            biased;
            changed = cancel.changed() => {
                if changed.is_err() || *cancel.borrow() {
                    return Ok(Flow::Cancelled);
                }
            }
            _ = tick.tick() => keepalive(client, &app, slot).await,
            next = bytes.next() => {
                match next {
                    None => return Ok(Flow::Ended),
                    Some(Err(err)) => return Err(network_error(&err)),
                    Some(Ok(chunk)) => {
                        let text = String::from_utf8_lossy(&chunk);
                        if app.write().store.apply_stream_chunk(&text) {
                            return Ok(Flow::Ended);
                        }
                    }
                }
            }
        }
    }
}

async fn send(client: &reqwest::Client, outbound: &Outbound) -> Result<reqwest::Response, String> {
    let mut request = client.post(&outbound.url);
    for (key, value) in &outbound.headers {
        request = request.header(key.as_str(), value.as_str());
    }
    request
        .json(&outbound.body)
        .send()
        .await
        .map_err(|err| network_error(&err))
}

async fn post_json(
    client: &reqwest::Client,
    outbound: &Outbound,
) -> Result<serde_json::Value, String> {
    let response = send(client, outbound).await?;
    let status = response.status().as_u16();
    let text = response.text().await.map_err(|err| network_error(&err))?;
    if !(200..300).contains(&status) {
        return Err(http_error(status, &text));
    }
    serde_json::from_str(&text)
        .map_err(|_| "The sandbox returned a response the app could not read.".into())
}

fn http_error(status: u16, body: &str) -> String {
    if let Ok(value) = serde_json::from_str::<serde_json::Value>(body) {
        if let Some(message) = value
            .pointer("/error/message")
            .and_then(|item| item.as_str())
        {
            let clean = message.trim();
            if !clean.is_empty()
                && clean.chars().count() <= 240
                && !clean.starts_with('{')
                && !clean.starts_with('[')
            {
                return clean.to_string();
            }
        }
    }
    match status {
        401 => "Missing or invalid proxy key.".into(),
        403 => {
            "This proxy refused the request. Check the proxy key and the app origin in Settings."
                .into()
        }
        _ => friendly_error(&format!("HTTP {status}")),
    }
}

fn network_error(err: &reqwest::Error) -> String {
    let detail = err.to_string();
    let lower = detail.to_ascii_lowercase();
    if lower.contains("apikey")
        || lower.contains("api_key")
        || lower.contains("bearer")
        || lower.contains("tavily")
    {
        return "Could not reach the API.".into();
    }
    let short: String = detail.chars().take(160).collect();
    format!("Could not reach the API. {short}")
}

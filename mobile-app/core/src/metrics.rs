//! Reply metrics. The numbers match the web client's metrics sheet:
//! tokens, generation and prefill speed, TTFT, context, and stop reason.

use serde_json::Value;

use crate::chat::{Message, TurnMetrics};
use crate::stream::{keep_count, ServerTiming};

const MIN_RATE_WINDOW_MS: u64 = 50;
const MIN_PREFILL_WINDOW_MS: u64 = 20;

#[derive(Clone, Debug, Default)]
pub struct TurnSample {
    pub start_ms: Option<u64>,
    pub first_token_ms: Option<u64>,
    pub first_visible_ms: Option<u64>,
    pub end_ms: Option<u64>,
    pub delta_count: u32,
    pub prompt_tokens: Option<u64>,
    pub completion_tokens: Option<u64>,
    pub cached_tokens: Option<u64>,
    pub cache_write_tokens: Option<u64>,
    pub reasoning_tokens: Option<u64>,
    pub server: Option<ServerTiming>,
    pub sealed: bool,
}

#[derive(Clone, Debug)]
pub struct MetricDraft {
    pub now_ms: Option<u64>,
    pub run_start_ms: Option<u64>,
    pub samples: Vec<TurnSample>,
    pub tool_ms: u64,
    pub tool_calls: u32,
    pub stop_reason: Option<String>,
    pub partial: bool,
    pub produced_chars: usize,
    pub prompt_chars: usize,
    pub prompt_has_images: bool,
    pub context_window: u32,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct ChatTotals {
    pub input: u64,
    pub output: u64,
    pub cached: u64,
    pub replies: u32,
    pub input_estimated: bool,
    pub output_estimated: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MetricRow {
    pub label: String,
    pub value: String,
    pub detail: String,
    pub tone: String,
    pub wide: bool,
    pub bar: Option<f64>,
    pub bar_tone: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhaseSeg {
    pub label: String,
    pub pct: f64,
    pub class_name: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MetricSheet {
    pub summary: String,
    pub summary_tone: String,
    pub rows: Vec<MetricRow>,
    pub phases: Vec<PhaseSeg>,
    pub notes: Vec<String>,
    pub warns: Vec<String>,
}

pub fn finalize_metrics(draft: MetricDraft) -> TurnMetrics {
    let mut samples = draft.samples;
    if let Some(last) = samples.last_mut() {
        if !last.sealed {
            if last.end_ms.is_none() {
                last.end_ms = draft.now_ms;
            }
        }
    }
    for sample in &mut samples {
        if let Some(server) = &sample.server {
            if sample.prompt_tokens.is_none() && server.prompt_tokens.unwrap_or(0) > 0 {
                sample.prompt_tokens = server.prompt_tokens;
            }
            if sample.completion_tokens.is_none() && server.completion_tokens.unwrap_or(0) > 0 {
                sample.completion_tokens = server.completion_tokens;
            }
        }
    }

    let mut metrics = TurnMetrics {
        turns: samples.len() as u32,
        tool_ms: draft.tool_ms,
        tool_calls: draft.tool_calls,
        stop_reason: draft.stop_reason,
        partial: draft.partial,
        context_window: draft.context_window,
        ..TurnMetrics::default()
    };

    if let (Some(start), Some(now)) = (draft.run_start_ms, draft.now_ms) {
        metrics.total_duration_ms = Some(now.saturating_sub(start));
    }
    if let Some(first) = samples.first() {
        if let (Some(start), Some(token)) = (first.start_ms, first.first_token_ms) {
            metrics.ttft_ms = Some(token.saturating_sub(start));
            if let Some(visible) = first.first_visible_ms {
                if visible > token {
                    metrics.think_ms = Some(visible.saturating_sub(token));
                }
            }
        }
    }

    let mut in_sum = 0u64;
    let mut out_sum = 0u64;
    let mut cached_sum = 0u64;
    let mut write_sum = 0u64;
    let mut reason_sum = 0u64;
    let mut saw_in = false;
    let mut saw_out = false;
    let mut prefill_ms = 0u64;
    let mut gen_ms = 0u64;
    let mut delta_count = 0u32;
    let mut srv_prompt_ms = 0u64;
    let mut srv_gen_ms = 0u64;
    let mut srv_engine = None;
    let mut srv_every = !samples.is_empty();
    let now = draft.now_ms.unwrap_or(0);

    for sample in &samples {
        if let Some(value) = sample.prompt_tokens {
            in_sum += value;
            saw_in = true;
        }
        if let Some(value) = sample.completion_tokens {
            out_sum += value;
            saw_out = true;
        }
        cached_sum += sample.cached_tokens.unwrap_or(0);
        write_sum += sample.cache_write_tokens.unwrap_or(0);
        reason_sum += sample.reasoning_tokens.unwrap_or(0);
        delta_count += sample.delta_count;
        let end = sample.end_ms.unwrap_or(now);
        if let Some(start) = sample.start_ms {
            if let Some(token) = sample.first_token_ms {
                prefill_ms += token.saturating_sub(start);
                gen_ms += end.saturating_sub(token);
            } else {
                prefill_ms += end.saturating_sub(start);
            }
        }
        if let Some(server) = &sample.server {
            srv_engine = server.source.clone().or(srv_engine);
            srv_prompt_ms += server.prompt_ms.unwrap_or(0);
            srv_gen_ms += server.gen_ms.unwrap_or(0);
        } else {
            srv_every = false;
        }
    }

    metrics.prefill_ms = Some(prefill_ms);
    metrics.cached_tokens = (cached_sum > 0).then_some(cached_sum);
    metrics.cache_write_tokens = (write_sum > 0).then_some(write_sum);
    metrics.reasoning_tokens = (reason_sum > 0).then_some(reason_sum);

    if !saw_out {
        if draft.produced_chars > 0 {
            out_sum = (draft.produced_chars as f64 / 3.5).round().max(1.0) as u64;
            metrics.output_estimated = true;
        } else {
            metrics.output_tokens = None;
        }
    }
    if !saw_in {
        if draft.prompt_has_images {
            metrics.input_tokens = None;
        } else if draft.prompt_chars > 0 {
            in_sum = (draft.prompt_chars as f64 / 3.5).round().max(1.0) as u64;
            metrics.input_estimated = true;
        }
    }
    if saw_out || metrics.output_estimated {
        metrics.output_tokens = Some(out_sum);
    }
    if saw_in || metrics.input_estimated {
        metrics.input_tokens = Some(in_sum);
    }
    metrics.tokens_estimated = metrics.input_estimated || metrics.output_estimated;

    if samples.len() <= 1 {
        if metrics.input_tokens.is_none() && metrics.output_tokens.is_none() {
            metrics.context_used = None;
            metrics.context_estimated = false;
        } else {
            metrics.context_used =
                Some(metrics.input_tokens.unwrap_or(0) + metrics.output_tokens.unwrap_or(0));
            metrics.context_estimated = metrics.input_estimated || metrics.output_estimated;
        }
    } else if let Some(last) = samples.last() {
        if last.prompt_tokens.is_some() || last.completion_tokens.is_some() {
            metrics.context_used =
                Some(last.prompt_tokens.unwrap_or(0) + last.completion_tokens.unwrap_or(0));
            metrics.context_estimated = false;
        }
    }

    let out_tokens = metrics.output_tokens.unwrap_or(0);
    let in_tokens = metrics.input_tokens.unwrap_or(0);
    if srv_every && srv_gen_ms > 0 && out_tokens > 0 {
        metrics.generation_tokens_per_second = Some(out_tokens as f64 / srv_gen_ms as f64 * 1000.0);
        metrics.rate_source = Some("server".into());
        metrics.rate_engine = srv_engine.clone();
        metrics.gen_window_ms = Some(srv_gen_ms);
    } else if out_tokens > 0 && delta_count >= 2 && gen_ms >= MIN_RATE_WINDOW_MS {
        metrics.generation_tokens_per_second = Some(out_tokens as f64 / gen_ms as f64 * 1000.0);
        metrics.rate_source = Some("client".into());
        metrics.gen_window_ms = Some(gen_ms);
    } else if out_tokens > 0 {
        metrics.rate_unmeasurable = true;
    }
    if srv_every && srv_prompt_ms > 0 && in_tokens > 0 {
        metrics.prompt_tokens_per_second = Some(in_tokens as f64 / srv_prompt_ms as f64 * 1000.0);
        metrics.prompt_rate_source = Some("server".into());
        if metrics.rate_engine.is_none() {
            metrics.rate_engine = srv_engine;
        }
        metrics.prompt_window_ms = Some(srv_prompt_ms);
    } else if in_tokens > 0 && prefill_ms >= MIN_PREFILL_WINDOW_MS {
        metrics.prompt_tokens_per_second = Some(in_tokens as f64 / prefill_ms as f64 * 1000.0);
        metrics.prompt_rate_source = Some("client".into());
        metrics.prompt_window_ms = Some(prefill_ms);
    }
    metrics
}

pub fn prompt_stats(body: &Value) -> (usize, bool) {
    let mut chars = 0usize;
    let mut images = false;
    let Some(messages) = body.get("messages").and_then(|v| v.as_array()) else {
        return (0, false);
    };
    for message in messages {
        chars += content_len(message.get("content"));
        if content_has_image(message.get("content")) {
            images = true;
        }
    }
    (chars, images)
}

fn content_len(content: Option<&Value>) -> usize {
    match content {
        Some(Value::String(text)) => text.len(),
        Some(Value::Array(parts)) => parts
            .iter()
            .map(|part| {
                part.get("text")
                    .and_then(|v| v.as_str())
                    .map(|text| text.len())
                    .unwrap_or(24)
            })
            .sum(),
        _ => 0,
    }
}

fn content_has_image(content: Option<&Value>) -> bool {
    let Some(parts) = content.and_then(|v| v.as_array()) else {
        return false;
    };
    parts.iter().any(|part| {
        part.get("type").and_then(|v| v.as_str()) == Some("image_url")
            || part.get("text").map(|v| !v.is_string()).unwrap_or(true)
    })
}

pub fn produced_chars(message: &Message) -> usize {
    let mut total = message.content.len() + message.thinking.len();
    for step in &message.steps {
        total += step.text.len();
    }
    total
}

pub fn chat_totals(messages: &[Message]) -> ChatTotals {
    let mut totals = ChatTotals::default();
    for message in messages {
        if message.role != "assistant" {
            continue;
        }
        let Some(metrics) = &message.metrics else {
            continue;
        };
        if metrics.input_tokens.is_none() && metrics.output_tokens.is_none() {
            continue;
        }
        totals.input += metrics.input_tokens.unwrap_or(0);
        totals.output += metrics.output_tokens.unwrap_or(0);
        totals.cached += metrics.cached_tokens.unwrap_or(0);
        totals.replies += 1;
        if metrics.input_estimated {
            totals.input_estimated = true;
        }
        if metrics.output_estimated {
            totals.output_estimated = true;
        }
    }
    totals
}

pub fn display_metrics(messages: &[Message]) -> Option<TurnMetrics> {
    messages.iter().rev().find_map(|message| {
        if message.role == "assistant" {
            message.metrics.clone()
        } else {
            None
        }
    })
}

pub fn metric_sheet(metrics: &TurnMetrics, totals: &ChatTotals) -> MetricSheet {
    let mut rows = Vec::new();
    let mut notes = Vec::new();
    let mut warns = Vec::new();
    let est_in = metrics.input_estimated;
    let est_out = metrics.output_estimated;
    let gen = metrics.generation_tokens_per_second;
    let gen_tier = gen.map(speed_tier);
    if let Some(rate) = gen {
        let mut detail = vec![gen_tier.unwrap_or("").to_string()];
        if let Some(window) = metrics.gen_window_ms {
            detail.push(format!("over {}", format_ms(window)));
        }
        rows.push(MetricRow {
            label: "Gen t/s".into(),
            value: format!(
                "{}{} t/s",
                if est_out { "~" } else { "" },
                format_rate(rate)
            ),
            detail: detail.join(" · "),
            tone: speed_class(rate).into(),
            wide: false,
            bar: None,
            bar_tone: String::new(),
        });
    }
    if let Some(rate) = metrics.prompt_tokens_per_second {
        let from_server = metrics.prompt_rate_source.as_deref() == Some("server");
        rows.push(MetricRow {
            label: "Prefill t/s".into(),
            value: format!("{}{} t/s", if est_in { "~" } else { "" }, format_rate(rate)),
            detail: if from_server {
                "from the server".into()
            } else {
                "upper bound".into()
            },
            tone: String::new(),
            wide: false,
            bar: None,
            bar_tone: String::new(),
        });
    }
    if let Some(ttft) = metrics.ttft_ms {
        let detail = metrics
            .think_ms
            .filter(|ms| *ms > 0)
            .map(|ms| format!("+{} to answer", format_ms(ms)))
            .unwrap_or_default();
        rows.push(MetricRow {
            label: "TTFT".into(),
            value: format_ms(ttft),
            detail,
            tone: String::new(),
            wide: false,
            bar: None,
            bar_tone: String::new(),
        });
    }
    if let Some(total) = metrics.total_duration_ms {
        let mut detail = Vec::new();
        if metrics.turns > 1 {
            detail.push(format!("{} turns", metrics.turns));
        }
        if metrics.tool_calls > 0 {
            let label = if metrics.tool_calls == 1 {
                "tool call"
            } else {
                "tool calls"
            };
            detail.push(format!("{} {label}", metrics.tool_calls));
        }
        rows.push(MetricRow {
            label: "Total".into(),
            value: format_ms(total),
            detail: detail.join(" · "),
            tone: String::new(),
            wide: false,
            bar: None,
            bar_tone: String::new(),
        });
    }
    let phases = phase_segments(metrics);
    if metrics.input_tokens.is_some() || metrics.output_tokens.is_some() || !phases.is_empty() {
        // The time breakdown is rendered from `phases`. A row keeps a label.
        if !phases.is_empty() {
            rows.push(MetricRow {
                label: "Time breakdown".into(),
                value: String::new(),
                detail: String::new(),
                tone: String::new(),
                wide: true,
                bar: None,
                bar_tone: String::new(),
            });
        }
    }
    if let Some(input) = metrics.input_tokens {
        let mut detail = Vec::new();
        if let Some(cached) = metrics.cached_tokens {
            detail.push(format!("{} cached", fmt_int(cached)));
        }
        if let Some(written) = metrics.cache_write_tokens {
            detail.push(format!("{} written", fmt_int(written)));
        }
        if metrics.turns > 1 {
            detail.push(format!("over {} turns", metrics.turns));
        }
        rows.push(MetricRow {
            label: "Input".into(),
            value: format!("{}{}", if est_in { "~" } else { "" }, fmt_int(input)),
            detail: detail.join(" · "),
            tone: String::new(),
            wide: false,
            bar: None,
            bar_tone: String::new(),
        });
    }
    if let Some(output) = metrics.output_tokens {
        let mut detail = Vec::new();
        if let Some(reasoning) = metrics.reasoning_tokens {
            if reasoning <= output {
                detail.push(format!("{} reasoning", fmt_int(reasoning)));
                detail.push(format!("{} visible", fmt_int(output - reasoning)));
            }
        }
        rows.push(MetricRow {
            label: "Output".into(),
            value: format!("{}{}", if est_out { "~" } else { "" }, fmt_int(output)),
            detail: detail.join(" · "),
            tone: String::new(),
            wide: false,
            bar: None,
            bar_tone: String::new(),
        });
    }
    if let Some(used) = metrics.context_used {
        let window = metrics.context_window.max(1) as f64;
        let pct = ((used as f64 / window) * 100.0).min(100.0);
        let (tone, bar_tone, word) = context_tier(pct);
        let left = metrics.context_window.saturating_sub(used as u32);
        rows.push(MetricRow {
            label: "Context usage".into(),
            value: format!(
                "{}{} / {} ({:.1}%)",
                if metrics.context_estimated { "~" } else { "" },
                fmt_int(used),
                fmt_int(metrics.context_window as u64),
                pct
            ),
            detail: format!("{word} · {} left", fmt_int(left as u64)),
            tone: tone.into(),
            wide: true,
            bar: Some(pct),
            bar_tone: bar_tone.into(),
        });
    }
    if totals.replies > 0 {
        let reply = if totals.replies == 1 {
            "reply"
        } else {
            "replies"
        };
        let mut detail = format!(
            "{} across {} {reply}",
            fmt_int(totals.input + totals.output),
            totals.replies
        );
        if totals.cached > 0 {
            detail.push_str(&format!(" · {} cached", fmt_int(totals.cached)));
        }
        rows.push(MetricRow {
            label: "Billed this chat".into(),
            value: format!(
                "{}{} in / {}{} out",
                if totals.input_estimated { "~" } else { "" },
                fmt_int(totals.input),
                if totals.output_estimated { "~" } else { "" },
                fmt_int(totals.output)
            ),
            detail,
            tone: String::new(),
            wide: true,
            bar: None,
            bar_tone: String::new(),
        });
    }

    if metrics.partial {
        warns.push("partial — the reply did not finish".into());
    }
    if let Some(text) = stop_text(metrics.stop_reason.as_deref()) {
        if metrics.stop_reason.as_deref() == Some("stop_sequence") {
            notes.push(text);
        } else {
            warns.push(text);
        }
    }
    if metrics.rate_unmeasurable {
        notes.push("arrived too fast to time a generation rate".into());
    }
    if est_in && est_out {
        notes.push("counts estimated from text length".into());
    } else if est_in {
        notes.push("input estimated from text length".into());
    } else if est_out {
        notes.push("output estimated from text length".into());
    } else if metrics.input_tokens.is_some() || metrics.output_tokens.is_some() {
        notes.push("counts reported by the provider".into());
    }
    if let Some(engine) = &metrics.rate_engine {
        notes.push(format!("timings from {engine}"));
    }

    let mut summary = Vec::new();
    if let Some(rate) = gen {
        summary.push(format!(
            "{}{} t/s",
            if est_out { "~" } else { "" },
            format_rate(rate)
        ));
    }
    if let Some(output) = metrics.output_tokens {
        summary.push(format!(
            "{}{} tok",
            if est_out { "~" } else { "" },
            fmt_int(output)
        ));
    }
    if let Some(ttft) = metrics.ttft_ms {
        summary.push(format!("{} ttft", format_ms(ttft)));
    }
    if let Some(total) = metrics.total_duration_ms {
        summary.push(format_ms(total));
    }
    if metrics.partial {
        summary.push("incomplete".into());
    } else if metrics.stop_reason.as_deref() == Some("max_tokens") {
        summary.push("truncated".into());
    }
    MetricSheet {
        summary: if summary.is_empty() {
            "—".into()
        } else {
            summary.join(" · ")
        },
        summary_tone: gen.map(speed_class).unwrap_or("").into(),
        rows,
        phases,
        notes,
        warns,
    }
}

fn phase_segments(metrics: &TurnMetrics) -> Vec<PhaseSeg> {
    let Some(total) = metrics.total_duration_ms.filter(|ms| *ms > 0) else {
        return Vec::new();
    };
    let mut wait = metrics.prefill_ms.unwrap_or(metrics.ttft_ms.unwrap_or(0));
    let mut think = metrics.think_ms.unwrap_or(0);
    let mut tool = metrics.tool_ms;
    let head = wait + think + tool;
    if head > total {
        let scale = total as f64 / head as f64;
        wait = (wait as f64 * scale) as u64;
        think = (think as f64 * scale) as u64;
        tool = (tool as f64 * scale) as u64;
    }
    let gen = total.saturating_sub(wait + think + tool);
    let parts = [
        ("prefill", "wait", wait),
        ("reasoning", "think", think),
        ("answer", "gen", gen),
        ("tools", "tool", tool),
    ];
    parts
        .into_iter()
        .filter(|(_, _, ms)| *ms > 0)
        .map(|(label, class_name, ms)| PhaseSeg {
            label: format!("{label} {}", format_ms(ms)),
            pct: ms as f64 / total as f64 * 100.0,
            class_name: class_name.into(),
        })
        .collect()
}

fn stop_text(reason: Option<&str>) -> Option<String> {
    match reason {
        Some("max_tokens") => Some("truncated — hit the output cap".into()),
        Some("content_filter") => Some("stopped by a content filter".into()),
        Some("stop_sequence") => Some("hit a stop sequence".into()),
        Some("error") => Some("the provider ended the turn on an error".into()),
        _ => None,
    }
}

fn speed_tier(rate: f64) -> &'static str {
    if rate < 8.0 {
        "crawling"
    } else if rate < 30.0 {
        "steady"
    } else if rate < 120.0 {
        "quick"
    } else {
        "blazing"
    }
}

fn speed_class(rate: f64) -> &'static str {
    if rate < 8.0 {
        "speed-1"
    } else if rate < 30.0 {
        "speed-2"
    } else if rate < 120.0 {
        "speed-3"
    } else {
        "speed-4"
    }
}

fn context_tier(pct: f64) -> (&'static str, &'static str, &'static str) {
    if pct < 50.0 {
        ("", "status-good", "plenty of room")
    } else if pct < 75.0 {
        ("status-warning", "status-warning", "past halfway")
    } else if pct < 90.0 {
        ("status-serious", "status-serious", "filling up")
    } else {
        ("status-critical", "status-critical", "almost full")
    }
}

pub fn format_ms(ms: u64) -> String {
    if ms < 1000 {
        return format!("{ms} ms");
    }
    if ms < 10_000 {
        return format!("{:.2} s", ms as f64 / 1000.0);
    }
    if ms < 60_000 {
        return format!("{:.1} s", ms as f64 / 1000.0);
    }
    let mut mins = ms / 60_000;
    let mut secs = (ms % 60_000) as f64 / 1000.0;
    let rounded = secs.round() as u64;
    if rounded == 60 {
        mins += 1;
        secs = 0.0;
    } else {
        secs = rounded as f64;
    }
    format!("{mins}m {secs:02.0}s")
}

pub fn format_rate(value: f64) -> String {
    if !value.is_finite() {
        return "—".into();
    }
    if value >= 1000.0 {
        return fmt_int(value.round() as u64);
    }
    if value >= 100.0 {
        return format!("{value:.0}");
    }
    if value >= 10.0 {
        return format!("{value:.1}");
    }
    format!("{value:.2}")
}

pub fn fmt_int(value: u64) -> String {
    let raw = value.to_string();
    let mut out = String::new();
    for (index, ch) in raw.chars().rev().enumerate() {
        if index > 0 && index % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out.chars().rev().collect()
}

pub fn merge_timing(current: &mut Option<ServerTiming>, incoming: ServerTiming) {
    match current {
        None => *current = Some(incoming),
        Some(existing) => {
            if incoming.source.is_some() {
                existing.source = incoming.source;
            }
            existing.prompt_tokens = keep_count(existing.prompt_tokens, incoming.prompt_tokens);
            existing.completion_tokens =
                keep_count(existing.completion_tokens, incoming.completion_tokens);
            existing.prompt_ms = keep_count(existing.prompt_ms, incoming.prompt_ms);
            existing.gen_ms = keep_count(existing.gen_ms, incoming.gen_ms);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_window_reports_ttft_rate_and_context() {
        let draft = MetricDraft {
            now_ms: Some(1_600),
            run_start_ms: Some(1_000),
            samples: vec![TurnSample {
                start_ms: Some(1_000),
                first_token_ms: Some(1_100),
                first_visible_ms: Some(1_100),
                end_ms: Some(1_600),
                delta_count: 4,
                prompt_tokens: Some(20),
                completion_tokens: Some(50),
                sealed: true,
                ..TurnSample::default()
            }],
            tool_ms: 0,
            tool_calls: 0,
            stop_reason: Some("end_turn".into()),
            partial: false,
            produced_chars: 10,
            prompt_chars: 10,
            prompt_has_images: false,
            context_window: 8192,
        };
        let metrics = finalize_metrics(draft);
        assert_eq!(metrics.ttft_ms, Some(100));
        assert_eq!(metrics.input_tokens, Some(20));
        assert_eq!(metrics.output_tokens, Some(50));
        assert_eq!(metrics.context_used, Some(70));
        assert_eq!(metrics.gen_window_ms, Some(500));
        let rate = metrics.generation_tokens_per_second.unwrap();
        assert!((rate - 100.0).abs() < 0.01);
        assert_eq!(metrics.rate_source.as_deref(), Some("client"));
        assert!(metrics.stop_reason.as_deref() == Some("end_turn"));
        let sheet = metric_sheet(&metrics, &ChatTotals::default());
        assert!(sheet.summary.contains("t/s"));
        assert!(sheet.summary.contains("ttft"));
        assert!(sheet.rows.iter().any(|row| row.label == "Context usage"));
        assert!(sheet.notes.iter().any(|note| note.contains("provider")));
        assert!(sheet.warns.is_empty());
    }

    #[test]
    fn max_tokens_is_called_out_and_a_single_chunk_is_not_a_rate() {
        let draft = MetricDraft {
            now_ms: Some(30),
            run_start_ms: Some(0),
            samples: vec![TurnSample {
                start_ms: Some(0),
                first_token_ms: Some(10),
                first_visible_ms: Some(10),
                end_ms: Some(30),
                delta_count: 1,
                completion_tokens: Some(8),
                prompt_tokens: Some(4),
                sealed: true,
                ..TurnSample::default()
            }],
            tool_ms: 0,
            tool_calls: 0,
            stop_reason: Some("max_tokens".into()),
            partial: false,
            produced_chars: 8,
            prompt_chars: 4,
            prompt_has_images: false,
            context_window: 100,
        };
        let metrics = finalize_metrics(draft);
        assert!(metrics.generation_tokens_per_second.is_none());
        assert!(metrics.rate_unmeasurable);
        let sheet = metric_sheet(&metrics, &ChatTotals::default());
        assert!(sheet.summary.contains("truncated"));
        assert!(sheet.warns.iter().any(|note| note.contains("output cap")));
    }
}

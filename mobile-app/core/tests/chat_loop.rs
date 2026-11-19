use std::fs;
use std::time::UNIX_EPOCH;

use omni_core::{SendOpts, Store, StreamEnd, ToolResult};

fn ready() -> Store {
    let mut store = Store::fresh();
    store
        .settings
        .set_api_key("openai", "sk-test-secret-value".into());
    store
}

#[test]
fn streams_a_reply_and_keeps_the_key_out_of_the_transcript() {
    let dir = std::env::temp_dir().join(format!(
        "omni-loop-{}-{}",
        UNIX_EPOCH.elapsed().unwrap_or_default().as_nanos(),
        std::process::id()
    ));
    fs::create_dir_all(&dir).unwrap();
    let mut store = ready();
    let prepared = store
        .begin_send("Hello there", SendOpts::default())
        .unwrap();
    assert_eq!(prepared.outbound.body["stream"], true);
    assert_eq!(prepared.outbound.body["provider"], "openai");
    assert!(!store.apply_stream_chunk("{\"delta\":\"Hi\"}\n{\"delta\":\" there\"}\n{\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n"));
    match store.finish_stream() {
        StreamEnd::Reply => {}
        other => panic!("expected a finished reply, got {other:?}"),
    }
    let chat = store.active().unwrap();
    assert_eq!(chat.messages[0].content, "Hello there");
    assert_eq!(chat.messages[1].content, "Hi there");
    assert_eq!(
        chat.messages[1].metrics.as_ref().unwrap().output_tokens,
        Some(2)
    );
    assert!(chat.title.starts_with("Hello there"));
    store.save(&dir).unwrap();
    let chats = fs::read_to_string(dir.join("chats.json")).unwrap();
    assert!(!chats.contains("sk-test-secret-value"));
    let settings = fs::read_to_string(dir.join("settings.json")).unwrap();
    assert!(settings.contains("sk-test-secret-value"));
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = fs::metadata(dir.join("settings.json"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(mode, 0o600);
    }
    let loaded = Store::load(&dir);
    assert_eq!(loaded.active().unwrap().messages[1].content, "Hi there");
    assert_eq!(
        loaded.settings.api_key_for("openai"),
        "sk-test-secret-value"
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn stops_a_generation() {
    let mut store = ready();
    store.begin_send("Hold on", SendOpts::default()).unwrap();
    store.apply_stream_chunk("{\"delta\":\"Partial\"}");
    store.complete_stopped();
    assert!(!store.is_streaming());
    assert_eq!(store.active().unwrap().messages[1].content, "Partial");
}

#[test]
fn empty_stop_marks_the_message() {
    let mut store = ready();
    store.begin_send("Hold on", SendOpts::default()).unwrap();
    store.complete_stopped();
    assert_eq!(
        store.active().unwrap().messages[1].content,
        "*(Generation stopped)*"
    );
}

#[test]
fn tool_round_then_final_answer() {
    let mut store = ready();
    store.settings.set_sandbox_key("e2b_test".into());
    store.new_chat();
    store.toggle_agent().unwrap();
    let prepared = store
        .begin_send("What is 2 + 2?", SendOpts::default())
        .unwrap();
    assert!(prepared.outbound.body.get("tools").is_some());
    assert!(prepared.outbound.body.get("tavilyKey").is_none());
    let chunk = concat!(
        r#"{"tool_start":{"i":0,"id":"call_1","name":"run_python"}}"#,
        "\n",
        r#"{"tool_args":{"i":0,"d":"{\"code\":\"print(4)\"}"}}"#,
        "\n",
    );
    assert!(!store.apply_stream_chunk(chunk));
    let calls = match store.finish_stream() {
        StreamEnd::Tools(calls) => calls,
        other => panic!("expected tools, got {other:?}"),
    };
    assert_eq!(calls[0].name, "run_python");
    assert_eq!(calls[0].args["code"], "print(4)");
    let next = store
        .record_tool_round(
            &calls,
            &[ToolResult {
                id: calls[0].id.clone(),
                name: calls[0].name.clone(),
                content: "4\n".into(),
            }],
            false,
        )
        .unwrap();
    assert!(next.body["messages"]
        .as_array()
        .unwrap()
        .iter()
        .any(|message| message["role"] == "tool"));
    store.apply_stream_chunk("{\"delta\":\"The answer is 4.\"}\n");
    assert!(matches!(store.finish_stream(), StreamEnd::Reply));
    let message = &store.active().unwrap().messages[1];
    assert_eq!(message.content, "The answer is 4.");
    assert_eq!(message.steps.len(), 1);
    assert_eq!(message.steps[0].results[0].content, "4\n");
}

#[test]
fn regenerates_and_cycles_branches() {
    let mut store = ready();
    store
        .begin_send("Explain rust", SendOpts::default())
        .unwrap();
    store.apply_stream_chunk("{\"delta\":\"First reply\"}\n");
    store.finish_stream();
    let again = store.prepare_regenerate(1).unwrap();
    assert_eq!(again, "Explain rust");
    store.begin_send(&again, SendOpts::default()).unwrap();
    store.apply_stream_chunk("{\"delta\":\"Second reply\"}\n");
    store.finish_stream();
    let chat = store.active().unwrap();
    assert_eq!(chat.messages.len(), 2);
    assert_eq!(chat.messages[1].content, "Second reply");
    store.cycle_branch(0, -1).unwrap();
    assert_eq!(store.active().unwrap().messages[1].content, "First reply");
    store.cycle_branch(0, 1).unwrap();
    assert_eq!(store.active().unwrap().messages[1].content, "Second reply");
}

#[test]
fn pins_sort_above_newer_chats_and_search_finds_message_text() {
    let mut store = ready();
    let older = store.new_chat();
    store.rename_chat(&older, "Pinned note").unwrap();
    store.toggle_pin(&older);
    let newer = store.new_chat();
    store.rename_chat(&newer, "Other").unwrap();
    store
        .begin_send("unique-needle", SendOpts::default())
        .unwrap();
    store.complete_stopped();
    let list = store.chat_list("");
    assert_eq!(list[0].id, older);
    let found = store.chat_list("unique-needle");
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].id, newer);
    assert!(found[0]
        .snippet
        .as_deref()
        .unwrap_or("")
        .contains("unique-needle"));
}

#[test]
fn slash_search_without_a_key_does_not_fail_the_chat() {
    let mut store = ready();
    let prepared = store
        .begin_send("/search latest rust release", SendOpts::default())
        .unwrap();
    assert_eq!(
        prepared.outbound.body["messages"]
            .as_array()
            .unwrap()
            .last()
            .unwrap()["content"],
        "latest rust release"
    );
    assert_eq!(prepared.outbound.body["searchMode"], "off");
    assert!(prepared.notice.is_some());
    store.complete_stopped();
}

#[test]
fn inline_think_tags_stay_out_of_the_answer() {
    let mut store = ready();
    store.begin_send("Think", SendOpts::default()).unwrap();
    store.apply_stream_chunk("{\"delta\":\"<think>secret</think>Visible\"}\n");
    store.finish_stream();
    let message = &store.active().unwrap().messages[1];
    assert_eq!(message.content, "Visible");
    assert_eq!(message.thinking, "secret");
}

#[test]
fn provider_error_event_becomes_a_short_message() {
    let mut store = ready();
    store.begin_send("Hi", SendOpts::default()).unwrap();
    assert!(store.apply_stream_chunk("{\"error\":{\"message\":\"HTTP 401 bad\"}}\n"));
    match store.finish_stream() {
        StreamEnd::Failed(message) => {
            assert!(message.contains("API key") || message.contains("rejected"))
        }
        other => panic!("expected failure, got {other:?}"),
    }
}

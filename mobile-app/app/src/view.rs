//! Phone UI. Widgets only — the chat rules live in `omni-core`.

use dioxus::prelude::*;
use dioxus::prelude::{ReadableExt, WritableExt};
use omni_core::{
    all_providers, available_models, parse_markdown, provider, Block, CustomModel, ExtraModel,
    Inline, Message, SendOpts, Settings,
};

use crate::session::{request_stop, spawn_generation};
use crate::state::{AppState, Tab};

const CSS: &str = include_str!("../assets/main.css");

pub fn app() -> Element {
    let mut state = use_signal(AppState::load);
    let tab = state.read().ui.tab;
    let toast = state.read().ui.toast.clone();
    let sheet = state.read().ui.model_sheet;
    rsx! {
        style { dangerous_inner_html: CSS }
        div { class: "app",
            div { class: "screen",
                match tab {
                    Tab::Chats => rsx! { Chats { state } },
                    Tab::Thread => rsx! { Thread { state } },
                    Tab::Settings => rsx! { SettingsPage { state } },
                }
            }
            if tab == Tab::Thread {
                Composer { state }
            }
            nav { class: "nav",
                NavButton { state, tab: Tab::Chats, label: "Chats" }
                NavButton { state, tab: Tab::Thread, label: "Chat" }
                NavButton { state, tab: Tab::Settings, label: "Settings" }
            }
            if !toast.is_empty() {
                button {
                    class: "toast",
                    r#type: "button",
                    onclick: move |_| state.write().ui.toast.clear(),
                    "{toast}"
                }
            }
            if sheet {
                ModelSheet { state }
            }
        }
    }
}

#[component]
fn NavButton(state: Signal<AppState>, tab: Tab, label: &'static str) -> Element {
    let mut state = state;
    let on = state.read().ui.tab == tab;
    let nav_class = if on { "nav-btn on" } else { "nav-btn" };
    rsx! {
        button {
            class: nav_class,
            r#type: "button",
            onclick: move |_| state.write().ui.tab = tab,
            "{label}"
        }
    }
}

#[component]
fn Chats(state: Signal<AppState>) -> Element {
    let mut state = state;
    let query = state.read().ui.query.clone();
    let items = state.read().store.chat_list(&query);
    let incognito = state.read().store.incognito;
    let pending = state.read().ui.pending_delete.clone();
    rsx! {
        header { class: "topbar",
            div {
                h1 { "Omni" }
                if incognito {
                    p { class: "incognito", "Incognito — this chat stays on this screen only" }
                }
            }
            div { class: "top-actions",
                button { class: "text", r#type: "button", onclick: move |_| toggle_incognito(state),
                    if incognito { "Exit" } else { "Incognito" }
                }
                button { class: "primary", r#type: "button", onclick: move |_| new_chat(state), "New" }
            }
        }
        div { class: "search-wrap",
            input {
                class: "field",
                placeholder: "Search chats",
                value: "{query}",
                oninput: move |event| state.write().ui.query = event.value(),
            }
        }
        div { class: "list",
            if items.is_empty() {
                p { class: "empty",
                    if query.trim().is_empty() { "No chats yet. Start one with New." } else { "No matching chats." }
                }
            }
            for item in items {
                {
                    let id = item.id.clone();
                    let delete_now = id.clone();
                    let delete_ask = id.clone();
                    let pin_id = id.clone();
                    let open_id = id.clone();
                    let confirming = pending.as_deref() == Some(id.as_str());
                    rsx! {
                        div { class: "chat-row", key: "{id}",
                            button {
                                class: "row-main",
                                r#type: "button",
                                onclick: move |_| open_chat(state, &open_id),
                                span { class: "row-title",
                                    if item.streaming { span { class: "dot", aria_label: "Replying" } }
                                    if item.pinned { span { class: "tag", "Pinned" } }
                                    "{item.title}"
                                }
                                if let Some(snippet) = item.snippet.clone() {
                                    span { class: "snippet", "{snippet}" }
                                }
                            }
                            if confirming {
                                button { class: "danger", r#type: "button", onclick: move |_| confirm_delete(state, &delete_now), "Delete" }
                                button { class: "text", r#type: "button", onclick: move |_| state.write().ui.pending_delete = None, "Keep" }
                            } else {
                                if !item.incognito {
                                    button {
                                        class: "text",
                                        r#type: "button",
                                        onclick: move |_| {
                                            state.write().store.toggle_pin(&pin_id);
                                            state.write().persist();
                                        },
                                        if item.pinned { "Unpin" } else { "Pin" }
                                    }
                                }
                                button {
                                    class: "text",
                                    r#type: "button",
                                    onclick: move |_| state.write().ui.pending_delete = Some(delete_ask.clone()),
                                    "Delete"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

#[component]
fn Thread(state: Signal<AppState>) -> Element {
    let mut state = state;
    let chat = state.read().store.active().cloned();
    let streaming = state.read().store.is_streaming();
    let status = state.read().store.status();
    let follow = state.read().ui.follow;
    let renaming = state.read().ui.renaming;
    let rename_draft = state.read().ui.rename_draft.clone();
    let incognito = state.read().store.incognito;
    let agent = chat.as_ref().map(|chat| chat.agent_mode).unwrap_or(false);
    let title = chat
        .as_ref()
        .map(|chat| chat.title.clone())
        .unwrap_or_else(|| "Chat".into());
    let chat_id = chat
        .as_ref()
        .map(|chat| chat.id.clone())
        .unwrap_or_default();
    let messages = chat
        .as_ref()
        .map(|chat| chat.messages.clone())
        .unwrap_or_default();
    let count = messages.len();

    use_effect(move || {
        let follow_now = state.read().ui.follow;
        let size = state
            .read()
            .store
            .active()
            .and_then(|chat| chat.messages.last())
            .map(|message| message.content.len() + message.thinking.len())
            .unwrap_or(0);
        if follow_now && size > 0 {
            scroll_thread();
        }
    });

    rsx! {
        header { class: "topbar",
            if renaming {
                input {
                    class: "field grow",
                    value: "{rename_draft}",
                    oninput: move |event| state.write().ui.rename_draft = event.value(),
                }
                button { class: "primary", r#type: "button", onclick: move |_| apply_rename(state, &chat_id), "Save" }
            } else {
                div { class: "title-block",
                    h1 { "{title}" }
                    if incognito { p { class: "incognito", "Incognito" } }
                    if agent { p { class: "agent", "Agent" } }
                }
                div { class: "top-actions",
                    button { class: "text", r#type: "button", onclick: move |_| start_rename(state), "Rename" }
                    button {
                        class: "text",
                        r#type: "button",
                        onclick: move |_| {
                            let next = !state.read().ui.follow;
                            state.write().ui.follow = next;
                        },
                        if follow { "Pause" } else { "Latest" }
                    }
                }
            }
        }
        div { id: "thread", class: "thread",
            if messages.is_empty() {
                p { class: "empty", "Send a message to start. Pick a model and add your API key in Settings." }
            }
            for (index, message) in messages.into_iter().enumerate() {
                MessageView {
                    key: "{index}",
                    state,
                    message,
                    index,
                    live: streaming && index + 1 == count,
                    status: status.clone(),
                }
            }
        }
    }
}

#[component]
fn MessageView(
    state: Signal<AppState>,
    message: Message,
    index: usize,
    live: bool,
    status: String,
) -> Element {
    let role = message.role.clone();
    let user = role == "user";
    let class = if user {
        "bubble user"
    } else {
        "bubble assistant"
    };
    let blocks = parse_markdown(&message.content);
    let thinking = message.thinking.clone();
    let thinking_open = !message.thinking_complete && !thinking.is_empty();
    let steps = message.steps.clone();
    let search = message.search.clone();
    let metrics = message.metrics.clone().map(|metrics| metric_line(&metrics));
    let show_actions = !user && !live && (!message.content.is_empty() || !message.steps.is_empty());
    let branch = if show_actions {
        state.read().store.branch_state(index)
    } else {
        None
    };
    let caret = if live && !message.content.is_empty() {
        " caret"
    } else {
        ""
    };
    let body_class = format!("bubble-body{caret}");

    rsx! {
        article { class: "{class}",
            if user {
                p { class: "who", "You" }
            } else {
                p { class: "who", "Omni" }
            }
            for step in steps {
                if !step.text.trim().is_empty() {
                    div { class: "step-text", {render_blocks(&parse_markdown(&step.text))} }
                }
                for call in step.calls {
                    {
                        let output = step.results.iter().find(|result| result.id == call.id).map(|result| result.content.clone()).unwrap_or_else(|| {
                            if live { "Running…".into() } else { "No result.".into() }
                        });
                        let name = call.name.clone();
                        rsx! {
                            details { class: "tool",
                                summary { "{name}" }
                                pre { "{output}" }
                            }
                        }
                    }
                }
            }
            if !thinking.is_empty() {
                details { class: "thinking", open: thinking_open,
                    summary { "Thinking" }
                    p { "{thinking}" }
                }
            }
            div { class: "{body_class}",
                if message.content.is_empty() && live {
                    p { class: "status", "{status}" }
                } else if !message.content.is_empty() {
                    {render_blocks(&blocks)}
                }
            }
            if let Some(search) = search {
                if !search.sources.is_empty() || search.error.is_some() {
                    details { class: "sources",
                        summary {
                            if search.sources.is_empty() { "Search" } else { "{search.sources.len()} sources" }
                        }
                        if let Some(error) = search.error.clone() {
                            p { class: "hint", "{error}" }
                        }
                        for (source_index, source) in search.sources.iter().enumerate() {
                            {
                                let title = if source.title.is_empty() { source.url.clone() } else { source.title.clone() };
                                let href = safe_link(&source.url);
                                rsx! {
                                    if href.is_empty() {
                                        p { key: "{source_index}", "{title}" }
                                    } else {
                                        a { key: "{source_index}", href: "{href}", target: "_blank", rel: "noopener noreferrer", "{title}" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if let Some(metrics) = metrics {
                if !metrics.is_empty() {
                    p { class: "meta", "{metrics}" }
                }
            }
            if show_actions {
                div { class: "actions",
                    if let Some(branch) = branch {
                        button {
                            class: "text",
                            r#type: "button",
                            onclick: move |_| cycle(state, branch.parent, -1),
                            "Prev"
                        }
                        span { class: "meta", "{branch.active + 1}/{branch.total}" }
                        button {
                            class: "text",
                            r#type: "button",
                            onclick: move |_| cycle(state, branch.parent, 1),
                            "Next"
                        }
                    }
                    button { class: "text", r#type: "button", onclick: move |_| regenerate(state, index), "Regenerate" }
                }
            }
        }
    }
}

#[component]
fn Composer(state: Signal<AppState>) -> Element {
    let mut state = state;
    let draft = state.read().ui.draft.clone();
    let streaming = state.read().store.is_streaming();
    let search_on = state.read().ui.search_chip;
    let code_on = state.read().ui.code_mode;
    let agent = state
        .read()
        .store
        .active()
        .map(|chat| chat.agent_mode)
        .unwrap_or(false);
    let model = state.read().store.settings.model.clone();
    let provider_name = provider(&state.read().store.settings.provider)
        .map(|item| item.name)
        .unwrap_or("Model");
    let can_send = !streaming && !draft.trim().is_empty();
    rsx! {
        div { class: "composer",
            div { class: "chips",
                button {
                    class: if agent { "chip on" } else { "chip" },
                    r#type: "button",
                    onclick: move |_| toggle_agent(state),
                    "Agent"
                }
                button {
                    class: if search_on { "chip on" } else { "chip" },
                    r#type: "button",
                    onclick: move |_| {
                        let next = !state.read().ui.search_chip;
                        state.write().ui.search_chip = next;
                    },
                    "Search"
                }
                button {
                    class: if code_on { "chip on" } else { "chip" },
                    r#type: "button",
                    onclick: move |_| {
                        let next = !state.read().ui.code_mode;
                        state.write().ui.code_mode = next;
                    },
                    "Code"
                }
                button {
                    class: "chip model",
                    r#type: "button",
                    onclick: move |_| state.write().ui.model_sheet = true,
                    "{provider_name} · {model}"
                }
            }
            div { class: "compose-row",
                textarea {
                    placeholder: "Message",
                    value: "{draft}",
                    rows: "2",
                    oninput: move |event| state.write().ui.draft = event.value(),
                }
                if streaming {
                    button { class: "send stop", r#type: "button", onclick: move |_| request_stop(state), "Stop" }
                } else {
                    button {
                        class: "send",
                        r#type: "button",
                        disabled: !can_send,
                        onclick: move |_| submit(state),
                        "Send"
                    }
                }
            }
        }
    }
}

#[component]
fn ModelSheet(state: Signal<AppState>) -> Element {
    let mut state = state;
    let settings_provider = state.read().store.settings.provider.clone();
    let settings_model = state.read().store.settings.model.clone();
    let effort = state.read().store.settings.effort.clone();
    let models = available_models(&state.read().store.settings);
    let effort_tiers = models
        .iter()
        .find(|model| model.id == settings_model)
        .map(|model| model.effort.clone())
        .unwrap_or_default();
    rsx! {
        div { class: "sheet-back",
            onclick: move |_| state.write().ui.model_sheet = false,
            div { class: "sheet",
                onclick: move |event| event.stop_propagation(),
                header { class: "sheet-head",
                    h2 { "Model" }
                    button { class: "primary", r#type: "button", onclick: move |_| state.write().ui.model_sheet = false, "Done" }
                }
                label { "Provider"
                    select {
                        value: "{settings_provider}",
                        onchange: move |event| {
                            state.write().store.set_provider(&event.value());
                            state.write().persist();
                        },
                        for item in all_providers() {
                            option { key: "{item.id}", value: "{item.id}", "{item.name}" }
                        }
                    }
                }
                label { "Model"
                    select {
                        value: "{settings_model}",
                        onchange: move |event| {
                            state.write().store.set_model(&event.value());
                            state.write().persist();
                        },
                        for model in models {
                            option { key: "{model.id}", value: "{model.id}", "{model.name}" }
                        }
                    }
                }
                if !effort_tiers.is_empty() {
                    p { class: "label", "Effort" }
                    div { class: "chips",
                        for tier in effort_tiers {
                            {
                                let selected = tier == effort;
                                let value = tier.clone();
                                rsx! {
                                    button {
                                        key: "{tier}",
                                        class: if selected { "chip on" } else { "chip" },
                                        r#type: "button",
                                        onclick: move |_| {
                                            state.write().store.set_effort(&value);
                                            state.write().persist();
                                        },
                                        "{tier}"
                                    }
                                }
                            }
                        }
                    }
                }
                p { class: "hint", "API keys stay in Settings on this device." }
            }
        }
    }
}

#[component]
fn SettingsPage(state: Signal<AppState>) -> Element {
    let mut state = state;
    let settings = state.read().store.settings.clone();
    let show_keys = state.read().ui.show_keys;
    let confirm_reset = state.read().ui.confirm_reset;
    let models = available_models(&settings);
    let effort_tiers = models
        .iter()
        .find(|model| model.id == settings.model)
        .map(|model| model.effort.clone())
        .unwrap_or_default();
    let hint = provider(&settings.provider)
        .map(|item| item.key_hint)
        .unwrap_or("API key");
    let key = settings.api_key_for(&settings.provider);
    let sandbox_key = settings.sandbox_key();
    let custom = settings.provider == "custom";
    let custom_id = state.read().ui.custom_model_id.clone();
    let custom_name = state.read().ui.custom_model_name.clone();
    let extra: Vec<(String, String)> = if custom {
        settings
            .custom_models
            .iter()
            .map(|model| (model.id.clone(), model.name.clone()))
            .collect()
    } else {
        settings
            .extra_models
            .iter()
            .filter(|model| model.provider == settings.provider)
            .map(|model| (model.id.clone(), model.name.clone()))
            .collect()
    };

    rsx! {
        header { class: "topbar",
            h1 { "Settings" }
            button {
                class: "text",
                r#type: "button",
                onclick: move |_| {
                    let next = !state.read().ui.show_keys;
                    state.write().ui.show_keys = next;
                },
                if show_keys { "Hide keys" } else { "Show keys" }
            }
        }
        div { class: "settings",
            h2 { "Connection" }
            Field { label: "API endpoint", value: settings.api_endpoint.clone(), on_value: move |value: String| { state.write().store.settings.api_endpoint = value; state.write().store.normalize(); state.write().persist(); } }
            p { class: "hint", "The app posts to this Worker's /api/chat and /sandbox routes. Leave it on the hosted Omni proxy unless you run your own." }
            Field { label: "App origin", value: settings.app_origin.clone(), on_value: move |value: String| { state.write().store.settings.app_origin = value.trim().to_string(); state.write().persist(); } }
            p { class: "hint", "Sent as the Origin header. A locked proxy (ALLOWED_ORIGINS) rejects requests that arrive without a matching origin. A phone has no browser origin of its own." }
            Field { label: "Proxy key", value: settings.proxy_key.clone(), password: !show_keys, on_value: move |value: String| { state.write().store.settings.proxy_key = value.trim().to_string(); state.write().persist(); } }

            h2 { "Model" }
            label { "Provider"
                select {
                    value: "{settings.provider}",
                    onchange: move |event| {
                        state.write().store.set_provider(&event.value());
                        state.write().persist();
                    },
                    for item in all_providers() {
                        option { key: "{item.id}", value: "{item.id}", "{item.name}" }
                    }
                }
            }
            label { "Model"
                select {
                    value: "{settings.model}",
                    onchange: move |event| {
                        state.write().store.set_model(&event.value());
                        state.write().persist();
                    },
                    for model in models {
                        option { key: "{model.id}", value: "{model.id}", "{model.name}" }
                    }
                }
            }
            if !effort_tiers.is_empty() {
                label { "Reasoning effort"
                    select {
                        value: "{settings.effort}",
                        onchange: move |event| {
                            state.write().store.set_effort(&event.value());
                            state.write().persist();
                        },
                        for tier in effort_tiers {
                            option { key: "{tier}", value: "{tier}", "{tier}" }
                        }
                    }
                }
            }
            Field { label: "API key", value: key, placeholder: hint.to_string(), password: !show_keys, on_value: move |value: String| { let provider_id = state.read().store.settings.provider.clone(); state.write().store.settings.set_api_key(&provider_id, value); state.write().persist(); } }
            if custom {
                Field { label: "Custom base URL", value: settings.custom_base_url.clone(), on_value: move |value: String| { state.write().store.settings.custom_base_url = value.trim().to_string(); state.write().persist(); } }
            }
            p { class: "label", if custom { "Custom models" } else { "Extra models for this provider" } }
            for (id, name) in extra {
                {
                    let remove_id = id.clone();
                    rsx! {
                        div { class: "model-line", key: "{id}",
                            span { "{name}" }
                            button {
                                class: "text",
                                r#type: "button",
                                onclick: move |_| remove_model(state, &remove_id),
                                "Remove"
                            }
                        }
                    }
                }
            }
            div { class: "compose-row",
                input { class: "field", placeholder: "model id", value: "{custom_id}", oninput: move |event| state.write().ui.custom_model_id = event.value() }
                input { class: "field", placeholder: "name", value: "{custom_name}", oninput: move |event| state.write().ui.custom_model_name = event.value() }
                button { class: "primary", r#type: "button", onclick: move |_| add_model(state), "Add" }
            }

            h2 { "Reply" }
            label { "Custom instructions"
                textarea {
                    rows: "4",
                    value: "{settings.system_prompt}",
                    oninput: move |event| {
                        state.write().store.settings.system_prompt = event.value();
                        state.write().persist();
                    },
                }
            }
            label { class: "check",
                input {
                    r#type: "checkbox",
                    checked: settings.thinking,
                    onchange: move |event| {
                        state.write().store.settings.thinking = event.checked();
                        state.write().persist();
                    },
                }
                "Show thinking"
            }
            label { "Temperature {settings.temperature:.1}"
                input {
                    r#type: "range",
                    min: "0",
                    max: "2",
                    step: "0.1",
                    value: "{settings.temperature}",
                    oninput: move |event| {
                        if let Ok(value) = event.value().parse::<f64>() {
                            state.write().store.settings.temperature = value;
                            state.write().store.normalize();
                            state.write().persist();
                        }
                    },
                }
            }
            Field { label: "Max tokens", value: settings.max_tokens.to_string(), on_value: move |value: String| { if let Ok(parsed) = value.trim().parse::<u32>() { state.write().store.settings.max_tokens = parsed; state.write().store.normalize(); state.write().persist(); } } }

            h2 { "Web search" }
            p { class: "hint", "Tavily is bring-your-own-key. With no key, search drops back to a normal answer instead of failing the chat. Anthropic, Google, and OpenRouter can use their own search and do not receive the Tavily key." }
            div { class: "chips",
                for mode in ["auto", "on", "off"] {
                    button {
                        key: "search-{mode}",
                        class: if settings.search_mode == mode { "chip on" } else { "chip" },
                        r#type: "button",
                        onclick: move |_| {
                            state.write().store.settings.search_mode = mode.to_string();
                            state.write().store.normalize();
                            state.write().persist();
                        },
                        "{mode}"
                    }
                }
            }
            div { class: "chips",
                for backend in ["auto", "tavily"] {
                    button {
                        key: "backend-{backend}",
                        class: if settings.search_backend == backend { "chip on" } else { "chip" },
                        r#type: "button",
                        onclick: move |_| {
                            state.write().store.settings.search_backend = backend.to_string();
                            state.write().store.normalize();
                            state.write().persist();
                        },
                        "{backend}"
                    }
                }
            }
            div { class: "chips",
                for depth in ["auto", "basic", "advanced"] {
                    button {
                        key: "depth-{depth}",
                        class: if settings.search_depth == depth { "chip on" } else { "chip" },
                        r#type: "button",
                        onclick: move |_| {
                            state.write().store.settings.search_depth = depth.to_string();
                            state.write().store.normalize();
                            state.write().persist();
                        },
                        "{depth}"
                    }
                }
            }
            Field { label: "Tavily API key", value: settings.tavily_key.clone(), password: !show_keys, on_value: move |value: String| { state.write().store.settings.tavily_key = value.trim().to_string(); state.write().persist(); } }

            h2 { "Code sandbox" }
            p { class: "hint", "Agent mode runs Python and shell in an E2B or Novita sandbox through the Worker. The sandbox key never goes into the app binary." }
            div { class: "chips",
                for sandbox in ["e2b", "novita"] {
                    button {
                        key: "{sandbox}",
                        class: if settings.sandbox_provider == sandbox { "chip on" } else { "chip" },
                        r#type: "button",
                        onclick: move |_| {
                            state.write().store.settings.sandbox_provider = sandbox.to_string();
                            state.write().store.normalize();
                            state.write().persist();
                        },
                        "{sandbox}"
                    }
                }
            }
            Field { label: "Sandbox API key", value: sandbox_key, password: !show_keys, on_value: move |value: String| { state.write().store.settings.set_sandbox_key(value); state.write().persist(); } }
            Field { label: "Max agent steps", value: settings.agent_max_steps.to_string(), on_value: move |value: String| { if let Ok(parsed) = value.trim().parse::<u32>() { state.write().store.settings.agent_max_steps = parsed; state.write().store.normalize(); state.write().persist(); } } }
            Field { label: "Sandbox timeout (seconds)", value: settings.agent_sandbox_timeout.to_string(), on_value: move |value: String| { if let Ok(parsed) = value.trim().parse::<u32>() { state.write().store.settings.agent_sandbox_timeout = parsed; state.write().store.normalize(); state.write().persist(); } } }

            h2 { "On this device" }
            p { class: "hint", "Chats and settings are JSON files in the app sandbox. Keys are not written into chats.json." }
            if confirm_reset {
                p { "Replace settings with the defaults? Chats stay." }
                div { class: "top-actions",
                    button {
                        class: "danger",
                        r#type: "button",
                        onclick: move |_| {
                            state.write().store.settings = Settings::default();
                            state.write().store.normalize();
                            state.write().ui.confirm_reset = false;
                            state.write().persist();
                        },
                        "Reset settings"
                    }
                    button { class: "text", r#type: "button", onclick: move |_| state.write().ui.confirm_reset = false, "Cancel" }
                }
            } else {
                button { class: "text", r#type: "button", onclick: move |_| state.write().ui.confirm_reset = true, "Reset settings" }
            }
        }
    }
}

#[component]
fn Field(
    label: &'static str,
    value: String,
    #[props(default)] placeholder: String,
    #[props(default)] password: bool,
    on_value: EventHandler<String>,
) -> Element {
    let kind = if password { "password" } else { "text" };
    rsx! {
        label { "{label}"
            input {
                class: "field",
                r#type: "{kind}",
                placeholder: "{placeholder}",
                value: "{value}",
                autocomplete: "off",
                spellcheck: false,
                oninput: move |event| on_value.call(event.value()),
            }
        }
    }
}

fn render_blocks(blocks: &[Block]) -> Element {
    rsx! {
        for (index, block) in blocks.iter().enumerate() {
            div { key: "{index}", class: "md",
                {render_block(block)}
            }
        }
    }
}

fn render_block(block: &Block) -> Element {
    match block {
        Block::Paragraph(inlines) => rsx! { p { {render_inlines(inlines)} } },
        Block::Heading(level, inlines) => match level {
            1 => rsx! { h1 { {render_inlines(inlines)} } },
            2 => rsx! { h2 { {render_inlines(inlines)} } },
            _ => rsx! { h3 { {render_inlines(inlines)} } },
        },
        Block::Code { lang, code } => {
            let copy = code.clone();
            let label = if lang.is_empty() {
                "code".to_string()
            } else {
                lang.clone()
            };
            rsx! {
                div { class: "codeblock",
                    div { class: "codehead",
                        span { "{label}" }
                        button { class: "text", r#type: "button", onclick: move |_| copy_text(copy.clone()), "Copy" }
                    }
                    pre { code { "{code}" } }
                }
            }
        }
        Block::List { ordered, items } => {
            if *ordered {
                rsx! {
                    ol {
                        for (index, item) in items.iter().enumerate() {
                            li { key: "{index}", {render_blocks(item)} }
                        }
                    }
                }
            } else {
                rsx! {
                    ul {
                        for (index, item) in items.iter().enumerate() {
                            li { key: "{index}", {render_blocks(item)} }
                        }
                    }
                }
            }
        }
        Block::Quote(blocks) => rsx! { blockquote { {render_blocks(blocks)} } },
        Block::Table { header, rows } => rsx! {
            table {
                if !header.is_empty() {
                    thead {
                        tr {
                            for (index, cell) in header.iter().enumerate() {
                                th { key: "{index}", "{cell}" }
                            }
                        }
                    }
                }
                tbody {
                    for (row_index, row) in rows.iter().enumerate() {
                        tr { key: "{row_index}",
                            for (cell_index, cell) in row.iter().enumerate() {
                                td { key: "{cell_index}", "{cell}" }
                            }
                        }
                    }
                }
            }
        },
        Block::Rule => rsx! { hr {} },
    }
}

fn render_inlines(inlines: &[Inline]) -> Element {
    rsx! {
        for (index, inline) in inlines.iter().enumerate() {
            span { key: "{index}", {render_inline(inline)} }
        }
    }
}

fn render_inline(inline: &Inline) -> Element {
    match inline {
        Inline::Text(text) => rsx! { "{text}" },
        Inline::Em(items) => rsx! { em { {render_inlines(items)} } },
        Inline::Strong(items) => rsx! { strong { {render_inlines(items)} } },
        Inline::Strike(items) => rsx! { s { {render_inlines(items)} } },
        Inline::Code(code) => rsx! { code { class: "inline", "{code}" } },
        Inline::Break => rsx! { br {} },
        Inline::Link { text, href } => {
            if href.is_empty() {
                render_inlines(text)
            } else {
                let href = href.clone();
                rsx! {
                    a { href: "{href}", target: "_blank", rel: "noopener noreferrer", {render_inlines(text)} }
                }
            }
        }
        Inline::Image { alt, src } => {
            if src.is_empty() {
                rsx! { "{alt}" }
            } else {
                rsx! { img { alt: "{alt}", src: "{src}" } }
            }
        }
    }
}

fn metric_line(metrics: &omni_core::TurnMetrics) -> String {
    let mut parts = Vec::new();
    if let Some(value) = metrics.input_tokens {
        parts.push(format!("in {value}"));
    }
    if let Some(value) = metrics.output_tokens {
        parts.push(format!("out {value}"));
    }
    if let Some(reason) = &metrics.stop_reason {
        if !reason.is_empty() {
            parts.push(reason.clone());
        }
    }
    parts.join(" · ")
}

fn safe_link(url: &str) -> String {
    let lower = url.trim().to_ascii_lowercase();
    if lower.starts_with("https://") || lower.starts_with("http://") {
        url.trim().to_string()
    } else {
        String::new()
    }
}

fn scroll_thread() {
    spawn(async move {
        let _ = document::eval(
            "(() => { const el = document.getElementById('thread'); if (el) el.scrollTop = el.scrollHeight; })()",
        )
        .await;
    });
}

fn copy_text(text: String) {
    let encoded = serde_json::to_string(&text).unwrap_or_else(|_| "\"\"".into());
    spawn(async move {
        let _ = document::eval(&format!(
            "navigator.clipboard && navigator.clipboard.writeText({encoded})"
        ))
        .await;
    });
}

fn submit(mut state: Signal<AppState>) {
    let draft = state.read().ui.draft.clone();
    let opts = SendOpts {
        search_override: None,
        search_chip: state.read().ui.search_chip,
        code_mode: state.read().ui.code_mode,
    };
    let prepared = state.write().store.begin_send(&draft, opts);
    match prepared {
        Ok(prepared) => {
            if let Some(notice) = prepared.notice {
                state.write().ui.toast = notice;
            }
            state.write().ui.draft.clear();
            state.write().ui.search_chip = false;
            state.write().ui.code_mode = false;
            state.write().ui.follow = true;
            state.write().ui.tab = Tab::Thread;
            state.write().persist();
            spawn_generation(state);
        }
        Err(err) => state.write().ui.toast = err,
    }
}

fn regenerate(mut state: Signal<AppState>, index: usize) {
    if state.read().store.is_streaming() {
        state.write().ui.toast = "Wait for the current reply to finish.".into();
        return;
    }
    let regenerated = state.write().store.prepare_regenerate(index);
    let text = match regenerated {
        Ok(text) => text,
        Err(err) => {
            state.write().ui.toast = err;
            return;
        }
    };
    let prepared = state.write().store.begin_send(&text, SendOpts::default());
    match prepared {
        Ok(prepared) => {
            if let Some(notice) = prepared.notice {
                state.write().ui.toast = notice;
            }
            state.write().ui.follow = true;
            state.write().persist();
            spawn_generation(state);
        }
        Err(err) => state.write().ui.toast = err,
    }
}

fn cycle(mut state: Signal<AppState>, parent: usize, direction: i32) {
    let cycled = state.write().store.cycle_branch(parent, direction);
    if let Err(err) = cycled {
        state.write().ui.toast = err;
        return;
    }
    state.write().persist();
}

fn new_chat(mut state: Signal<AppState>) {
    if state.read().store.is_streaming() {
        state.write().ui.toast = "Wait for the current reply to finish.".into();
        return;
    }
    state.write().store.new_chat();
    state.write().ui.tab = Tab::Thread;
    state.write().ui.renaming = false;
    state.write().ui.follow = true;
    state.write().persist();
}

fn open_chat(mut state: Signal<AppState>, id: &str) {
    state.write().store.open_chat(id);
    state.write().ui.tab = Tab::Thread;
    state.write().ui.renaming = false;
    state.write().ui.follow = true;
    state.write().persist();
}

fn confirm_delete(mut state: Signal<AppState>, id: &str) {
    if state.read().store.streaming_id() == Some(id) {
        request_stop(state);
    }
    state.write().store.delete_chat(id);
    state.write().ui.pending_delete = None;
    state.write().persist();
}

fn toggle_incognito(mut state: Signal<AppState>) {
    if state.read().store.is_streaming() {
        state.write().ui.toast = "Wait for the current reply to finish.".into();
        return;
    }
    state.write().store.toggle_incognito();
    state.write().ui.tab = Tab::Thread;
    state.write().persist();
}

fn toggle_agent(mut state: Signal<AppState>) {
    if state.read().store.active().is_none() {
        state.write().store.new_chat();
    }
    let toggled = state.write().store.toggle_agent();
    match toggled {
        Ok(_) => state.write().persist(),
        Err(err) => state.write().ui.toast = err,
    }
}

fn start_rename(mut state: Signal<AppState>) {
    let title = state.read().store.active().map(|chat| chat.title.clone());
    let Some(title) = title else {
        state.write().ui.toast = "Start a chat first.".into();
        return;
    };
    state.write().ui.rename_draft = title;
    state.write().ui.renaming = true;
}

fn apply_rename(mut state: Signal<AppState>, id: &str) {
    let title = state.read().ui.rename_draft.clone();
    let renamed = state.write().store.rename_chat(id, &title);
    if let Err(err) = renamed {
        state.write().ui.toast = err;
        return;
    }
    state.write().ui.renaming = false;
    state.write().persist();
}

fn add_model(mut state: Signal<AppState>) {
    let id = state.read().ui.custom_model_id.trim().to_string();
    let name = state.read().ui.custom_model_name.trim().to_string();
    if id.is_empty() {
        state.write().ui.toast = "The model needs an id.".into();
        return;
    }
    let name = if name.is_empty() { id.clone() } else { name };
    let provider_id = state.read().store.settings.provider.clone();
    if provider_id == "custom" {
        state
            .write()
            .store
            .settings
            .custom_models
            .push(CustomModel {
                id: id.clone(),
                name,
                ctx: 8192,
            });
    } else {
        state.write().store.settings.extra_models.push(ExtraModel {
            provider: provider_id,
            id: id.clone(),
            name,
            ctx: 8192,
        });
    }
    state.write().store.set_model(&id);
    state.write().ui.custom_model_id.clear();
    state.write().ui.custom_model_name.clear();
    state.write().persist();
}

fn remove_model(mut state: Signal<AppState>, id: &str) {
    let provider_id = state.read().store.settings.provider.clone();
    if provider_id == "custom" {
        state
            .write()
            .store
            .settings
            .custom_models
            .retain(|model| model.id != id);
    } else {
        state
            .write()
            .store
            .settings
            .extra_models
            .retain(|model| !(model.provider == provider_id && model.id == id));
    }
    state.write().store.normalize();
    state.write().persist();
}

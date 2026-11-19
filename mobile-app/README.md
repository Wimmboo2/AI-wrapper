# Omni for Android and iOS

Mobile client for the existing Omni chat app. It talks to the same Cloudflare Worker. The web app and `worker.js` are unchanged.

## Stack

[Dioxus 0.7](https://dioxuslabs.com) with the `mobile` feature. One Rust UI is packaged for Android and iOS with `dx serve --android` and `dx serve --ios`. The screen is drawn by the platform web view; the chat loop, streaming parser, and storage are Rust.

Tauri mobile would have put the interface back in HTML. Dioxus keeps the interface and the client in the same language, and 0.7.10 is the current stable line that builds on Rust 1.83. The 0.8 releases are still alpha and need a newer compiler, so this app stays on 0.7.

This is a phone app. There is no desktop feature. `cargo run` on a computer prints the phone commands and exits. It does not open a window.

```
mobile-app/
  Cargo.toml          workspace
  core/               omni-core: chats, streaming, search, sandbox, storage
  app/                omni-mobile: Dioxus UI and HTTP
    Dioxus.toml
    src/main.rs       launch, only on Android and iOS
    src/state.rs      tabs, draft, settings screen
    src/session.rs    one streaming reply, stop, tool loop
    src/view.rs       layout
    assets/main.css
```

`cargo test -p omni-core` covers the chat loop without a device. `cargo check -p omni-mobile` typechecks the UI without the mobile renderer.

## Run it on a phone

You need Rust 1.85 or newer (this tree was checked with stable 1.100) and the Dioxus CLI that matches this app:

```sh
cargo install dioxus-cli --version 0.7.10 --locked
```

Android, from `mobile-app/app` after the SDK and NDK are installed:

```sh
rustup target add aarch64-linux-android
dx serve --android
```

`ANDROID_HOME` and `NDK_HOME` have to point at a working SDK. `minSdk` is 24. Cleartext HTTP is allowed so a local `http://` Worker can be used. Prefer `https://` for anything that is not a machine you control.

iOS needs a Mac with Xcode:

```sh
rustup target add aarch64-apple-ios
dx serve --ios
```

iOS App Transport Security blocks plain HTTP. Point the endpoint at an `https://` Worker there.

## How it talks to the Worker

The default endpoint is `https://omni-api.wimmboo.workers.dev`. Chats are `POST {endpoint}/api/chat` with `Accept: application/x-ndjson`. Sandbox calls are `POST {endpoint}/sandbox/create`, `/keepalive`, `/exec`, and `/kill`.

API keys are typed on the device and stored in `settings.json`. They are sent on the request, the same way the web app does. Nothing in the binary is a provider key.

If the Worker is locked with `OMNI_PROXY_KEY`, put that key in Settings. It is sent as `X-Omni-Proxy-Key` and in the JSON body. If the Worker is locked with `ALLOWED_ORIGINS`, set **App origin** to an origin on that list. A native app has no browser origin, and the Worker answers 403 when the header is missing.

A missing Tavily key does not fail the chat. Search is turned off for that request, and a notice says so. Anthropic, Google, and OpenRouter keep their own search and never receive the Tavily key. Agent mode does not attach the Tavily key either, because the Worker skips search when tools are present.

## On the device

Chats and settings are two JSON files, `chats.json` and `settings.json`.

- `OMNI_DATA_DIR`, if you set it
- otherwise, on Android and iOS, `$HOME/omni-chat` when `HOME` is set, or `omni-data` in the working directory
- otherwise the process working directory plus `omni-data`

Writes are atomic and the files are mode `0600` where the OS allows it. Incognito chats are not written. The sandbox id and token stay in memory for the run and are dropped when the reply ends. Debug output redacts keys.

## What you can do

- Chat list, search, pin, rename, delete, new chat
- Streaming replies, with a Stop button
- Provider, model, effort, and per-provider API keys
- Markdown, including fenced code
- Regenerate and branch switching
- Web search (Tavily, plus the providers that search natively)
- Agent mode: `run_python`, `run_bash`, `write_file`, `read_file` on E2B or Novita
- `/search` and `/web` in the composer
- Code and Search chips on a single message
- Local persistence

## What this pass does not include

Attachments and vision uploads, voice and speech, the rest of the slash menu, settings import and export, editing an earlier message, and the full metrics sheet. Token counts and the stop reason are kept on the message. Enter in the composer inserts a newline; Send is the button.

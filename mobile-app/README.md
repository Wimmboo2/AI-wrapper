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

### Android launcher icon (required workaround)

**`[bundle] icon` in `Dioxus.toml` is ignored on Android** ([dioxus#3685](https://github.com/DioxusLabs/dioxus/issues/3685)). A normal `dx bundle` or full recompile still ships the green Android robot unless you patch the generated Gradle project.

Committed mipmaps live in `app/assets/android-res/` (generated from `app/assets/icon.png`). After `dx bundle --platform android` (debug or release):

```sh
# from mobile-app/
chmod +x scripts/*.sh
./scripts/patch-android-launcher.sh debug   # or: release
cd app/target/dx/*/debug/android/app       # adjust * to your app folder name
./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`. Before uploading, unzip the APK and confirm `res/mipmap-xxxhdpi-v4/ic_launcher.webp` (or `.png`) is the dark square with the gold star—not the robot foreground paths in `ic_launcher_foreground.xml`.

To refresh mipmaps after changing `icon.png`:

```sh
./scripts/gen-android-launcher-icons.sh
```

Building on **Google Colab**: see [`COLAB_ANDROID.md`](COLAB_ANDROID.md) — use `%%bash` cells, not `%cd foo && bar` (that fails in Jupyter).

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
- `/code`, `/search`, `/web`, `/explain`, `/summarize`, `/refactor`, and `/translate`
- Code and Search chips, plus a prefix chip for the explainer, summary, refactor, and translate commands
- Image and text attachments on models that accept them
- Editing an earlier message, which keeps the old thread as a branch you can switch back to
- A metrics sheet: tokens, generation and prefill speed, TTFT, context, stop reason
- Settings export and import, and a separate chat export and import
- Local persistence

Enter in the composer inserts a newline. Send is the button.

## How to verify

From `mobile-app`:

```bash
cargo test -p omni-core
cargo check -p omni-mobile
```

On a phone (`dx serve --android` or `dx serve --ios`):

- Attach an image, send it, and confirm the picture stays in the thread. On a model without vision the app refuses the image.
- Type `/` and run Explain, Summarize, Refactor, Translate, Code, and Search. `/search some question` sends immediately.
- Export settings, confirm the toast says the file includes API keys, and import it back. Export chats and import those on a second check. The chat file does not contain the API key.
- Edit an earlier message, send the new text, then use Prev to return to the old thread and Next to come back. Regenerate still switches reply branches.
- Open the metrics line under the thread. It lists tokens, t/s, TTFT, and context without covering the conversation.

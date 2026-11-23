# Omni Mobile (Android)

Native Kotlin + Jetpack Compose Android client for Omni (`dev.wimmboo.omni`, version `1.0`), built for 1:1 visual, UX, and protocol parity with the Omni web app while omitting Read Aloud / TTS.

## Architecture

- **UI**: 100% Jetpack Compose with custom dark & incognito color palettes ported directly from `index.html`, bundled `Inter`, `Instrument Serif`, and `JetBrains Mono` fonts, adaptive launcher icon from `icon.svg`, native Markdown + Prism Tomorrow syntax highlighting, collapsible reasoning & agent tool-call trace cards, phone bottom sheets for model/effort/metrics, and a sandboxed `WebView` strictly for the Live Code Preview modal.
- **Networking**: Ktor Client (`OkHttp` engine) + `kotlinx.serialization` streaming NDJSON/SSE over Kotlin `Flow` through the Cloudflare Worker (`https://omni-api.wimmboo.workers.dev` by default). Sends `Origin` (`https://claude-like-ai-wrapper.wimmboo.workers.dev` by default, editable in Settings) for Workers locked with `ALLOWED_ORIGINS`, plus `X-Omni-Proxy-Key` when configured.
- **Storage & Security**:
  - Conversations persist in Room (`omni_chats.db`); incognito conversations stay strictly in memory and never touch disk.
  - BYOK secrets (provider API keys, worker access key, Tavily key, E2B/Novita sandbox keys) are encrypted at rest using Android Keystore (`AES/GCM/NoPadding`) inside Jetpack DataStore.
  - Chat and settings JSON export/import via Android Storage Access Framework use the exact same JSON schemas as the web app.

## Build & Test

```bash
cd mobile-app
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

The assembled debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

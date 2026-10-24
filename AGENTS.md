# AGENTS.md

Guidance for AI agents working in this repo. Read this before editing anything.

**Line numbers were last resynced after the search/BYOK refresh.** They will
drift. Re-grep before you rely on one.

---

## Rules

1. **Never install** packages, dependencies, SDKs, CLIs, browser extensions, or
   system software without asking first and receiving explicit approval.
2. **If you're unsure about requirements, ask** instead of guessing.
3. **If documentation or behavior is uncertain, check official documentation or
   reputable sources** instead of inventing an answer.
4. **Verify code by reading and reasoning.** Do not run it, start localhost,
   build, test, or execute commands unless explicitly told to.
   - Exception: read-only git is pre-approved (`status`, `log`, `diff`, `fetch`,
     `show`, `branch --list`).
   - Still ask before anything that mutates git state: `commit`, `push`,
     `checkout`, `reset`, `clean`, `stash`.
5. **Before large or destructive changes** (many files, deletions, refactors,
   config changes), explain the plan and wait for approval.
6. **Modify only what is necessary.** Do not rewrite working code unless asked
   for a refactor.
7. **Never access, modify, or delete files outside this project** unless
   explicitly asked.
8. **Tell the user exactly what you changed** after every completed task —
   file paths, plus what changed in each.

---

## Project

Omni is a single-page AI chat client for 12 providers, backed by a self-hosted
Cloudflare Worker that acts as a stateless proxy. Vanilla JS. No framework, no
bundler, no `package.json`.

**Hard constraint: no build step.** `index.html` is served as-is by any static
host. Do not introduce npm, a bundler, a transpiler, or JSX. The README's
"No build step required" is a feature, not an oversight.

### Files

| File | Purpose |
| --- | --- |
| `index.html` | The entire frontend: CSS, markup, and JS. 8,806 lines / ~345 KB |
| `worker.js` | Cloudflare Worker. Provider passthrough + web search + sandbox proxy. Holds zero secrets |
| `sw.js` | Service worker. App-shell cache, network-first shell, SWR assets |
| `manifest.json` | PWA manifest |
| `icon.svg` | App icon |
| `README.md` | User-facing docs: setup, deploy, architecture notes |

---

## `index.html` map

### Shell

| Lines | What |
| --- | --- |
| 1–2 | `<!DOCTYPE html>`, `<html lang="en">` |
| 3–19 | `<head>`: meta, title, favicon, PWA meta, CDN deps (Tailwind browser build, Prism + theme + autoloader) |
| 20–3225 | `<style>` |
| 3227–3728 | `<body>` markup |
| 3730–8804 | `<script>` — one IIFE |
| 8805–8806 | `</body></html>` |

### CSS (`<style>`, 20–3225)

Hand-written CSS with design tokens. Tailwind is loaded from CDN but the styling
is overwhelmingly custom.

| Lines | Section |
| --- | --- |
| 20–22 | `<style>` open, Google Fonts `@import` |
| 23–101 | **`:root` design tokens** (colors, radii, shadows) + base/reset |
| 102–482 | Sidebar |
| 483–535 | Main / layout shell |
| 536–688 | Messages container |
| 689–754 | Message rows |
| 755–817 | Code block container & header |
| 818–832 | Code diff line highlighting |
| 833–839 | Code preview modal |
| 840–958 | Code mode pill button |
| 959–1040 | Thinking indicator (Claude-style) |
| 1041–1077 | Message actions |
| 1078–1153 | Regenerate dropdown |
| 1154–1196 | Inline citation chips |
| 1197–1312 | Expandable sources footer |
| 1313–1414 | Agent mode tool-call steps |
| 1415–1536 | Composer |
| 1537–1968 | Slash menu popup |
| 1969–1988 | Composer effort selector |
| 1989–2014 | Segmented control |
| 2015–2202 | Effort selector popover |
| 2203–2447 | Metrics (sheet furniture 2270, surface gap 2371) |
| 2448–2993 | Modal |
| 2994–3126 | `@media (max-width: 768px)` — mobile pass 1 |
| 3127–3215 | `@media (max-width: 768px)` — mobile pass 2 (bottom sheets) |
| 3216–3221 | `@media (hover: none)` |
| 3222–3224 | `@media (min-width: 769px)` |
| 3225 | `</style>` |

Keyframes: `streamPulse` 417, `softIn` 606, `msgIn` 696, `thinkPulse` 981,
`blink` 1413, `micPulse` 1614, `slideUpSheet` 3147.

### HTML (`<body>`, 3227–3728)

| Lines | What |
| --- | --- |
| 3228–3293 | `.app` → `<aside id="sidebar">`: brand, New chat/Settings nav, chat search, `#chatList`, user chip |
| 3295–3298 | Backdrops: sidebar, model picker, effort menu, metrics |
| 3300–3472 | `<main class="main">` |
| 3301–3334 | Topbar: sidebar toggle, incognito banner, incognito button |
| 3336–3338 | `#messagesWrap` → `#messages` |
| 3340–3343 | Jump-to-latest button |
| 3345–3471 | `#composerArea` |
| 3346–3370 | Metrics row + collapsible `#metricsPanel` / `#metricsGrid` |
| 3372–3470 | `#composerShell` → `#composer`: edit banner, slash menu, textarea, pending files, attach, agent toggle, model picker, effort control, mic, send, add-model drawer; `#composerNote` |
| 3475–3702 | `#settingsModal` (see group list below) |
| 3704–3721 | `#codePreviewModal` — sandboxed iframe |
| 3723–3728 | `#toast` |

**Settings modal groups** (`#settingsModal`, modal body 3485–3696):

| Line | Group |
| --- | --- |
| 3486 | API Endpoint |
| 3493 | Provider (hidden `<select>` 3496 is the source of truth; custom picker 3510) |
| 3519 | API Key |
| 3533 | Custom provider section (Base URL 3535, Custom Models 3541) |
| 3551 | Custom Instructions |
| 3557 | Agent sandbox provider (segmented) |
| 3566 | Sandbox API key |
| 3580 | Agent step limit |
| 3589 | Temperature |
| 3598 | Max tokens |
| 3607 | Thinking toggle |
| 3620 | Reasoning effort (hidden unless model supports tiers) |
| 3647 | Web search mode (auto/on/off) |
| 3659 | Tavily API key (BYOK, password field + status) |
| 3674 | Search backend (auto/Tavily) |
| 3685 | Search depth (auto/basic/advanced) |

Footer: 3697–3700. `#resetSettingsBtn`, `#saveSettingsBtn`.

### JavaScript (`<script>`, 3730–8804)

One IIFE, `'use strict'`, ~190 top-level functions, ~5,000 lines.

| Lines | What |
| --- | --- |
| 3730–3733 | `<script>` open, IIFE open, `'use strict'` |
| 3735–3789 | **IndexedDB layer**: `openDB`, `dbPutChat`, `dbDeleteChat`, `dbGetAllChats` |
| 3791–3977 | **`PROVIDERS`** — 12 provider configs and their model catalogs (biggest data blob) |
| 3979–3986 | `DEFAULT_TEMPLATES` — slash-menu prompt templates |
| 3988–4015 | **`DEFAULT_SETTINGS`** — every persisted setting key |
| 4017–4023 | `PROMPTS` |
| 4025–4053 | `ICONS` — inline SVG strings |
| 4055–4097 | **Module state**: `settings`, `chats`, `activeChatId`, `streamControllers`, `incognito`, `sidebarCollapsed` + cached DOM refs (`$()`) |
| 4099–4128 | `loadJSON`, `mergeSettings`, `saveSettings`, `uid` |
| 4130–4177 | `exportChats` / `importChats` |
| 4179–4206 | Provider + model resolution: `getProviderConfig`, `getAvailableModels`, `getActiveModelConfig` |
| 4208–4394 | **Effort tiers**: build/render/position/portal the reasoning-effort menu |
| 4396–4461 | Context window, `getActiveChat`, chat CRUD (`createChat`, `deleteChat`, `togglePinChat`), title derivation |
| 4463–4529 | Composer note, incognito enter/exit/toggle, `startNewChat` |
| 4531–4943 | **Model picker + provider picker** + custom-model CRUD |
| 4945–4987 | `escapeHtml`, `renderMarkdown` (fenced code blocks, preview button) |
| 4989–5103 | `renderAll`, `renderChatList` (sidebar search, pin, delete) |
| 5105–5165 | Chat rename, empty state |
| 5167–5209 | `renderMessages`, thinking animation |
| 5211–5354 | Message actions bar, regenerate dropdowns, thinking detail blocks |
| 5356–5434 | **Branching**: `snapshotBranch`, `restoreBranch`, `regenerateMessage`, `cycleBranch` |
| 5436–5477 | Attachment marker strip/restore |
| 5479–5612 | **Message editing**: banner, begin/cancel/apply |
| 5614–5737 | `buildToolSteps`, `buildMessageEl` — assembles one message DOM node |
| 5739–5852 | Citation chips + expandable sources footer |
| 5854–6009 | Web-search sources rendering, `updateLastAssistantMessage` (streaming writes) |
| 6012–6030 | `finishStreamingUI` |
| 6032–6217 | Metric formatters (`fmtInt`, `formatMs`, `formatRate`, `metricCell`, `pickTier`) + token totals |
| 6219–6405 | **`updateMetrics`** — builds the metrics grid |
| 6407–6432 | Composer placement, sidebar collapse/expand state |
| 6434–6525 | Settings UI: `renderCustomModels`, `syncSettingsUI`, provider placeholder/URL info, Tavily key + backend/depth controls |
| 6527–6587 | `openSettings`, `closeSettings`, `resetSettings` |
| 6589–6708 | **Agent mode**: `SANDBOX_PROVIDERS`, `AGENT_TOOLS`, endpoint/key helpers, `ensureSandbox` |
| 6710–6766 | Sandbox lifecycle: keepalive, `killSandbox`, `sandboxExec`, `formatToolResult` |
| 6768–6820 | `executeToolCall` — dispatch per tool |
| 6822–7082 | **Think-tag splitter** — streaming reasoning extraction (harmony channels) |
| 7084–7133 | `toApiMessages`, `buildUserMessagePayload` — message → provider payload |
| 7135–7783 | **`sendMessage`** — ~650 lines. Streaming, SSE parsing, per-provider shapes, web search, agent loop, branches |
| 7785–7844 | `stopGeneration`, `autoResize`, `updateSendBtn`, scroll tracking |
| 7846–7872 | `showToast` / `dismissToast` |
| 7874–7904 | Inline event listeners: sidebar, settings modal, effort triggers |
| 7906–7980 | Thinking toggles, slider fills |
| 7982–8074 | Sandbox provider control, metrics sheet open/close |
| 8076–8174 | Search mode + Tavily key/backend/depth controls, pending files, Prism highlight, code download |
| 8176–8294 | Code preview, code mode, agent-mode button |
| 8296–8399 | Text-to-speech + speech recognition |
| 8401–8725 | Prompt templates, slash menu, composer active tags |
| 8727–8762 | `migrateChatBranches` |
| 8764–8800 | `init()` — IndexedDB load, legacy localStorage migration, first render |
| 8802–8804 | `init()` call, `})();`, `</script>` |

---

## Where to change things

| Task | Touch these |
| --- | --- |
| Add / edit a provider | `PROVIDERS` 3791, hidden `<select>` 3496, `populateProviderPicker` 4803, legacy remap in `mergeSettings` 4104 |
| Edit a model catalog | `PROVIDERS[<id>].models` inside 3791–3977 |
| Add a setting | `DEFAULT_SETTINGS` 3988 → new `.setting-group` in `#settingsModal` → `syncSettingsUI` 6470 → `mergeSettings` 4104 for back-compat |
| Change theme colors | `:root` 23 |
| Add an agent tool | `AGENT_TOOLS` 6622 + `executeToolCall` 6768 (+ matching handler in `worker.js`) |
| Change streaming / SSE | `sendMessage` 7135 |
| Add a slash command | `DEFAULT_TEMPLATES` 3979 + `applySlashTemplate` 8489 |
| Change message rendering | `buildMessageEl` 5663, `renderMarkdown` 4945 |
| Change web search | frontend key/backend/depth at 3647–3695 + request payload 7264–7290; Worker search block 1241–1500 |
| Add a backend route | `worker.js` only — the frontend never talks to providers directly |

---

## Architecture invariants

- **Everything lives inside one IIFE.** Nothing is exported to `window`. Do not
  add globals; do not assume anything is reachable from the console.
- **State is closed over, not passed.** `settings`, `chats`, `activeChatId`,
  `streamControllers`, `incognito`, `sidebarCollapsed`, `codeMode`, and the
  cached DOM refs are shared by direct mutation. Functions call each other
  freely; there is no dependency graph to preserve beyond source order for
  top-level `var` initialization.
- **DOM refs are cached once** at 4083–4097 via `$()`. New elements need a
  cached ref there or a local `$()` call.
- **Rendering is imperative.** `document.createElement` everywhere; no template
  literals for message bodies, except escaped markdown → `innerHTML` in
  `renderMarkdown`.
- **Persistence is split**: chats in IndexedDB (`omni_db` / `chats`), settings in
  `localStorage['omni_settings']`, plus `omni_active_chat` and `omni_sidebar`.
- **API keys live in localStorage** and travel per-request to the Worker. That
  includes the provider keys, the E2B/Novita sandbox key, and the Tavily key.
  The Worker stores nothing. Never log or persist a key anywhere else.
- **Web search is pure BYOK.** There is no Worker-held Tavily key, no KV
  counter, no monthly cap, and no `/usage` route. The key is read from
  `body.tavilyKey`; a missing key must degrade to a normal answer, never a
  failed chat. Search quality lives in `worker.js` (`buildSearchPlan`,
  `runTavilySearch`, `curateSearchResults`, `buildSearchContext`).
- **Native-search providers are Anthropic, Google and OpenRouter only.** xAI is
  *not* one: its Live Search (`search_parameters`) was removed and now answers
  `410 Gone`, and Chat Completions accepts function tools only, so xAI's
  built-in `web_search` tool is unreachable there. xAI searches through Tavily
  like the other OpenAI-compatible providers.
- **Event listeners are bound inline throughout the script** (e.g. 7874–7904),
  not centralized in `init()`. `init()` only loads data and paints.

---

## Landmines

1. **`#settingsModal` now closes cleanly — do not remove the closer.** The file
   has 120 `<div` opens and 120 closes. The overlay opened at 3475 closes at
   3702, before `#codePreviewModal` starts at 3704. Before the fix it never
   closed, so the preview modal, the toast (3723), and `<script>` parsed as its
   descendants and inherited `opacity: 0` from `.modal-overlay`. Keep the div
   balance if editing this region.

2. **`sendMessage` is ~650 lines** (7135–7783) and branches on provider, search
   mode, agent mode, and attachments simultaneously. Change one path at a time.

3. **`sw.js` caches only `/index.html` as the shell.** Any new file added to the
   app must be added to `ASSETS` (line 5) or it breaks offline. Cross-origin
   requests (Tailwind, Prism, Google Fonts) bypass the service worker entirely —
   they are not available offline.

4. **`worker.js` mirrors frontend constants.** `SANDBOX_PROVIDERS` at 6606 is
   explicitly kept in sync with the Worker. Provider request shapes exist in both
   files; changing one usually means changing the other.

5. **The Tavily key must never leak into logs or provider payloads.** It is
   forwarded only to `api.tavily.com` as a Bearer token. Search cache entries
   contain public results, not the key, and queries are hashed in cache keys.
   Do not add the key to error messages, console output, or `searchEvent`.

6. **Anthropic web search needs `allowed_callers: ['direct']`.** The
   `web_search_20260209`+ versions otherwise default to being called from
   inside code execution, which provisions a sandbox mid-turn and can pause
   the turn — and this proxy streams one response with no resume path.

7. **Verify provider search APIs against their live OpenAPI spec before
   changing them.** Three of them changed under this code: xAI removed
   `search_parameters`, Anthropic changed the `allowed_callers` default, and
   Google moved to a `google_search` tool on the newer Interactions API while
   `streamGenerateContent` still takes `googleSearch`.

8. **Line numbers drift.** This map was resynced after the search/BYOK refresh.
   Grep for the named function before editing; don't trust an offset.

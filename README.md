# Omni

Chat with **12 AI providers** through a single interface — no accounts, no vendor lock-in, no recurring frontend subscriptions. One HTML file, one Cloudflare Worker, and your own API keys.

## Why?

I use different models for different tasks: Claude for reasoning, GPT for speed, Gemini for long contexts, Groq for free-tier throughput. Switching between provider-specific chat UIs wastes time. The hosted alternatives (Poe, ChatHub, TypingMind) either route your API keys through their servers or charge monthly. I wanted a chat interface I fully control — that lives at a URL I own, never sees my keys, works offline, and adds zero operational cost beyond the Cloudflare Worker's free tier.

## What it can do

[SCREENSHOT: main chat view showing a conversation with streaming response, sidebar with chat list, and the composer with model picker open]

[SCREENSHOT: settings modal with provider picker dropdown, API key field, system prompt editor, temperature/max-tokens sliders, and thinking toggle]

[SCREENSHOT: code block in a message with syntax highlighting, language header, and copy/download/wrap buttons]

[SCREENSHOT: slash command menu open in the composer showing available templates like /code, /explain, /summarize]

[SCREENSHOT: metrics panel showing tokens per second, time to first token, context window bar, and total token counter]

[SCREENSHOT: mobile layout with sidebar as an overlay and model picker as a bottom sheet]

### Chat

- **11 built-in providers**: OpenAI, Anthropic, Google AI Studio, Cerebras, NVIDIA NIM, Groq, Moonshot AI, Together AI, DeepSeek, xAI Grok, OpenRouter — plus custom OpenAI-compatible endpoints (Ollama, LiteLLM, local models)
- 90+ preset models with context window sizes
- Streaming responses with token-by-token rendering and a typewriter cursor
- Stop generation mid-response
- Scroll-to-latest button when scrolled up during streaming

### Messages

- Full Markdown rendering with syntax-highlighted fenced code blocks (Prism.js)
- Code block actions: copy to clipboard, download as file, toggle word wrap, live HTML/SVG/XML preview in an iframe sandbox
- Edit any message — editing a user message re-sends with the corrected prompt
- Regenerate responses with branch history — previous generations are preserved as branches you can cycle through with prev/next buttons
- Copy individual messages
- Text-to-speech for assistant responses (Web Speech Synthesis API)
- Thinking/reasoning tokens displayed in expandable details (Anthropic extended thinking, OpenAI reasoning)

### Composer

- Auto-resizing textarea
- `/` slash command menu with templates: code mode (strict production code), web search, explain, summarize, refactor, translate, fix bugs, write tests, and more
- Web search toggle — queries Tavily, injects results into the prompt
- Code mode — prepends a system prompt enforcing clean, complete, production-ready code
- File attachment: images as base64 inline, text files truncated to 8 KB with a visible tag
- Paste images from clipboard
- Voice dictation (Web Speech Recognition API)
- Model picker dropdown with search, grouped by provider, with custom model management (add, edit, delete)

### Settings

- Per-provider API key with show/hide toggle (stored in `localStorage`, never sent anywhere except your Cloudflare Worker)
- Custom provider section: base URL + custom model list for any OpenAI-compatible endpoint
- System prompt editor
- Temperature slider (0–2)
- Max output tokens slider (64–16,384)
- Thinking toggle for providers that support it

### Metrics

Real-time per-response metrics:
- Generation tokens per second
- Prompt tokens per second
- Input/output token counts
- Time to first token (TTFT)
- Total response duration
- Context window usage bar

### Data

- Chat history persisted to IndexedDB (with automatic migration from legacy `localStorage`)
- Full-text search across chat titles and message content
- Export all chats as JSON, import from JSON files
- Incognito mode — chat is never persisted, UI theme switches to a purple-tinted palette

### UX

- Dark theme with custom CSS variables
- Responsive: sidebar collapses to overlay on ≤768px, model picker becomes a bottom sheet
- Keyboard shortcuts: `Ctrl+N` new chat, `Ctrl+Shift+N` toggle incognito, `Ctrl+K` focus composer, `Escape` dismiss modals
- PWA with `manifest.json` — installable on desktop and mobile, works offline via service worker

## How it works

```
Browser (index.html)
    │
    ├── localStorage  →  settings, API keys, provider/model selection
    ├── IndexedDB     →  chat history (skipped in incognito mode)
    │
    └── POST /api/chat  ──→  Cloudflare Worker (worker.js)
                                  │
                                  ├── OpenAI-compatible  (OpenAI, Cerebras, NVIDIA, Groq, Together, DeepSeek, xAI, OpenRouter, custom)
                                  ├── Anthropic API       (Claude — different message format, system prompt handling, thinking tokens)
                                  └── Google AI Studio    (Gemini — parts array, system instruction, Google Search grounding)
```

The frontend is a single HTML file with vanilla JavaScript. No frameworks, no build step. Tailwind CSS v4 and Prism.js are loaded from CDN at runtime. CSS variables handle all theming — incognito mode swaps the palette by toggling one class on `<body>`.

The Cloudflare Worker (`worker.js`) is the critical piece. Each AI provider speaks a different protocol — OpenAI uses `data: [DONE]` SSE, Anthropic uses typed events (`content_block_delta`, `message_delta`), Google uses its own SSE format with `candidates[].content.parts[]`. The Worker normalizes all three into a single NDJSON stream (`{"delta":"text"}\n`) so the frontend only has to parse one format. It also translates message schemas — OpenAI multipart `content` arrays into Anthropic image blocks, Google `inline_data` parts, etc.

Web search works by intercepting the request in the Worker, calling the Tavily API with the last user message, injecting the results as a system-level prefix, and tracking monthly usage (capped at 900 searches) via Cloudflare KV. The xAI Grok provider uses native search parameters instead.

## What I figured out along the way

### Three streaming protocols, one NDJSON pipe

OpenAI, Anthropic, and Google each stream differently — SSE with `data:` prefix, typed events with separate `event:` lines, and a variant SSE format with `thought` metadata on parts. The Worker translates all three into a flat NDJSON stream. The frontend's parser handles five different field names for the content delta (`delta`, `content`, `token`, `text`, and raw non-JSON fallback) because providers don't agree on the schema and the Worker normalizes but doesn't fully homogenize. See `streamOpenAICompatible`, `streamAnthropic`, and `streamGoogle` in `worker.js:148–369`.

### FileReader race condition

`FileReader.readAsDataURL` and `FileReader.readAsText` are asynchronous but the event-based API doesn't compose well with sequential processing. When attaching multiple files, the original code triggered all readers at once, which intermittently dropped files or corrupted the order. The fix was a recursive `readNext()` pattern (`index.html:5386–5408`) that chains readers: each `onload` calls `readNext()` for the next file in the queue, ensuring serial execution. Same approach applies to clipboard paste images (`index.html:5411–5433`).

### Web search reliability

Tavily's API occasionally returns empty results, a non-200 status, or worse — times out after several seconds. The first fix just added error logging. Then I added an `ALLOWED_ORIGINS` check so only the production Worker URL can trigger search (preventing abuse of the Tavily key). Then a monthly cap via Cloudflare KV (900 searches/month, stored with a `YYYY-MM` key and 40-day TTL). The search results are injected as a system prefix with explicit instructions to the model: "Answer the question above using these search results. Do not mention the search." — without this, models would sometimes ignore the results and answer from training data. See `worker.js:396–472`.

### Touch scroll vs. auto-scroll on mobile

During streaming, the UI auto-scrolls to follow new tokens. On mobile, touch-scrolling up to read older messages would get interrupted because the near-bottom detection logic incorrectly classified the position. The fix tracks whether the user is actively touching the screen and skips auto-scroll during touch interactions (`f293ec4`).

### Branch navigation for regenerated responses

When you regenerate a response, the previous version is saved to a `_branches` array keyed by the parent user message index. But the branch navigation needs to track which branch is currently active — not just for the current state but also when branches themselves get overlaid by new regenerations. The active branch index is stored in `_branchActive[parentUserIdx]`, where `null` or `>= branches.length` means "viewing current (latest)." Cycling left/right saves the current content back into the branch slot before loading the next one, so you never lose a version. See `index.html:4142–4191`.

### localStorage → IndexedDB migration

Originally, all chats were stored in `localStorage` as a serialized JSON blob — fast to read but blocking on every write, and capped at ~5–10 MB. When the chat list grew beyond ~50 conversations, saves would sometimes fail silently because the quota was exceeded. The migration to IndexedDB was transparent: on init, if no IndexedDB records exist but legacy `localStorage` data does, each chat is written to IndexedDB and the legacy key is removed. New chats write directly to IndexedDB with errors logged but swallowed — the app never blocks on persistence failures. See `index.html:5533–5566`.

### API key security model

API keys are stored in `localStorage` per provider, never in the Worker source. The Worker is just a passthrough — it receives the key, model, and messages in each POST body, forwards to the provider, and streams back. The Worker script itself contains zero secrets. This means anyone can audit the Worker code and confirm it doesn't log, store, or exfiltrate keys. The tradeoff is that keys travel through the Worker, which you must trust — but since you deploy it yourself, that trust boundary is you.

## Setup

### 1. Deploy the Cloudflare Worker

1. Go to the [Cloudflare Dashboard](https://dash.cloudflare.com/) → Workers & Pages → Create
2. Create a Worker, paste the contents of `worker.js`
3. Set the following **secrets** (Worker → Settings → Variables):
   - `TAVILY_API_KEY` — your [Tavily API key](https://tavily.com/) for web search (optional, search is disabled without it)
   - `SEARCH_COUNTER` — create a KV namespace and bind it as `SEARCH_COUNTER` to enable the 900/month search cap
4. Deploy. Your Worker URL will be something like `https://omni-proxy.your-subdomain.workers.dev`

### 2. Serve the static files

Serve `index.html`, `icon.svg`, `manifest.json`, and `sw.js` from any static host — GitHub Pages, Cloudflare Pages, Netlify, Vercel, or even `npx serve`. No build step required.

Example with Cloudflare Pages:
1. Pages → Create a project → Upload assets
2. Drag in the four files and deploy

### 3. Configure the app

1. Open the deployed URL
2. Click the settings icon in the sidebar
3. Set the **API Endpoint** to your Worker URL (from step 1)
4. Select a provider, enter your API key, pick a model
5. Start chatting

Everything is stored in your browser — no accounts, no database, no backend state.

## License

[MIT](LICENSE)

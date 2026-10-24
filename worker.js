// Omni AI Chat Proxy — Cloudflare Worker
// Deploy: Cloudflare Dashboard → Workers & Pages → Create → paste → Deploy
// No install. No build. No dependencies.

const PROVIDER_URLS = {
  openai: 'https://api.openai.com/v1/chat/completions',
  deepinfra: 'https://api.deepinfra.com/v1/openai/chat/completions',
  nvidia: 'https://integrate.api.nvidia.com/v1/chat/completions',
  moonshot: 'https://api.moonshot.ai/v1/chat/completions',
  gmicloud: 'https://api.gmi-serving.com/v1/chat/completions',
  together: 'https://api.together.xyz/v1/chat/completions',
  deepseek: 'https://api.deepseek.com/chat/completions',
  xai: 'https://api.x.ai/v1/chat/completions',
  openrouter: 'https://openrouter.ai/api/v1/chat/completions',
  anthropic: 'https://api.anthropic.com/v1/messages',
};

// Tavily search cache. The Worker is stateless across restarts, so this is a
// best-effort in-memory cache: repeated queries in the same isolate reuse the
// results instead of charging the user's Tavily account again. No KV, no disk,
// no secrets. Queries are hashed so raw prompts are not kept as Map keys.
const SEARCH_CACHE_TTL_MS = 15 * 60 * 1000;
const SEARCH_CACHE_MAX = 50;
const searchCache = new Map();

function searchCacheKey(depth, topic, timeRange, query) {
  let h = 5381;
  for (let i = 0; i < query.length; i++) h = ((h * 33) ^ query.charCodeAt(i)) >>> 0;
  return depth + '|' + topic + '|' + (timeRange || '') + '|' + h.toString(36);
}

function searchCacheGet(key) {
  const hit = searchCache.get(key);
  if (!hit) return null;
  if (Date.now() - hit.at > SEARCH_CACHE_TTL_MS) {
    searchCache.delete(key);
    return null;
  }
  return hit.value;
}

function searchCacheSet(key, value) {
  if (searchCache.size >= SEARCH_CACHE_MAX) {
    const oldest = searchCache.keys().next().value;
    if (oldest !== undefined) searchCache.delete(oldest);
  }
  searchCache.set(key, { at: Date.now(), value });
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };
}

function jsonResponse(data, status) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...corsHeaders() },
  });
}

function streamToNDJSON(readable) {
  return new Response(readable, {
    headers: { 'Content-Type': 'application/x-ndjson', ...corsHeaders() },
  });
}

// The custom-provider base URL is caller-supplied and gets fetched server-side,
// so without this the Worker is an open relay into private network space.
function isSafeCustomURL(raw) {
  let u;
  try { u = new URL(raw); } catch (_) { return false; }
  if (u.protocol !== 'https:') return false;
  const host = u.hostname.toLowerCase();
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.internal')) return false;
  if (/^\[?::1\]?$/.test(host) || /^\[?f[cd][0-9a-f]{2}:/.test(host)) return false;
  const v4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const a = +v4[1], b = +v4[2];
    if (a === 10 || a === 127 || a === 0) return false;
    if (a === 192 && b === 168) return false;
    if (a === 172 && b >= 16 && b <= 31) return false;
    if (a === 169 && b === 254) return false;
  }
  return true;
}

// Client tools arrive as JSON Schema function declarations:
//   [{ name, description, parameters }]
// Each provider wants that in a different wrapper. Validation is deliberately
// strict — these names round-trip back to us as tool_call_id references.
function sanitizeTools(tools) {
  if (!Array.isArray(tools)) return null;
  const clean = [];
  for (const t of tools) {
    if (!t || typeof t.name !== 'string') continue;
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(t.name)) continue;
    clean.push({
      name: t.name,
      description: typeof t.description === 'string' ? t.description.slice(0, 1024) : '',
      parameters: t.parameters && typeof t.parameters === 'object' ? t.parameters : { type: 'object', properties: {} },
    });
    if (clean.length >= 16) break;
  }
  return clean.length ? clean : null;
}

// Gemini accepts only a subset of JSON Schema and returns 400 on unknown keys,
// so strip everything it does not understand rather than passing schemas through.
const GOOGLE_SCHEMA_KEYS = ['type', 'format', 'description', 'nullable', 'enum', 'items', 'properties', 'required'];
function sanitizeSchemaForGoogle(schema) {
  if (!schema || typeof schema !== 'object') return { type: 'object', properties: {} };
  if (Array.isArray(schema)) return schema.map(sanitizeSchemaForGoogle);
  const out = {};
  for (const key of GOOGLE_SCHEMA_KEYS) {
    if (!(key in schema)) continue;
    const val = schema[key];
    if (key === 'properties' && val && typeof val === 'object') {
      out.properties = {};
      for (const p of Object.keys(val)) out.properties[p] = sanitizeSchemaForGoogle(val[p]);
    } else if (key === 'items') {
      out.items = sanitizeSchemaForGoogle(val);
    } else {
      out[key] = val;
    }
  }
  if (!out.type) out.type = 'object';
  return out;
}

function buildOpenAIRequest(model, messages, temperature, maxTokens, effort, tools) {
  const isOModel = /^o[0-9]/.test(model);
  const body = {
    model,
    messages,
    stream: true,
    // Without this a strict OpenAI-compatible endpoint reports no usage at all
    // while streaming, and the client silently falls back to counting
    // characters. Estimated tokens that look like real ones are worse than no
    // number, so ask for the real ones everywhere.
    stream_options: { include_usage: true },
  };
  if (isOModel) {
    body.max_completion_tokens = maxTokens;
  } else {
    body.max_tokens = maxTokens;
    body.temperature = temperature;
  }
  if (effort) {
    body.reasoning_effort = effort;
  }
  if (tools) {
    body.tools = tools.map((t) => ({
      type: 'function',
      function: { name: t.name, description: t.description, parameters: t.parameters },
    }));
  }
  return body;
}

function translateToAnthropicContent(content) {
  if (typeof content === 'string' || !content) return content;
  if (!Array.isArray(content)) return String(content);
  return content.map((part) => {
    if (part.type === 'image_url' && part.image_url) {
      const url = part.image_url.url || '';
      const base64Match = url.match(/^data:(image\/\w+);base64,(.+)$/);
      if (base64Match) {
        return { type: 'image', source: { type: 'base64', media_type: base64Match[1], data: base64Match[2] } };
      }
      return { type: 'image', source: { type: 'url', url: url } };
    }
    return { type: 'text', text: typeof part.text === 'string' ? part.text : '' };
  });
}

const ANTHROPIC_EFFORT_BUDGET = { standard: 4096, high: 8192, max: 16384 };
const GOOGLE_EFFORT_BUDGET = { standard: 2048, high: 8192, max: 16384 };

function anthropicSearchTool(model) {
  const legacy = model.indexOf('haiku-4-5') !== -1 || model.indexOf('sonnet-4-5') !== -1;
  if (legacy) {
    return { type: 'web_search_20250305', name: 'web_search', max_uses: 5, allowed_callers: ['direct'] };
  }
  // The 20260209+ versions default to being called from inside code
  // execution, which needs the API to provision a sandbox mid-turn and can
  // pause the turn. This proxy streams one response and cannot resume a
  // paused turn, so direct calling is pinned instead.
  return { type: 'web_search_20260318', name: 'web_search', max_uses: 5, allowed_callers: ['direct'] };
}

function safeParseArgs(raw) {
  if (raw && typeof raw === 'object') return raw;
  try {
    const parsed = JSON.parse(raw || '{}');
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (_) {
    return {};
  }
}

// Anthropic and Google both reject one tool result per turn when the model made
// several calls at once — the results have to be merged into a single turn. Doing
// that grouping once here keeps the run-merging logic out of all three builders.
function groupToolTurns(messages) {
  const turns = [];
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    if (m.role === 'tool') {
      const results = [];
      while (i < messages.length && messages[i].role === 'tool') {
        results.push({
          id: messages[i].tool_call_id || '',
          name: messages[i].name || '',
          content: typeof messages[i].content === 'string' ? messages[i].content : JSON.stringify(messages[i].content || ''),
        });
        i++;
      }
      i--;
      turns.push({ kind: 'tool_results', results });
    } else if (m.role === 'assistant' && Array.isArray(m.tool_calls) && m.tool_calls.length) {
      turns.push({
        kind: 'assistant_tools',
        content: typeof m.content === 'string' ? m.content : '',
        toolCalls: m.tool_calls.map((tc) => ({
          id: tc.id || '',
          name: (tc.function && tc.function.name) || tc.name || '',
          args: safeParseArgs(tc.function && tc.function.arguments),
        })),
      });
    } else {
      turns.push({ kind: 'plain', role: m.role, content: m.content });
    }
  }
  return turns;
}

function buildAnthropicRequest(model, messages, temperature, maxTokens, effortBudget, searchTool, tools) {
  const systemMessages = messages.filter((m) => m.role === 'system').map((m) => m.content);
  const chatMessages = messages.filter((m) => m.role !== 'system');

  const anthropicMessages = groupToolTurns(chatMessages).map((turn) => {
    if (turn.kind === 'assistant_tools') {
      const blocks = [];
      if (turn.content) blocks.push({ type: 'text', text: turn.content });
      for (const tc of turn.toolCalls) {
        blocks.push({ type: 'tool_use', id: tc.id, name: tc.name, input: tc.args });
      }
      return { role: 'assistant', content: blocks };
    }
    if (turn.kind === 'tool_results') {
      return {
        role: 'user',
        content: turn.results.map((r) => ({ type: 'tool_result', tool_use_id: r.id, content: r.content })),
      };
    }
    return { role: turn.role, content: translateToAnthropicContent(turn.content) };
  });

  const body = {
    model,
    messages: anthropicMessages,
    max_tokens: maxTokens,
    temperature,
    stream: true,
  };
  if (systemMessages.length) {
    body.system = systemMessages.map((s) => ({ type: 'text', text: s }));
    if (body.system.length === 1) body.system = body.system[0].text;
  }
  if (tools) {
    body.tools = tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters }));
  }
  if (searchTool) {
    // Concat, never assign: agent tools are already in body.tools by this point.
    body.tools = (body.tools || []).concat([searchTool]);
    const steer = 'Use the web search tool when the request depends on current, changing, or out-of-training-data information (news, prices, scores, recent events). Answer directly from stable knowledge without searching otherwise. Cite sources in your reply.';
    if (body.system) {
      const existing = Array.isArray(body.system) ? body.system : [{ type: 'text', text: String(body.system) }];
      body.system = existing.concat([{ type: 'text', text: steer }]);
    } else {
      body.system = steer;
    }
  }
  if (effortBudget) {
    const budget = Math.min(Math.max(effortBudget, 1024), Math.max(maxTokens - 1, 1024));
    body.thinking = { type: 'enabled', budget_tokens: budget };
  }
  return body;
}

function translateToGoogleParts(content) {
  if (typeof content === 'string' || !content) return [{ text: content || '' }];
  if (!Array.isArray(content)) return [{ text: String(content) }];
  var parts = [];
  for (const part of content) {
    if (part.type === 'image_url' && part.image_url) {
      const url = part.image_url.url || '';
      const base64Match = url.match(/^data:(image\/\w+);base64,(.+)$/);
      if (base64Match) {
        parts.push({ inline_data: { mime_type: base64Match[1], data: base64Match[2] } });
      } else {
        parts.push({ file_data: { file_uri: url, mime_type: 'image/jpeg' } });
      }
    } else if (part.text) {
      parts.push({ text: part.text });
    }
  }
  return parts.length ? parts : [{ text: '' }];
}

function buildGoogleRequest(model, messages, temperature, maxTokens, effortBudget, tools) {
  const systemMsg = messages.filter((m) => m.role === 'system');
  const chatMsg = messages.filter((m) => m.role !== 'system');

  // Gemini matches a functionResponse to its call by NAME, not by id.
  const contents = groupToolTurns(chatMsg).map((turn) => {
    if (turn.kind === 'assistant_tools') {
      const parts = [];
      if (turn.content) parts.push({ text: turn.content });
      for (const tc of turn.toolCalls) {
        parts.push({ functionCall: { name: tc.name, args: tc.args } });
      }
      return { role: 'model', parts };
    }
    if (turn.kind === 'tool_results') {
      return {
        role: 'user',
        parts: turn.results.map((r) => ({
          functionResponse: { name: r.name, response: { result: r.content } },
        })),
      };
    }
    return {
      role: turn.role === 'assistant' ? 'model' : 'user',
      parts: translateToGoogleParts(turn.content),
    };
  });

  const body = {
    contents,
    generationConfig: {
      temperature,
      maxOutputTokens: maxTokens,
    },
  };
  if (tools) {
    body.tools = [{
      functionDeclarations: tools.map((t) => ({
        name: t.name,
        description: t.description,
        parameters: sanitizeSchemaForGoogle(t.parameters),
      })),
    }];
  }
  if (systemMsg.length) {
    body.systemInstruction = {
      parts: [{ text: systemMsg.map((s) => s.content).join('\n\n') }],
    };
  }
  if (effortBudget) {
    body.generationConfig.thinkingConfig = { includeThoughts: true, thinkingBudget: effortBudget };
  }
  return body;
}

const NS_PER_MS = 1e6;

function finiteNum(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function tokensPerSecond(tokens, ms) {
  if (tokens == null || ms == null || !(ms > 0)) return null;
  return (tokens / ms) * 1000;
}

// Several OpenAI-compatible backends report their own prompt-eval and generation
// timings on the final chunk. Those are measured inside the server, so unlike
// anything the browser can observe they exclude the network hop and the Worker.
// All three shapes ride the same code path, so one parser covers them.
function parseServerTiming(parsed) {
  // llama.cpp / llama-cpp-python — milliseconds.
  const t = parsed.timings;
  if (t && typeof t === 'object') {
    const promptMs = finiteNum(t.prompt_ms);
    const genMs = finiteNum(t.predicted_ms);
    const promptN = finiteNum(t.prompt_n);
    const genN = finiteNum(t.predicted_n);
    return {
      source: 'llama.cpp',
      prompt_tokens: promptN,
      completion_tokens: genN,
      prompt_ms: promptMs,
      gen_ms: genMs,
      queue_ms: null,
      prompt_tps: finiteNum(t.prompt_per_second) ?? tokensPerSecond(promptN, promptMs),
      gen_tps: finiteNum(t.predicted_per_second) ?? tokensPerSecond(genN, genMs),
    };
  }

  // Groq — seconds.
  const g = parsed.x_groq && parsed.x_groq.usage;
  if (g && typeof g === 'object') {
    const promptSec = finiteNum(g.prompt_time);
    const genSec = finiteNum(g.completion_time);
    const queueSec = finiteNum(g.queue_time);
    const promptMs = promptSec == null ? null : promptSec * 1000;
    const genMs = genSec == null ? null : genSec * 1000;
    const promptN = finiteNum(g.prompt_tokens);
    const genN = finiteNum(g.completion_tokens);
    return {
      source: 'groq',
      prompt_tokens: promptN,
      completion_tokens: genN,
      prompt_ms: promptMs,
      gen_ms: genMs,
      queue_ms: queueSec == null ? null : queueSec * 1000,
      prompt_tps: tokensPerSecond(promptN, promptMs),
      gen_tps: tokensPerSecond(genN, genMs),
    };
  }

  // Ollama — nanoseconds.
  const evalNs = finiteNum(parsed.eval_duration);
  const promptNs = finiteNum(parsed.prompt_eval_duration);
  if (evalNs != null || promptNs != null) {
    const promptMs = promptNs == null ? null : promptNs / NS_PER_MS;
    const genMs = evalNs == null ? null : evalNs / NS_PER_MS;
    const promptN = finiteNum(parsed.prompt_eval_count);
    const genN = finiteNum(parsed.eval_count);
    const loadNs = finiteNum(parsed.load_duration);
    return {
      source: 'ollama',
      prompt_tokens: promptN,
      completion_tokens: genN,
      prompt_ms: promptMs,
      gen_ms: genMs,
      queue_ms: loadNs == null ? null : loadNs / NS_PER_MS,
      prompt_tps: tokensPerSecond(promptN, promptMs),
      gen_tps: tokensPerSecond(genN, genMs),
    };
  }

  return null;
}

// Each protocol has its own vocabulary for why generation ended, and the client
// needs one. "Finished" and "ran out of output budget" look identical today.
const STOP_REASON_MAP = {
  stop: 'end_turn',
  end_turn: 'end_turn',
  STOP: 'end_turn',
  length: 'max_tokens',
  max_tokens: 'max_tokens',
  MAX_TOKENS: 'max_tokens',
  tool_calls: 'tool_use',
  function_call: 'tool_use',
  tool_use: 'tool_use',
  stop_sequence: 'stop_sequence',
  content_filter: 'content_filter',
  refusal: 'content_filter',
  SAFETY: 'content_filter',
  RECITATION: 'content_filter',
  PROHIBITED_CONTENT: 'content_filter',
  BLOCKLIST: 'content_filter',
  SPII: 'content_filter',
  IMAGE_SAFETY: 'content_filter',
  pause_turn: 'pause_turn',
  MALFORMED_FUNCTION_CALL: 'error',
  OTHER: 'error',
};

function normalizeStopReason(raw) {
  if (!raw) return null;
  return STOP_REASON_MAP[raw] || String(raw).toLowerCase();
}

async function streamOpenAICompatible(url, apiKey, requestBody, encoder, writer, collectCitations) {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify(requestBody),
  });

  if (!response.ok) {
    const errText = await response.text().catch(() => response.statusText);
    await writer.write(encoder.encode(JSON.stringify({ error: { message: `Provider error ${response.status}: ${errText}` } }) + '\n'));
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const citedSources = [];
  const citedUrls = new Set();

  const collectAnnotations = (annotations) => {
    if (!collectCitations || !Array.isArray(annotations)) return;
    for (const a of annotations) {
      const uc = a && a.type === 'url_citation' ? a.url_citation : null;
      if (!uc || !uc.url || citedUrls.has(uc.url)) continue;
      citedUrls.add(uc.url);
      citedSources.push({ title: (uc.title || uc.url).slice(0, 160), url: uc.url, date: '' });
      if (citedSources.length >= 10) return;
    }
  };

  const emitCitedSources = async () => {
    if (citedSources.length) {
      await writer.write(encoder.encode(JSON.stringify({ search: { query: '', sources: citedSources } }) + '\n'));
    }
  };

  // Tool calls stream as sparse indexed fragments: the array position is always 0
  // while tc.index carries the real slot, so key off tc.index and never position.
  const toolSlots = new Map();
  let nextSlot = 0;
  let sawToolCall = false;
  let stopSent = false;

  const sendToolStop = async () => {
    if (stopSent) return;
    stopSent = true;
    await writer.write(encoder.encode(JSON.stringify({ stop: { reason: 'tool_use' } }) + '\n'));
  };

  const closeOpenSlots = async () => {
    for (const slot of toolSlots.values()) {
      await writer.write(encoder.encode(JSON.stringify({ tool_end: { i: slot } }) + '\n'));
    }
    toolSlots.clear();
  };

  const handleChunk = async (parsed) => {
    const choices = parsed.choices;
    if (choices && choices[0]) {
      const delta = choices[0].delta;
      collectAnnotations(delta && delta.annotations);
      collectAnnotations(choices[0].message && choices[0].message.annotations);
      const content = delta ? (delta.content || delta.text || '') : '';

      if (content) {
        await writer.write(encoder.encode(JSON.stringify({ delta: content }) + '\n'));
      }

      const reasoning = delta ? delta.reasoning_content : null;
      if (reasoning) {
        await writer.write(encoder.encode(JSON.stringify({ thinking_delta: reasoning }) + '\n'));
      }

      if (delta && Array.isArray(delta.tool_calls)) {
        for (const tc of delta.tool_calls) {
          if (!tc) continue;
          const idx = typeof tc.index === 'number' ? tc.index : 0;
          const fn = tc.function || {};
          let slot = toolSlots.get(idx);
          if (slot === undefined) {
            slot = nextSlot++;
            toolSlots.set(idx, slot);
            sawToolCall = true;
            await writer.write(encoder.encode(JSON.stringify({
              tool_start: { i: slot, id: tc.id || 'call_' + slot, name: fn.name || '' },
            }) + '\n'));
          } else if (tc.id || fn.name) {
            // Some providers send id/name on a later chunk than the first;
            // tool_start is an upsert on the client, so re-emitting is safe.
            await writer.write(encoder.encode(JSON.stringify({
              tool_start: { i: slot, id: tc.id || 'call_' + slot, name: fn.name || '' },
            }) + '\n'));
          }
          if (fn.arguments) {
            await writer.write(encoder.encode(JSON.stringify({ tool_args: { i: slot, d: fn.arguments } }) + '\n'));
          }
        }
      }

      const finish = choices[0].finish_reason;
      if (finish === 'tool_calls' || finish === 'function_call') {
        await closeOpenSlots();
        await sendToolStop();
      }
      if (finish) {
        await writer.write(encoder.encode(JSON.stringify({ stop_reason: normalizeStopReason(finish) }) + '\n'));
      }
    }
    if (parsed.usage) {
      // The detail objects are what make the headline counts interpretable: a
      // cached prefill is not the same work as a cold one, and reasoning tokens
      // bill as output while never appearing in the visible answer.
      const promptDetail = parsed.usage.prompt_tokens_details || {};
      const completionDetail = parsed.usage.completion_tokens_details || {};
      await writer.write(encoder.encode(JSON.stringify({
        usage: {
          prompt_tokens: parsed.usage.prompt_tokens,
          completion_tokens: parsed.usage.completion_tokens,
          total_tokens: parsed.usage.total_tokens,
          cached_tokens: promptDetail.cached_tokens,
          reasoning_tokens: completionDetail.reasoning_tokens,
        }
      }) + '\n'));
    }
    const serverTiming = parseServerTiming(parsed);
    if (serverTiming) {
      await writer.write(encoder.encode(JSON.stringify({ server_timing: serverTiming }) + '\n'));
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.startsWith('data: ')) continue;
      const data = trimmed.slice(6);
      if (data === '[DONE]') continue;

      try {
        await handleChunk(JSON.parse(data));
      } catch (_) { /* skip */ }
    }
  }
  if (buffer.trim().startsWith('data: ') && buffer.trim().slice(6) !== '[DONE]') {
    try {
      await handleChunk(JSON.parse(buffer.trim().slice(6)));
    } catch (_) { /* skip */ }
  }
  // Providers truncate without a finish_reason; never leave a slot unterminated.
  if (toolSlots.size) await closeOpenSlots();
  if (sawToolCall) await sendToolStop();
  await emitCitedSources();
}

async function streamAnthropic(apiKey, requestBody, encoder, writer) {
  const response = await fetch(PROVIDER_URLS.anthropic, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'anthropic-beta': 'output-128k-2025-02-19',
    },
    body: JSON.stringify(requestBody),
  });

  if (!response.ok) {
    const errText = await response.text().catch(() => response.statusText);
    await writer.write(encoder.encode(JSON.stringify({ error: { message: `Provider error ${response.status}: ${errText}` } }) + '\n'));
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = '';
  const searchSources = [];
  const seenSearchUrls = new Set();
  // Maps an Anthropic content_block index to our normalized slot. Only agent
  // tool_use blocks get an entry — web search is a server_tool_use block that
  // Anthropic executes itself, so its args must never reach the client.
  const blockSlots = new Map();
  let nextSlot = 0;
  let sawToolUse = false;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      if (line.startsWith('event: ')) {
        currentEvent = line.slice(7).trim();
        continue;
      }
      if (!line.startsWith('data: ')) continue;
      const data = line.slice(6);
      if (!data) continue;

      try {
        const parsed = JSON.parse(data);
        const type = parsed.type || '';

        if (type === 'message_start') {
          // Anthropic reports input on message_start and output on message_delta,
          // and the message_delta copy of input_tokens is routinely absent. Only
          // the second was forwarded, so the input count could vanish entirely.
          const startUsage = parsed.message?.usage;
          if (startUsage) {
            await writer.write(encoder.encode(JSON.stringify({
              usage: {
                prompt_tokens: startUsage.input_tokens,
                cached_tokens: startUsage.cache_read_input_tokens,
                cache_write_tokens: startUsage.cache_creation_input_tokens,
              }
            }) + '\n'));
          }
        } else if (type === 'content_block_start' && parsed.content_block && parsed.content_block.type === 'web_search_tool_result') {
          const results = parsed.content_block.content;
          if (Array.isArray(results)) {
            for (const r of results) {
              if (!r || r.type !== 'web_search_result' || !r.url || seenSearchUrls.has(r.url)) continue;
              seenSearchUrls.add(r.url);
              searchSources.push({ title: (r.title || r.url).slice(0, 160), url: r.url, date: (r.page_age || '').slice(0, 10) });
              if (searchSources.length >= 10) break;
            }
          }
        } else if (type === 'content_block_start' && parsed.content_block && parsed.content_block.type === 'tool_use') {
          const slot = nextSlot++;
          blockSlots.set(parsed.index, slot);
          sawToolUse = true;
          await writer.write(encoder.encode(JSON.stringify({
            tool_start: { i: slot, id: parsed.content_block.id || 'call_' + slot, name: parsed.content_block.name || '' },
          }) + '\n'));
        } else if (type === 'content_block_stop' && blockSlots.has(parsed.index)) {
          await writer.write(encoder.encode(JSON.stringify({ tool_end: { i: blockSlots.get(parsed.index) } }) + '\n'));
          blockSlots.delete(parsed.index);
        } else if (type === 'content_block_delta') {
          const deltaType = parsed.delta?.type;
          if (deltaType === 'text_delta') {
            await writer.write(encoder.encode(JSON.stringify({ delta: parsed.delta.text }) + '\n'));
          } else if (deltaType === 'thinking_delta') {
            await writer.write(encoder.encode(JSON.stringify({ thinking_delta: parsed.delta.thinking }) + '\n'));
          } else if (deltaType === 'input_json_delta') {
            // Route to the owning tool slot. If the block is not one of ours it
            // belongs to server-side web search: drop it rather than emitting it
            // as a text delta, which is what leaked raw JSON into the transcript.
            const slot = blockSlots.get(parsed.index);
            if (slot !== undefined) {
              await writer.write(encoder.encode(JSON.stringify({ tool_args: { i: slot, d: parsed.delta.partial_json } }) + '\n'));
            }
          }
        } else if (type === 'message_delta') {
          const stopReason = parsed.delta?.stop_reason;
          if (parsed.usage) {
            // input_tokens is usually absent here; message_start already sent the
            // real one, so never overwrite it with an undefined.
            const usage = {
              completion_tokens: parsed.usage.output_tokens,
              cached_tokens: parsed.usage.cache_read_input_tokens,
              cache_write_tokens: parsed.usage.cache_creation_input_tokens,
            };
            if (typeof parsed.usage.input_tokens === 'number') {
              usage.prompt_tokens = parsed.usage.input_tokens;
            }
            await writer.write(encoder.encode(JSON.stringify({ usage }) + '\n'));
          }
          if (stopReason) {
            await writer.write(encoder.encode(JSON.stringify({ stop_reason: normalizeStopReason(stopReason) }) + '\n'));
          }
          if (stopReason === 'tool_use') {
            await writer.write(encoder.encode(JSON.stringify({ stop: { reason: 'tool_use' } }) + '\n'));
          }
        } else if (type === 'error') {
          await writer.write(encoder.encode(JSON.stringify({ error: { message: parsed.error?.message || 'Anthropic error' } }) + '\n'));
        }
      } catch (_) { /* skip */ }
    }
  }
  // Guard against a truncated stream leaving a tool block unterminated.
  if (blockSlots.size) {
    for (const slot of blockSlots.values()) {
      await writer.write(encoder.encode(JSON.stringify({ tool_end: { i: slot } }) + '\n'));
    }
    blockSlots.clear();
    await writer.write(encoder.encode(JSON.stringify({ stop: { reason: 'tool_use' } }) + '\n'));
  }
  if (searchSources.length) {
    await writer.write(encoder.encode(JSON.stringify({ search: { query: '', sources: searchSources } }) + '\n'));
  }
}

async function streamGoogle(model, apiKey, requestBody, encoder, writer, collectGrounding) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:streamGenerateContent?alt=sse`;
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-goog-api-key': apiKey,
    },
    body: JSON.stringify(requestBody),
  });

  if (!response.ok) {
    const errText = await response.text().catch(() => response.statusText);
    await writer.write(encoder.encode(JSON.stringify({ error: { message: `Provider error ${response.status}: ${errText}` } }) + '\n'));
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const searchSources = [];
  const seenSearchUrls = new Set();
  let nextSlot = 0;
  let sawFunctionCall = false;

  const collectChunks = (groundingMetadata) => {
    if (!collectGrounding || !groundingMetadata || !Array.isArray(groundingMetadata.groundingChunks)) return;
    for (const chunk of groundingMetadata.groundingChunks) {
      const web = chunk && chunk.web;
      if (!web || !web.uri || seenSearchUrls.has(web.uri)) continue;
      seenSearchUrls.add(web.uri);
      searchSources.push({ title: (web.title || web.uri).slice(0, 160), url: web.uri, date: '' });
      if (searchSources.length >= 10) return;
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.startsWith('data: ')) continue;
      const data = trimmed.slice(6);
      if (!data) continue;

      try {
        const parsed = JSON.parse(data);
        if (parsed.error) {
          await writer.write(encoder.encode(JSON.stringify({ error: { message: parsed.error.message || 'Google API error' } }) + '\n'));
          continue;
        }

        if (parsed.usageMetadata) {
          await writer.write(encoder.encode(JSON.stringify({
            usage: {
              prompt_tokens: parsed.usageMetadata.promptTokenCount,
              completion_tokens: parsed.usageMetadata.candidatesTokenCount,
              total_tokens: parsed.usageMetadata.totalTokenCount,
              cached_tokens: parsed.usageMetadata.cachedContentTokenCount,
              reasoning_tokens: parsed.usageMetadata.thoughtsTokenCount,
            }
          }) + '\n'));
        }

        const candidates = parsed.candidates;
        if (candidates && candidates.length) {
          for (const candidate of candidates) {
            collectChunks(candidate.groundingMetadata);
            // Gemini reports STOP alongside a function call, so this is only
            // trustworthy as a reason, never as a "should the loop continue".
            if (candidate.finishReason) {
              await writer.write(encoder.encode(JSON.stringify({
                stop_reason: normalizeStopReason(candidate.finishReason),
              }) + '\n'));
            }
            const parts = candidate.content?.parts;
            if (parts) {
              for (const part of parts) {
                if (part.text) {
                  const event = part.thought
                    ? { thinking_delta: part.text }
                    : { delta: part.text };
                  await writer.write(encoder.encode(JSON.stringify(event) + '\n'));
                } else if (part.functionCall) {
                  // Gemini delivers a call complete rather than streamed, so emit
                  // the whole start/args/end sequence at once. The client then has
                  // one code path for all three providers.
                  const slot = nextSlot++;
                  sawFunctionCall = true;
                  await writer.write(encoder.encode(JSON.stringify({
                    tool_start: { i: slot, id: 'gcall_' + slot, name: part.functionCall.name || '' },
                  }) + '\n'));
                  await writer.write(encoder.encode(JSON.stringify({
                    tool_args: { i: slot, d: JSON.stringify(part.functionCall.args || {}) },
                  }) + '\n'));
                  await writer.write(encoder.encode(JSON.stringify({ tool_end: { i: slot } }) + '\n'));
                }
              }
            }
          }
        }
      } catch (_) { /* skip */ }
    }
  }
  // Gemini reports finishReason STOP even when it emitted a function call, so the
  // presence of a call is the only reliable signal to continue the agent loop.
  if (sawFunctionCall) {
    await writer.write(encoder.encode(JSON.stringify({ stop: { reason: 'tool_use' } }) + '\n'));
  }
  if (searchSources.length) {
    await writer.write(encoder.encode(JSON.stringify({ search: { query: '', sources: searchSources } }) + '\n'));
  }
}


// ---------------------------------------------------------------------------
// E2B sandbox proxy
//
// The user's own E2B key travels in each request body, exactly like their
// provider keys — the Worker stores nothing. E2B splits into two planes:
//   control  REST at api.e2b.app        (create / kill / extend timeout)
//   data     Connect-RPC on the sandbox (process.Process/Start, server-streaming)
// Connect frames each message as [1 byte flags][4 byte big-endian length][JSON],
// which is the same reframing job this Worker already does for LLM protocols.
// ---------------------------------------------------------------------------

// Novita's Agent Sandbox is wire-compatible with E2B: same POST /sandboxes with
// {templateID, timeout}, same sandboxID/domain/envdAccessToken response keys,
// same DELETE and /timeout routes, same envd on 49983 behind a
// {port}-{sandboxId}.{domain} host, and the same "process.Process" Connect
// service. So the two differ only by base URL and domain, not by code path.
// Verified against api.e2b.app's OpenAPI spec and novita-sandbox 2.1.1.
const SANDBOX_PROVIDERS = {
  e2b: {
    label: 'E2B',
    api: 'https://api.e2b.app',
    defaultDomain: 'e2b.app',
    // Covers the bare domain, regional subdomains and their test domain.
    domainRe: /^([a-z0-9-]+\.)*e2b(-[a-z0-9]+)?\.(app|dev)$/,
  },
  novita: {
    label: 'Novita',
    api: 'https://api.us-phx-1.sandbox.novita.ai',
    defaultDomain: 'us-phx-1.sandbox.novita.ai',
    domainRe: /^([a-z0-9-]+\.)*sandbox\.novita\.ai$/,
  },
};

function sandboxProvider(name) {
  return SANDBOX_PROVIDERS[name] || SANDBOX_PROVIDERS.e2b;
}

const E2B_ENVD_PORT = 49983;
const SANDBOX_MAX_TIMEOUT = 300;
const SANDBOX_DEFAULT_TIMEOUT = 120;
const EXEC_MAX_MS = 60000;
const OUTPUT_CAP = 32768;

// sandboxId and domain are interpolated into a URL, so without these checks
// /sandbox/exec would be an open proxy to any host the caller names.
function validSandboxId(id) {
  return typeof id === 'string' && /^[a-zA-Z0-9_-]{8,64}$/.test(id);
}
// The domain is caller-supplied and interpolated into a URL, so it is checked
// against the selected provider's namespace rather than a general pattern.
function validDomain(d, prov) {
  return typeof d === 'string' && prov.domainRe.test(d);
}

function envdBase(sandboxId, domain, prov) {
  const host = validDomain(domain, prov) ? domain : prov.defaultDomain;
  return 'https://' + E2B_ENVD_PORT + '-' + sandboxId + '.' + host;
}

function connectFrame(obj) {
  const payload = new TextEncoder().encode(JSON.stringify(obj));
  const framed = new Uint8Array(5 + payload.length);
  framed[0] = 0;
  new DataView(framed.buffer).setUint32(1, payload.length, false);
  framed.set(payload, 5);
  return framed;
}

// Reads a Connect server-stream, handing each decoded message to onMessage.
async function readConnectStream(response, onMessage) {
  const reader = response.body.getReader();
  let buf = new Uint8Array(0);
  const decoder = new TextDecoder();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    const next = new Uint8Array(buf.length + value.length);
    next.set(buf, 0);
    next.set(value, buf.length);
    buf = next;
    while (buf.length >= 5) {
      const len = new DataView(buf.buffer, buf.byteOffset, buf.byteLength).getUint32(1, false);
      if (buf.length < 5 + len) break;
      const flags = buf[0];
      const body = buf.slice(5, 5 + len);
      buf = buf.slice(5 + len);
      let parsed = null;
      try { parsed = JSON.parse(decoder.decode(body)); } catch (_) { parsed = null; }
      if (parsed) onMessage(flags, parsed);
    }
  }
}

// atob yields a latin1 binary string, so decoding each chunk as text would
// mangle any non-ASCII output. Collect raw bytes instead and decode once at the
// end — a multi-byte character split across two stream chunks would otherwise
// corrupt even with a correct decoder.
function b64bytes(b64) {
  try {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  } catch (_) {
    return new Uint8Array(0);
  }
}

function concatBytes(chunks, cap) {
  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Uint8Array(Math.min(total, cap));
  let at = 0;
  for (const c of chunks) {
    if (at >= out.length) break;
    const take = Math.min(c.length, out.length - at);
    out.set(c.subarray(0, take), at);
    at += take;
  }
  return { bytes: out, truncated: total > cap };
}

function utf8(bytes) {
  return new TextDecoder('utf-8').decode(bytes);
}

async function sandboxCreate(prov, key, timeoutSec, template) {
  const res = await fetch(prov.api + '/sandboxes', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': key },
    body: JSON.stringify({
      templateID: template || 'base',
      timeout: Math.min(Math.max(timeoutSec || SANDBOX_DEFAULT_TIMEOUT, 30), SANDBOX_MAX_TIMEOUT),
    }),
  });
  const text = await res.text();
  if (!res.ok) return { error: prov.label + ' create failed (' + res.status + '): ' + text.slice(0, 300) };
  let data = {};
  try { data = JSON.parse(text); } catch (_) { return { error: prov.label + ' returned unparseable JSON on create' }; }
  const sandboxId = data.sandboxID || data.sandboxId || '';
  if (!validSandboxId(sandboxId)) return { error: prov.label + ' returned no usable sandbox id' };
  return {
    sandboxId,
    domain: validDomain(data.domain, prov) ? data.domain : prov.defaultDomain,
    token: data.envdAccessToken || '',
  };
}

async function handleSandbox(path, request) {
  let body;
  try { body = await request.json(); } catch (_) {
    return jsonResponse({ error: { message: 'Invalid JSON body' } }, 400);
  }
  const prov = sandboxProvider(body.sandboxProvider);
  // e2bKey is still accepted so an older cached frontend keeps working.
  const key = body.sandboxKey || body.e2bKey;
  if (!key || typeof key !== 'string') {
    return jsonResponse({ error: { message: 'Missing ' + prov.label + ' API key' } }, 400);
  }

  if (path === '/sandbox/create') {
    const made = await sandboxCreate(prov, key, body.timeoutSec, body.template);
    if (made.error) return jsonResponse({ error: { message: made.error } }, 502);
    return jsonResponse(Object.assign({ provider: body.sandboxProvider || 'e2b' }, made), 200);
  }

  const sandboxId = body.sandboxId;
  if (!validSandboxId(sandboxId)) {
    return jsonResponse({ error: { message: 'Invalid sandbox id' } }, 400);
  }
  if (body.domain && !validDomain(body.domain, prov)) {
    return jsonResponse({ error: { message: 'Invalid sandbox domain for ' + prov.label } }, 400);
  }

  if (path === '/sandbox/kill') {
    try {
      await fetch(prov.api + '/sandboxes/' + sandboxId, { method: 'DELETE', headers: { 'X-API-Key': key } });
    } catch (e) { console.error('SANDBOX KILL FAILED:', e.message); }
    return jsonResponse({ ok: true }, 200);
  }

  if (path === '/sandbox/keepalive') {
    const secs = Math.min(Math.max(body.timeoutSec || SANDBOX_DEFAULT_TIMEOUT, 30), SANDBOX_MAX_TIMEOUT);
    try {
      const res = await fetch(prov.api + '/sandboxes/' + sandboxId + '/timeout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': key },
        body: JSON.stringify({ timeout: secs }),
      });
      // Report the real outcome: a keepalive that silently no-ops is how
      // sandboxes end up billing after the user has walked away.
      if (!res.ok) return jsonResponse({ ok: false, status: res.status }, 200);
    } catch (e) {
      return jsonResponse({ ok: false, error: e.message }, 200);
    }
    return jsonResponse({ ok: true, timeout: secs }, 200);
  }

  if (path === '/sandbox/exec') {
    const code = typeof body.code === 'string' ? body.code : '';
    if (!code) return jsonResponse({ error: { message: 'Missing code' } }, 400);
    const language = body.language === 'bash' ? 'bash' : 'python';

    // Base64 the payload and decode it inside the sandbox. Model-written code is
    // full of quotes, newlines and backslashes; this sidesteps shell quoting
    // entirely without needing a multipart file upload first.
    const encoded = btoa(unescape(encodeURIComponent(code)));
    const file = language === 'bash' ? '/tmp/omni_run.sh' : '/tmp/omni_run.py';
    const runner = language === 'bash' ? 'bash' : 'python3';
    const script = 'echo ' + encoded + ' | base64 -d > ' + file + ' && ' + runner + ' ' + file;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), Math.min(body.timeoutMs || EXEC_MAX_MS, EXEC_MAX_MS));

    const stdoutChunks = [];
    const stderrChunks = [];
    let stdoutLen = 0;
    let stderrLen = 0;
    let exitCode = null;
    let tailErr = '';
    const started = Date.now();

    try {
      const headers = {
        'Content-Type': 'application/connect+json',
        'connect-protocol-version': '1',
        'X-API-Key': key,
      };
      if (body.token) headers['X-Access-Token'] = body.token;

      const res = await fetch(envdBase(sandboxId, body.domain, prov) + '/process.Process/Start', {
        method: 'POST',
        headers,
        body: connectFrame({
          process: { cmd: '/bin/bash', args: ['-lc', script], envs: {}, cwd: '/home/user' },
        }),
        signal: controller.signal,
      });

      if (!res.ok) {
        clearTimeout(timer);
        const errText = await res.text().catch(() => res.statusText);
        return jsonResponse({ error: { message: 'Sandbox exec failed (' + res.status + '): ' + errText.slice(0, 300) } }, 502);
      }

      await readConnectStream(res, (flags, msg) => {
        if (flags & 0x02) {
          if (msg.error) tailErr += '\n[stream error] ' + (msg.error.message || JSON.stringify(msg.error));
          return;
        }
        const ev = msg.event || (msg.result && msg.result.event) || null;
        if (!ev) return;
        if (ev.data) {
          // stdout/stderr are protobuf `bytes`, so Connect JSON delivers base64.
          if (ev.data.stdout && stdoutLen < OUTPUT_CAP) {
            const b = b64bytes(ev.data.stdout);
            stdoutChunks.push(b); stdoutLen += b.length;
          }
          if (ev.data.stderr && stderrLen < OUTPUT_CAP) {
            const b = b64bytes(ev.data.stderr);
            stderrChunks.push(b); stderrLen += b.length;
          }
        } else if (ev.end) {
          exitCode = typeof ev.end.exitCode === 'number' ? ev.end.exitCode
            : (typeof ev.end.exit_code === 'number' ? ev.end.exit_code : 0);
          if (ev.end.error) tailErr += '\n' + ev.end.error;
        }
      });
    } catch (e) {
      clearTimeout(timer);
      const aborted = e.name === 'AbortError';
      const oPart = concatBytes(stdoutChunks, OUTPUT_CAP);
      const ePart = concatBytes(stderrChunks, OUTPUT_CAP);
      return jsonResponse({
        ok: false,
        stdout: utf8(oPart.bytes),
        stderr: utf8(ePart.bytes) + tailErr + (aborted ? '\n[timed out]' : '\n' + e.message),
        exit_code: null,
        timed_out: aborted,
        duration_ms: Date.now() - started,
      }, 200);
    }
    clearTimeout(timer);

    // Context is the scarce resource here — a runaway print loop would otherwise
    // fill the window and get re-sent on every subsequent agent turn.
    const outPart = concatBytes(stdoutChunks, OUTPUT_CAP);
    const errPart = concatBytes(stderrChunks, OUTPUT_CAP);

    return jsonResponse({
      ok: exitCode === 0 || exitCode === null,
      stdout: utf8(outPart.bytes),
      stderr: utf8(errPart.bytes) + tailErr,
      exit_code: exitCode,
      truncated: outPart.truncated || errPart.truncated,
      duration_ms: Date.now() - started,
    }, 200);
  }

  return jsonResponse({ error: { message: 'Unknown sandbox route' } }, 404);
}

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    if (request.method === 'GET') {
      return jsonResponse({ error: { message: 'Method not allowed. Use POST.' } }, 405);
    }

    if (request.method !== 'POST') {
      return jsonResponse({ error: { message: 'Method not allowed. Use POST.' } }, 405);
    }

    // Every POST used to fall through to the chat proxy regardless of path, so
    // sandbox routes have to be dispatched before that fallback is reached.
    const postPath = new URL(request.url).pathname;
    if (postPath.indexOf('/sandbox/') === 0) {
      return handleSandbox(postPath, request);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return jsonResponse({ error: { message: 'Invalid JSON body' } }, 400);
    }

    const { provider, model, apiKey, messages, temperature, max_tokens, baseURL, searchMode: rawSearchMode, searchBackend: rawSearchBackend, searchDepth: rawSearchDepth, tavilyKey: rawTavilyKey, effort, tools: rawTools } = body;

    if (!provider) return jsonResponse({ error: { message: 'Missing provider' } }, 400);
    if (!model) return jsonResponse({ error: { message: 'Missing model' } }, 400);
    if (!apiKey) return jsonResponse({ error: { message: 'Missing API key' } }, 400);
    if (!messages || !messages.length) return jsonResponse({ error: { message: 'Missing messages' } }, 400);

    const agentTools = sanitizeTools(rawTools);
    // Server-side search and agent tools do not mix: Gemini rejects googleSearch
    // alongside functionDeclarations outright, and the Tavily prefix injection
    // below targets the last user message, which in an agent run is a tool result.
    const searchMode = agentTools
      ? 'off'
      : (rawSearchMode === 'on' || rawSearchMode === 'off' || rawSearchMode === 'auto' ? rawSearchMode : 'auto');
    const searchBackend = rawSearchBackend === 'tavily' ? 'tavily' : 'auto';
    const searchDepth = rawSearchDepth === 'basic' || rawSearchDepth === 'advanced' ? rawSearchDepth : 'auto';
    const tavilyKey = typeof rawTavilyKey === 'string' ? rawTavilyKey.trim() : '';
    const effortTier = typeof effort === 'string' && effort ? effort : null;

    var searchResults = '';
    var searchEvent = null;

    const lastUserMsg = [...messages].reverse().find((m) => m.role === 'user');
    const lastUserText = !lastUserMsg
      ? ''
      : (typeof lastUserMsg.content === 'string'
        ? lastUserMsg.content
        : (Array.isArray(lastUserMsg.content)
          ? lastUserMsg.content.filter((p) => p && p.type === 'text' && typeof p.text === 'string').map((p) => p.text).join(' ')
          : ''));

    const nativeSearchProvider = provider === 'anthropic' || provider === 'google' || provider === 'openrouter';
    const forceTavily = searchBackend === 'tavily';
    const useTavily = !nativeSearchProvider || forceTavily;

    // A search is wanted when the mode is explicit, or when the auto
    // heuristic recognises a time-sensitive query.
    const searchRequested = searchMode === 'on' || (searchMode === 'auto' && shouldAutoSearch(lastUserText));

    if (useTavily && searchMode !== 'off' && searchRequested) {
      if (!tavilyKey) {
        searchEvent = { search: { error: 'No Tavily API key configured. Add one in Settings to use web search.', code: 'no_key' } };
      } else {
        const plan = buildSearchPlan(lastUserText, searchDepth);
        if (!plan) {
          searchEvent = { search: { error: 'No query provided for search.', code: 'no_query' } };
        } else {
          const cacheKey = searchCacheKey(plan.depth, plan.topic, plan.timeRange, plan.query);
          let curated = searchCacheGet(cacheKey);
          const cached = !!curated;
          if (!curated) {
            const outcomes = [await runTavilySearch(plan.query, tavilyKey, plan)];
            const firstCount = outcomes[0].data && Array.isArray(outcomes[0].data.results) ? outcomes[0].data.results.length : 0;
            if (firstCount < 3 && plan.alternate) {
              outcomes.push(await runTavilySearch(plan.alternate, tavilyKey, Object.assign({}, plan, { depth: 'basic' })));
            }
            curated = curateSearchResults(outcomes);
            if (curated.items.length) searchCacheSet(cacheKey, curated);
          }
          if (curated.items.length) {
            searchEvent = {
              search: {
                query: plan.query,
                sources: curated.items.map(function(r) {
                  return { title: r.title, url: r.url, domain: r.domain, date: r.date, snippet: r.snippet };
                }),
                credits: cached ? 0 : curated.credits,
                depth: plan.depth,
                cached: cached,
              },
            };
            searchResults = buildSearchContext(curated.items);
          } else {
            searchEvent = { search: curated.error || { error: 'No web results found.', code: 'no_results' } };
          }
        }
      }
    }

    async function runTavilySearch(query, apiKey, plan) {
      const controller = new AbortController();
      const timer = setTimeout(function() { controller.abort(); }, 12000);
      const body = {
        query: query,
        search_depth: plan.depth,
        chunks_per_source: plan.depth === 'advanced' ? 3 : 2,
        max_results: plan.depth === 'advanced' ? 6 : 8,
        topic: plan.topic,
        include_answer: false,
        include_raw_content: false,
        include_published_date: true,
        include_usage: true,
        safe_search: true,
      };
      if (plan.timeRange) body.time_range = plan.timeRange;
      try {
        const res = await fetch('https://api.tavily.com/search', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + apiKey,
          },
          body: JSON.stringify(body),
          signal: controller.signal,
        });
        if (!res.ok) {
          const errBody = (await res.text()).slice(0, 300);
          console.error('TAVILY FAILED:', res.status, errBody);
          return describeTavilyError(res.status);
        }
        const data = await res.json();
        return {
          data: data,
          credits: data && data.usage && typeof data.usage.credits === 'number' ? data.usage.credits : 0,
        };
      } catch (e) {
        if (e && e.name === 'AbortError') return { error: 'Web search timed out.', code: 'timeout' };
        console.error('TAVILY THREW:', e && e.message);
        return { error: 'Web search failed: ' + ((e && e.message) || 'network error'), code: 'network' };
      } finally {
        clearTimeout(timer);
      }
    }

    function describeTavilyError(status) {
      if (status === 401 || status === 403) return { error: 'Tavily rejected your API key. Check it in Settings.', code: 'auth' };
      if (status === 429) return { error: 'Tavily rate limit reached. Try again in a moment.', code: 'rate_limit' };
      if (status === 432 || status === 433) return { error: 'Tavily plan or credit limit reached.', code: 'plan_limit' };
      if (status === 400 || status === 422) return { error: 'Tavily rejected the search request.', code: 'bad_request' };
      if (status >= 500) return { error: 'Tavily is temporarily unavailable.', code: 'provider' };
      return { error: 'Tavily request failed (HTTP ' + status + ').', code: 'provider' };
    }

    function buildSearchPlan(raw, depthPref) {
      const cleaned = String(raw || '').replace(/\[Attached:[^\]]*\]/g, '').replace(/\s+/g, ' ').trim().slice(0, 300);
      if (!cleaned) return null;
      const alternate = focusedSearchQuery(cleaned);
      const freshness = detectFreshness(cleaned);
      const depth = depthPref === 'advanced'
        ? 'advanced'
        : depthPref === 'basic'
          ? 'basic'
          : (isComplexQuery(cleaned) ? 'advanced' : 'basic');
      return {
        query: cleaned,
        alternate: alternate && alternate !== cleaned ? alternate : '',
        depth: depth,
        topic: freshness.topic,
        timeRange: freshness.timeRange,
      };
    }

    function focusedSearchQuery(text) {
      const compact = text
        .replace(/^(please|hey|hi|hello|can you|could you|would you|tell me|explain|describe|what is|what's|whats|who is|who's|where is|when is|why is|how do|how does|how can|how to)\b/i, ' ')
        .replace(/\b(the|a|an|and|or|of|to|for|with|on|in|at|by|from|about|me|my|i|we|you|is|are|was|were|do|does|did|can|could|would|should|please)\b/gi, ' ')
        .replace(/\s+/g, ' ')
        .trim();
      return compact.length >= 6 ? compact.slice(0, 160) : '';
    }

    function isComplexQuery(text) {
      const words = text.split(/\s+/).length;
      return words > 30 || text.length > 200 ||
        /\b(compare|comparison|versus|difference between|pros and cons|trade-?offs?|analy[sz]e|research|benchmark|review of)\b/i.test(text);
    }

    function detectFreshness(text) {
      const lower = text.toLowerCase();
      // An explicit window is honoured. A news topic already biases toward
      // recent coverage, so forcing one there would drop older-but-relevant
      // articles that the query asked for.
      let timeRange = '';
      if (/\b(today|tonight|right now|currently|this morning|this afternoon)\b/.test(lower)) timeRange = 'day';
      else if (/\b(this week|yesterday|last week|past week)\b/.test(lower)) timeRange = 'week';
      else if (/\b(this month|last month|past month)\b/.test(lower)) timeRange = 'month';
      else if (/\b(this year|last year|past year)\b/.test(lower)) timeRange = 'year';
      const finance = /\b(stock|stocks|share price|market cap|earnings|crypto|bitcoin|ethereum|exchange rate)\b/.test(lower);
      if (finance) return { topic: 'finance', timeRange: timeRange };
      const news = /\b(news|breaking|announced|announcement|launched|released|election|weather|forecast)\b/.test(lower);
      if (news) return { topic: 'news', timeRange: timeRange };
      return { topic: 'general', timeRange: timeRange };
    }

    function normalizeSearchUrl(raw) {
      try {
        const u = new URL(raw);
        u.hash = '';
        const drop = [];
        u.searchParams.forEach(function(v, k) {
          if (/^(utm_|gclid$|fbclid$|mc_cid$|mc_eid$|ref$|ref_src$)/i.test(k)) drop.push(k);
        });
        for (let i = 0; i < drop.length; i++) u.searchParams.delete(drop[i]);
        return u.toString().replace(/\/$/, '');
      } catch (_) {
        return String(raw || '').trim();
      }
    }

    function curateSearchResults(outcomes) {
      const items = [];
      const seen = new Set();
      const perDomain = {};
      let credits = 0;
      let firstError = null;
      for (let oi = 0; oi < outcomes.length; oi++) {
        const o = outcomes[oi];
        if (!o) continue;
        if (typeof o.credits === 'number') credits += o.credits;
        if (!o.data) {
          if (o.error && !firstError) firstError = { error: o.error, code: o.code || 'provider' };
          continue;
        }
        const results = Array.isArray(o.data.results) ? o.data.results : [];
        for (let ri = 0; ri < results.length; ri++) {
          const r = results[ri];
          if (!r || !r.url) continue;
          const normalized = normalizeSearchUrl(r.url);
          if (!normalized || seen.has(normalized)) continue;
          let domain = '';
          try { domain = new URL(normalized).hostname.replace(/^www\./, ''); } catch (_) { domain = ''; }
          if (domain && (perDomain[domain] || 0) >= 2) continue;
          seen.add(normalized);
          if (domain) perDomain[domain] = (perDomain[domain] || 0) + 1;
          items.push({
            title: String(r.title || 'Untitled').slice(0, 160),
            url: r.url,
            domain: domain,
            date: String(r.published_date || '').slice(0, 10),
            snippet: String(r.content || '').slice(0, 240),
            content: String(r.content || '').slice(0, 1500),
            score: Number(r.score) || 0,
          });
          if (items.length >= 8) break;
        }
        if (items.length >= 8) break;
      }
      items.sort(function(a, b) { return b.score - a.score; });
      return { items: items, credits: credits, error: firstError };
    }

    function buildSearchContext(items) {
      const budget = 10000;
      const perSource = Math.max(500, Math.min(1200, Math.floor((budget - 600) / Math.max(items.length, 1))));
      const blocks = items.map(function(r, i) {
        let entry = '[' + (i + 1) + '] ' + r.title;
        if (r.url) entry += '\n   Source: ' + r.url;
        if (r.date) entry += '\n   Date: ' + r.date;
        entry += '\n   ' + String(r.content || r.snippet || '').slice(0, perSource);
        return entry;
      });
      return 'Web search results (current, sourced):\n\n' + blocks.join('\n\n') +
        '\n\nAnswer the user query using these results. Cite sources inline as [1], [2], etc. Do not mention that you used web search unless the user asks. Prefer recent results for time-sensitive questions. If the results do not cover the question, say so clearly instead of guessing.';
    }

    function shouldAutoSearch(text) {
      const q = String(text || '').replace(/\[Attached:[^\]]*\]/g, '').replace(/\s+/g, ' ').trim().slice(0, 600);
      if (!q) return false;
      if (q.split(/\s+/).length <= 3 && /^(hi|hello|hey|thanks|thank you|ok|okay|cool|nice|yes|no|continue|go on)\b/i.test(q)) return false;
      const lower = q.toLowerCase();
      const TIME = /\b(today|tonight|yesterday|tomorrow|this week|this month|this year|right now|currently|now)\b/;
      const FRESH = /\b(latest|recent|recently|breaking|news|announced|announcement|launched|launch|released|release)\b/;
      const VOLATILE = /\b(price|prices|pricing|cost|stock|stocks|share price|score|scores|election|elections|weather|forecast|odds|ranking|rankings)\b/;
      const DATES = /\b(january|february|march|april|may|june|july|august|september|october|november|december)\b|\b20\d\d\b|\bas of\b/;
      const STEMS = /\b(who won|what happened|how is|how are|where is|what's the score|what is the score|what's new|whats new)\b/;
      // Deliberately narrow. A coding client asks "how do I", "fix this error"
      // and "what is the best way to" constantly, and each of those would burn
      // a Tavily credit on a question the model can answer on its own. These
      // are the intents that are almost always a lookup.
      const COMPARE = /\b(vs\.?|versus|compare|comparison|difference between|better than|alternative to|alternatives to)\b/;
      const RECOMMEND = /\b(recommend|recommended|worth it|should i buy)\b/;
      const LOOKUP = /\b(who is|who was|when did|when was|how old is)\b/;
      return TIME.test(lower) || FRESH.test(lower) || VOLATILE.test(lower) || DATES.test(lower) ||
        STEMS.test(lower) || COMPARE.test(lower) || RECOMMEND.test(lower) || LOOKUP.test(lower);
    }

    const temp = typeof temperature === 'number' ? temperature : 0.7;
    const maxOut = typeof max_tokens === 'number' ? max_tokens : 2048;
    // Whitelist, not a spread: this is what keeps client-injected vendor fields
    // out of provider requests. Tool fields have to be listed or agent turns are
    // silently flattened into unusable {role, content} pairs.
    const safeMessages = messages.map((m) => {
      const out = { role: m.role, content: m.content };
      if (Array.isArray(m.tool_calls)) out.tool_calls = m.tool_calls;
      if (m.tool_call_id) out.tool_call_id = m.tool_call_id;
      if (m.name) out.name = m.name;
      return out;
    });

    function stripImageParts(content) {
      if (!Array.isArray(content)) return content;
      const kept = content.filter((p) => !p || p.type !== 'image_url');
      if (!kept.length) return '[Image attachment not supported by this model \u2014 removed]';
      return kept;
    }
    if (searchResults) {
      for (var si = safeMessages.length - 1; si >= 0; si--) {
        if (safeMessages[si].role !== 'user') continue;
        var uc = safeMessages[si].content;
        if (typeof uc === 'string') {
          safeMessages[si].content = searchResults + '\n\n=== USER QUERY ===\n\n' + uc;
        } else if (Array.isArray(uc)) {
          // A message with attachments is a multipart array. Injecting into the
          // array itself keeps the images intact; the old string-only check
          // silently dropped search results for every attachment turn.
          var textPart = null;
          for (var pi = 0; pi < uc.length; pi++) {
            if (uc[pi] && uc[pi].type === 'text' && typeof uc[pi].text === 'string') { textPart = uc[pi]; break; }
          }
          if (textPart) {
            textPart.text = searchResults + '\n\n=== USER QUERY ===\n\n' + textPart.text;
          } else {
            uc.unshift({ type: 'text', text: searchResults + '\n\n=== USER QUERY ===\n\n' });
          }
        }
        break;
      }
    }

    const deepseekMessages = provider === 'deepseek'
      ? safeMessages.map((m) => Object.assign({}, m, { content: stripImageParts(m.content) }))
      : safeMessages;

    const { readable, writable } = new TransformStream();
    const writer = writable.getWriter();
    const encoder = new TextEncoder();

    const streamPromise = (async () => {
      try {
        if (searchEvent) {
          await writer.write(encoder.encode(JSON.stringify(searchEvent) + '\n'));
        }
        switch (provider) {
          case 'openai': {
            const url = PROVIDER_URLS.openai;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'deepinfra': {
            const url = PROVIDER_URLS.deepinfra;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'nvidia': {
            const url = PROVIDER_URLS.nvidia;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'moonshot': {
            const url = PROVIDER_URLS.moonshot;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'gmicloud': {
            const url = PROVIDER_URLS.gmicloud;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'together': {
            const url = PROVIDER_URLS.together;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'deepseek': {
            const url = PROVIDER_URLS.deepseek;
            const reqBody = buildOpenAIRequest(model, deepseekMessages, temp, maxOut, effortTier, agentTools);
            if (effortTier) reqBody.thinking = { type: 'enabled' };
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'xai': {
            const url = PROVIDER_URLS.xai;
            // xAI removed live search: `search_parameters` now returns 410 Gone,
            // and the Chat Completions endpoint accepts function tools only, so
            // the built-in `web_search` tool is not available here either. xAI
            // searches through Tavily like the other OpenAI-compatible
            // providers.
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'openrouter': {
            const url = PROVIDER_URLS.openrouter;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, null, agentTools);
            if (effortTier) reqBody.reasoning = { effort: effortTier };
            const nativeSearch = !forceTavily && searchMode !== 'off';
            if (nativeSearch) {
              reqBody.tools = (reqBody.tools || []).concat([{ type: 'openrouter:web_search', parameters: { max_results: 5, max_uses: 5 } }]);
            }
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer, nativeSearch);
            break;
          }
          case 'anthropic': {
            const effortBudget = ANTHROPIC_EFFORT_BUDGET[effortTier] || null;
            const searchTool = !forceTavily && searchMode !== 'off' ? anthropicSearchTool(model) : null;
            const reqBody = buildAnthropicRequest(model, safeMessages, temp, maxOut, effortBudget, searchTool, agentTools);
            await streamAnthropic(apiKey, reqBody, encoder, writer);
            break;
          }
          case 'google': {
            const effortBudget = GOOGLE_EFFORT_BUDGET[effortTier] || null;
            const reqBody = buildGoogleRequest(model, safeMessages, temp, maxOut, effortBudget, agentTools);
            const useNativeSearch = !forceTavily && searchMode !== 'off' && model.indexOf('gemini') === 0;
            if (useNativeSearch) {
              if (!reqBody.tools) reqBody.tools = [{ googleSearch: {} }];
              else reqBody.tools.push({ googleSearch: {} });
            }
            await streamGoogle(model, apiKey, reqBody, encoder, writer, useNativeSearch);
            break;
          }
          case 'custom': {
            if (!baseURL) {
              await writer.write(encoder.encode(JSON.stringify({ error: { message: 'Missing base URL for custom provider' } }) + '\n'));
              break;
            }
            if (!isSafeCustomURL(baseURL)) {
              await writer.write(encoder.encode(JSON.stringify({ error: { message: 'Custom base URL must be https and must not point at a private or loopback address' } }) + '\n'));
              break;
            }
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            await streamOpenAICompatible(baseURL, apiKey, reqBody, encoder, writer);
            break;
          }
          default:
            await writer.write(encoder.encode(JSON.stringify({ error: { message: `Unknown provider: ${provider}` } }) + '\n'));
        }
      } catch (err) {
        await writer.write(encoder.encode(JSON.stringify({ error: { message: err.message || 'Unexpected error' } }) + '\n'));
      }
      await writer.close();
    })();

    ctx.waitUntil(streamPromise);
    return streamToNDJSON(readable);
  },
};

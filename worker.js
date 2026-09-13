// Omni AI Chat Proxy — Cloudflare Worker
// Deploy: Cloudflare Dashboard → Workers & Pages → Create → paste → Deploy
// No install. No build. No dependencies.

const PROVIDER_URLS = {
  openai: 'https://api.openai.com/v1/chat/completions',
  cerebras: 'https://api.cerebras.ai/v1/chat/completions',
  nvidia: 'https://integrate.api.nvidia.com/v1/chat/completions',
  moonshot: 'https://api.moonshot.ai/v1/chat/completions',
  groq: 'https://api.groq.com/openai/v1/chat/completions',
  together: 'https://api.together.xyz/v1/chat/completions',
  deepseek: 'https://api.deepseek.com/chat/completions',
  xai: 'https://api.x.ai/v1/chat/completions',
  openrouter: 'https://openrouter.ai/api/v1/chat/completions',
  anthropic: 'https://api.anthropic.com/v1/messages',
};

const ALLOWED_ORIGINS = [
  'https://claude-like-ai-wrapper.wimmboo.workers.dev',
];

const MONTHLY_CAP = 900;
const IP_CAP = 200;

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
  return { type: 'web_search_20260318', name: 'web_search', max_uses: 5 };
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
    }
    if (parsed.usage) {
      await writer.write(encoder.encode(JSON.stringify({
        usage: {
          prompt_tokens: parsed.usage.prompt_tokens,
          completion_tokens: parsed.usage.completion_tokens,
          total_tokens: parsed.usage.total_tokens,
        }
      }) + '\n'));
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

        if (type === 'content_block_start' && parsed.content_block && parsed.content_block.type === 'web_search_tool_result') {
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
            await writer.write(encoder.encode(JSON.stringify({
              usage: {
                prompt_tokens: parsed.usage.input_tokens,
                completion_tokens: parsed.usage.output_tokens,
              },
              stop_reason: stopReason,
            }) + '\n'));
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
            }
          }) + '\n'));
        }

        const candidates = parsed.candidates;
        if (candidates && candidates.length) {
          for (const candidate of candidates) {
            collectChunks(candidate.groundingMetadata);
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

const E2B_API = 'https://api.e2b.app';
const E2B_DEFAULT_DOMAIN = 'e2b.app';
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
function validDomain(d) {
  // Matches every host shape in E2B's own proxy fixtures — the bare domain it
  // returns ("e2b.app"), regional subdomains ("demo.e2b.app") and their test
  // domain ("e2b-test.app") — while refusing an arbitrary attacker-chosen host.
  return typeof d === 'string' && /^([a-z0-9-]+\.)*e2b(-[a-z0-9]+)?\.(app|dev)$/.test(d);
}

function envdBase(sandboxId, domain) {
  const host = validDomain(domain) ? domain : E2B_DEFAULT_DOMAIN;
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

async function e2bCreate(key, timeoutSec, template) {
  const res = await fetch(E2B_API + '/sandboxes', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': key },
    body: JSON.stringify({
      templateID: template || 'base',
      timeout: Math.min(Math.max(timeoutSec || SANDBOX_DEFAULT_TIMEOUT, 30), SANDBOX_MAX_TIMEOUT),
    }),
  });
  const text = await res.text();
  if (!res.ok) return { error: 'E2B create failed (' + res.status + '): ' + text.slice(0, 300) };
  let data = {};
  try { data = JSON.parse(text); } catch (_) { return { error: 'E2B returned unparseable JSON on create' }; }
  const sandboxId = data.sandboxID || data.sandboxId || '';
  if (!validSandboxId(sandboxId)) return { error: 'E2B returned no usable sandbox id' };
  return {
    sandboxId,
    domain: validDomain(data.domain) ? data.domain : E2B_DEFAULT_DOMAIN,
    token: data.envdAccessToken || '',
  };
}

async function handleSandbox(path, request) {
  let body;
  try { body = await request.json(); } catch (_) {
    return jsonResponse({ error: { message: 'Invalid JSON body' } }, 400);
  }
  const key = body.e2bKey;
  if (!key || typeof key !== 'string') {
    return jsonResponse({ error: { message: 'Missing E2B API key' } }, 400);
  }

  if (path === '/sandbox/create') {
    const made = await e2bCreate(key, body.timeoutSec, body.template);
    if (made.error) return jsonResponse({ error: { message: made.error } }, 502);
    return jsonResponse(made, 200);
  }

  const sandboxId = body.sandboxId;
  if (!validSandboxId(sandboxId)) {
    return jsonResponse({ error: { message: 'Invalid sandbox id' } }, 400);
  }
  if (body.domain && !validDomain(body.domain)) {
    return jsonResponse({ error: { message: 'Invalid sandbox domain' } }, 400);
  }

  if (path === '/sandbox/kill') {
    try {
      await fetch(E2B_API + '/sandboxes/' + sandboxId, { method: 'DELETE', headers: { 'X-API-Key': key } });
    } catch (e) { console.error('E2B KILL FAILED:', e.message); }
    return jsonResponse({ ok: true }, 200);
  }

  if (path === '/sandbox/keepalive') {
    const secs = Math.min(Math.max(body.timeoutSec || SANDBOX_DEFAULT_TIMEOUT, 30), SANDBOX_MAX_TIMEOUT);
    try {
      const res = await fetch(E2B_API + '/sandboxes/' + sandboxId + '/timeout', {
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

      const res = await fetch(envdBase(sandboxId, body.domain) + '/process.Process/Start', {
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
      const url = new URL(request.url);
      if (url.pathname === '/usage') {
        const monthKey = 'tavily:' + new Date().toISOString().slice(0, 7);
        const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
        let globalUsed = 0;
        let perIpUsed = 0;
        try {
          const g = await env.SEARCH_COUNTER.get(monthKey);
          globalUsed = g ? parseInt(g, 10) : 0;
          const p = await env.SEARCH_COUNTER.get(monthKey + ':' + ip);
          perIpUsed = p ? parseInt(p, 10) : 0;
        } catch (e) { console.error('KV USAGE READ FAILED:', e.message); }
        return jsonResponse({
          month: monthKey.slice(7),
          perIp: { used: perIpUsed, limit: IP_CAP },
          global: { used: globalUsed, limit: MONTHLY_CAP },
        }, 200);
      }
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

    const { provider, model, apiKey, messages, temperature, max_tokens, thinking, baseURL, web_search, searchMode: rawSearchMode, effort, tools: rawTools } = body;

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
      : (rawSearchMode === 'on' || rawSearchMode === 'off' || rawSearchMode === 'auto' ? rawSearchMode : (web_search ? 'on' : 'auto'));
    const effortTier = typeof effort === 'string' && effort ? effort : null;

    var searchResults = '';
    var searchEvent = null;

    const lastUserMsg = [...messages].reverse().find((m) => m.role === 'user');
    const lastUserText = lastUserMsg ? (typeof lastUserMsg.content === 'string' ? lastUserMsg.content : '') : '';

    const groqNative = provider === 'groq' && (model.indexOf('groq/') === 0 || /^openai\/gpt-oss-(20b|120b)$/.test(model));
    const isNativeSearch = provider === 'anthropic' || provider === 'google' || provider === 'openrouter' || groqNative;

    if (!isNativeSearch && searchMode !== 'off') {
      const force = searchMode === 'on';
      if (force || shouldAutoSearch(lastUserText)) {
        const reqOrigin = request.headers.get('Origin') || request.headers.get('Referer') || '';
        const originAllowed = !reqOrigin || ALLOWED_ORIGINS.some(function(o) { return reqOrigin.indexOf(o) === 0; });
        if (originAllowed) {
          const monthKey = 'tavily:' + new Date().toISOString().slice(0, 7);
          const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
          const ipKey = monthKey + ':' + ip;
          let counter = 0;
          let ipCounter = 0;
          try {
            const val = await env.SEARCH_COUNTER.get(monthKey);
            counter = val ? parseInt(val, 10) : 0;
            const ipVal = await env.SEARCH_COUNTER.get(ipKey);
            ipCounter = ipVal ? parseInt(ipVal, 10) : 0;
          } catch (e) { console.error('KV READ FAILED:', e.message); }
          if (counter >= MONTHLY_CAP) {
            console.error('TAVILY CAP HIT:', monthKey, counter);
            searchEvent = { search: { error: 'Monthly search cap reached (' + MONTHLY_CAP + ').' } };
          } else if (ipCounter >= IP_CAP) {
            console.error('TAVILY IP CAP HIT:', ipKey, ipCounter);
            searchEvent = { search: { error: 'Monthly search limit reached (' + IP_CAP + ' per IP).' } };
          } else {
            const queries = buildSearchQueries(lastUserText);
            if (queries.length) {
              const outcomes = await Promise.all(queries.map(function(q) {
                return runTavilySearch(q, env.TAVILY_API_KEY).then(function(data) {
                  return data ? { data: data } : { error: 'Search provider error (check TAVILY_API_KEY).' };
                }).catch(function(e) {
                  console.error('TAVILY THREW:', e.message);
                  return { error: 'Search failed: ' + (e.message || 'unknown error') };
                });
              }));
              const merged = [];
              const seenUrls = new Set();
              let searchSummary = '';
              for (var oi = 0; oi < outcomes.length; oi++) {
                if (!outcomes[oi].data) continue;
                if (outcomes[oi].data.answer && !searchSummary) {
                  searchSummary = String(outcomes[oi].data.answer).slice(0, 1400);
                }
                var dataResults = outcomes[oi].data.results || [];
                for (var ri = 0; ri < dataResults.length; ri++) {
                  var r = dataResults[ri];
                  if (!r || !r.url || seenUrls.has(r.url)) continue;
                  seenUrls.add(r.url);
                  merged.push(r);
                  if (merged.length >= 10) break;
                }
              }
              ctx.waitUntil(
                (async function() {
                  try {
                    await env.SEARCH_COUNTER.put(monthKey, String(counter + queries.length), { expirationTtl: 3456000 });
                    await env.SEARCH_COUNTER.put(ipKey, String(ipCounter + queries.length), { expirationTtl: 3456000 });
                  }
                  catch (e) { console.error('KV WRITE FAILED:', e.message); }
                })()
              );
              if (merged.length) {
                const sources = merged.map(function(r) {
                  return {
                    title: (r.title || 'Untitled').slice(0, 160),
                    url: r.url || '',
                    date: (r.published_date || '').slice(0, 10),
                    snippet: (r.content || '').slice(0, 240),
                  };
                });
                searchEvent = { search: { query: queries[0], sources: sources } };
                searchResults = 'Web search results (current, sourced):\n\n' +
                  (searchSummary ? 'Search summary:\n' + searchSummary + '\n\n' : '') +
                  merged.map(function(r, i) {
                    var entry = '[' + (i + 1) + '] ' + (r.title || 'Untitled');
                    if (r.url) entry += '\n   Source: ' + r.url;
                    if (r.published_date) entry += '\n   Date: ' + r.published_date.slice(0, 10);
                    entry += '\n   ' + (r.content || '');
                    return entry;
                  }).join('\n\n') +
                  '\n\nAnswer the user query using these results. Cite sources inline as [1], [2], etc. Prefer recent results for time-sensitive questions. If the results do not cover the question, say so clearly instead of guessing.';
              } else {
                var failedOutcome = null;
                for (var ei = 0; ei < outcomes.length; ei++) { if (outcomes[ei].error) { failedOutcome = outcomes[ei].error; break; } }
                searchEvent = { search: { error: failedOutcome || 'No web results found.' } };
              }
            } else {
              searchEvent = { search: { error: 'No query provided for search.' } };
            }
          }
        } else {
          console.error('SEARCH ORIGIN BLOCKED:', reqOrigin);
          searchEvent = { search: { error: 'Search blocked for this origin.' } };
        }
      }
    }

    async function runTavilySearch(query, apiKey) {
      const body = {
        api_key: apiKey,
        query: query,
        search_depth: 'advanced',
        max_results: 8,
        include_answer: true,
        include_raw_content: false,
      };
      if (/(news|latest|today|tonight|breaking|released|launched|announced|update|updated|this week|this month|recap|roundup|score|price|stock|election|weather)/i.test(query)) {
        body.topic = 'news';
        body.days = 7;
      }
      const res = await fetch('https://api.tavily.com/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const errBody = (await res.text()).slice(0, 300);
        console.error('TAVILY FAILED:', res.status, errBody);
        return null;
      }
      return res.json();
    }

    function buildSearchQueries(raw) {
      const cleaned = String(raw || '').replace(/\[Attached:[^\]]*\]/g, '').replace(/\s+/g, ' ').trim().slice(0, 400);
      if (!cleaned) return [];
      const compact = cleaned
        .replace(/\b(what|whats|what's|whatre|what're|who|whos|who's|wheres|where|when|why|how|is|are|was|were|do|does|did|can|could|would|should|shall|will|tell me|explain|describe|about|please|the|a|an|and|or|of|to|for|with|on|in|at|by|me|my|i want|i need|know|think|dont|don't|doesnt|doesn't)\b/gi, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 200);
      if (cleaned.length > 20 && compact && compact.length >= 6 && compact !== cleaned) return [cleaned, compact];
      return [cleaned];
    }

    function shouldAutoSearch(text) {
      const q = String(text || '').replace(/\[Attached:[^\]]*\]/g, '').replace(/\s+/g, ' ').trim().slice(0, 600);
      if (!q) return false;
      const lower = q.toLowerCase();
      const TIME = /\b(today|tonight|yesterday|tomorrow|this week|this month|this year|right now|currently|now)\b/;
      const FRESH = /\b(latest|recent|recently|breaking|news|update|updates|updated|announced|announcement|launched|launch|released|release)\b/;
      const VOLATILE = /\b(price|prices|pricing|cost|stock|stocks|share price|score|scores|result|results|election|elections|weather|forecast|odds|ranking|rankings)\b/;
      const DATES = /\b(january|february|march|april|may|june|july|august|september|october|november|december)\b|\b20\d\d\b|\bas of\b/;
      const STEMS = /\b(who won|what happened|how is|how are|where is|what's the score|what is the score|what's new|whats new)\b/;
      return TIME.test(lower) || FRESH.test(lower) || VOLATILE.test(lower) || DATES.test(lower) || STEMS.test(lower);
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
        if (safeMessages[si].role === 'user') {
          var uc = safeMessages[si].content;
          if (typeof uc === 'string') {
            safeMessages[si].content = searchResults + '\n\n=== USER QUERY ===\n\n' + uc;
          }
          break;
        }
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
          case 'cerebras': {
            const url = PROVIDER_URLS.cerebras;
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
          case 'groq': {
            const url = PROVIDER_URLS.groq;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            if (searchMode !== 'off' && /^openai\/gpt-oss-(20b|120b)$/.test(model)) {
              reqBody.tools = (reqBody.tools || []).concat([{ type: 'browser_search' }]);
            }
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
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier, agentTools);
            if (searchMode === 'on') reqBody.search_parameters = { mode: 'on' };
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'openrouter': {
            const url = PROVIDER_URLS.openrouter;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, null, agentTools);
            if (effortTier) reqBody.reasoning = { effort: effortTier };
            if (searchMode !== 'off') {
              reqBody.tools = (reqBody.tools || []).concat([{ type: 'openrouter:web_search', parameters: { max_results: 5, max_uses: 5 } }]);
            }
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer, searchMode !== 'off');
            break;
          }
          case 'anthropic': {
            const effortBudget = ANTHROPIC_EFFORT_BUDGET[effortTier] || null;
            const searchTool = searchMode !== 'off' ? anthropicSearchTool(model) : null;
            const reqBody = buildAnthropicRequest(model, safeMessages, temp, maxOut, effortBudget, searchTool, agentTools);
            await streamAnthropic(apiKey, reqBody, encoder, writer);
            break;
          }
          case 'google': {
            const effortBudget = GOOGLE_EFFORT_BUDGET[effortTier] || null;
            const reqBody = buildGoogleRequest(model, safeMessages, temp, maxOut, effortBudget, agentTools);
            const useNativeSearch = searchMode !== 'off' && model.indexOf('gemini') === 0;
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

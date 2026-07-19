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
  'https://claude-like-ai-wrapper.rafandra-aydin.workers.dev',
];

const MONTHLY_CAP = 900;

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

function buildOpenAIRequest(model, messages, temperature, maxTokens) {
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

function buildAnthropicRequest(model, messages, temperature, maxTokens, thinking) {
  const systemMessages = messages.filter((m) => m.role === 'system').map((m) => m.content);
  const chatMessages = messages.filter((m) => m.role !== 'system');

  const body = {
    model,
    messages: chatMessages.map((m) => ({
      role: m.role,
      content: translateToAnthropicContent(m.content),
    })),
    max_tokens: maxTokens,
    temperature,
    stream: true,
  };
  if (systemMessages.length) {
    body.system = systemMessages.map((s) => ({ type: 'text', text: s }));
    if (body.system.length === 1) body.system = body.system[0].text;
  }
  if (thinking) {
    body.thinking = { type: 'enabled', budget_tokens: 4096 };
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

function buildGoogleRequest(model, messages, temperature, maxTokens, thinking) {
  const systemMsg = messages.filter((m) => m.role === 'system');
  const chatMsg = messages.filter((m) => m.role !== 'system');

  const contents = chatMsg.map((m) => ({
    role: m.role === 'assistant' ? 'model' : 'user',
    parts: translateToGoogleParts(m.content),
  }));

  const body = {
    contents,
    generationConfig: {
      temperature,
      maxOutputTokens: maxTokens,
    },
  };
  if (systemMsg.length) {
    body.systemInstruction = {
      parts: [{ text: systemMsg.map((s) => s.content).join('\n\n') }],
    };
  }
  if (thinking) {
    body.generationConfig.thinkingConfig = { includeThoughts: true };
  }
  return body;
}

async function streamOpenAICompatible(url, apiKey, requestBody, encoder, writer) {
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
        const parsed = JSON.parse(data);
        const choices = parsed.choices;
        if (choices && choices[0]) {
          const delta = choices[0].delta;
          const content = delta ? (delta.content || delta.text || '') : '';

          if (content) {
            await writer.write(encoder.encode(JSON.stringify({ delta: content }) + '\n'));
          }

          const reasoning = delta ? delta.reasoning_content : null;
          if (reasoning) {
            await writer.write(encoder.encode(JSON.stringify({ thinking_delta: reasoning }) + '\n'));
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
      } catch (_) { /* skip */ }
    }
  }
  if (buffer.trim().startsWith('data: ') && buffer.trim().slice(6) !== '[DONE]') {
    try {
      const parsed = JSON.parse(buffer.trim().slice(6));
      const delta = parsed.choices?.[0]?.delta;
      if (delta?.content) {
        await writer.write(encoder.encode(JSON.stringify({ delta: delta.content }) + '\n'));
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
    } catch (_) { /* skip */ }
  }
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

        if (type === 'content_block_delta') {
          const deltaType = parsed.delta?.type;
          if (deltaType === 'text_delta') {
            await writer.write(encoder.encode(JSON.stringify({ delta: parsed.delta.text }) + '\n'));
          } else if (deltaType === 'thinking_delta') {
            await writer.write(encoder.encode(JSON.stringify({ thinking_delta: parsed.delta.thinking }) + '\n'));
          } else if (deltaType === 'input_json_delta') {
            await writer.write(encoder.encode(JSON.stringify({ delta: parsed.delta.partial_json }) + '\n'));
          }
        } else if (type === 'message_delta') {
          if (parsed.usage) {
            await writer.write(encoder.encode(JSON.stringify({
              usage: {
                prompt_tokens: parsed.usage.input_tokens,
                completion_tokens: parsed.usage.output_tokens,
              },
              stop_reason: parsed.delta?.stop_reason,
            }) + '\n'));
          }
        } else if (type === 'error') {
          await writer.write(encoder.encode(JSON.stringify({ error: { message: parsed.error?.message || 'Anthropic error' } }) + '\n'));
        }
      } catch (_) { /* skip */ }
    }
  }
}

async function streamGoogle(model, apiKey, requestBody, encoder, writer) {
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
            const parts = candidate.content?.parts;
            if (parts) {
              for (const part of parts) {
                if (part.text) {
                  const event = part.thought
                    ? { thinking_delta: part.text }
                    : { delta: part.text };
                  await writer.write(encoder.encode(JSON.stringify(event) + '\n'));
                }
              }
            }
          }
        }
      } catch (_) { /* skip */ }
    }
  }
}

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    if (request.method !== 'POST') {
      return jsonResponse({ error: { message: 'Method not allowed. Use POST.' } }, 405);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return jsonResponse({ error: { message: 'Invalid JSON body' } }, 400);
    }

    const { provider, model, apiKey, messages, temperature, max_tokens, thinking, baseURL, web_search } = body;

    if (!provider) return jsonResponse({ error: { message: 'Missing provider' } }, 400);
    if (!model) return jsonResponse({ error: { message: 'Missing model' } }, 400);
    if (!apiKey) return jsonResponse({ error: { message: 'Missing API key' } }, 400);
    if (!messages || !messages.length) return jsonResponse({ error: { message: 'Missing messages' } }, 400);

    var searchResults = '';
    if (web_search && !(provider === 'groq' && model && model.indexOf('groq/') === 0)) {
      const reqOrigin = request.headers.get('Origin') || request.headers.get('Referer') || '';
      const originAllowed = !reqOrigin || ALLOWED_ORIGINS.some(function(o) { return reqOrigin.indexOf(o) === 0; });
      if (originAllowed) {
        const monthKey = 'tavily:' + new Date().toISOString().slice(0, 7);
        let counter = 0;
        try {
          const val = await env.SEARCH_COUNTER.get(monthKey);
          counter = val ? parseInt(val, 10) : 0;
        } catch (e) { console.error('KV READ FAILED:', e.message); }
        if (counter >= MONTHLY_CAP) {
          console.error('TAVILY CAP HIT:', monthKey, counter);
        } else {
          const lastUserMsg = [...messages].reverse().find((m) => m.role === 'user');
          const query = lastUserMsg ? (typeof lastUserMsg.content === 'string' ? lastUserMsg.content : '') : '';
          if (query) {
            try {
              const tavilyRes = await fetch('https://api.tavily.com/search', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  api_key: env.TAVILY_API_KEY,
                  query: query,
                  search_depth: 'advanced',
                  max_results: 8,
                  include_answer: false,
                }),
              });
              if (tavilyRes.ok) {
                const data = await tavilyRes.json();
                const results = data.results || [];
                if (results.length) {
                  searchResults = 'Current web search results:\n\n' +
                    results.map(function(r, i) {
                      var entry = (i + 1) + '. ' + (r.title || '');
                      if (r.url) entry += '\n   Source: ' + r.url;
                      if (r.published_date) entry += '\n   Date: ' + r.published_date.slice(0, 10);
                      entry += '\n   ' + (r.content || '');
                      return entry;
                    }).join('\n\n') +
                    '\n\nAnswer the question above using these search results. Do not mention the search.';
                }
                ctx.waitUntil(
                  (async function() {
                    try { await env.SEARCH_COUNTER.put(monthKey, String(counter + 1), { expirationTtl: 3456000 }); }
                    catch (e) { console.error('KV WRITE FAILED:', e.message); }
                  })()
                );
              } else {
                console.error('TAVILY FAILED:', tavilyRes.status, await tavilyRes.text());
              }
            } catch (e) { console.error('TAVILY THREW:', e.message); }
          }
        }
      } else {
        console.error('SEARCH ORIGIN BLOCKED:', reqOrigin);
      }
    }

    const temp = typeof temperature === 'number' ? temperature : 0.7;
    const maxOut = typeof max_tokens === 'number' ? max_tokens : 2048;
    const useThinking = !!thinking;
    const safeMessages = messages.map((m) => ({
      role: m.role,
      content: m.content,
    }));
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

    const { readable, writable } = new TransformStream();
    const writer = writable.getWriter();
    const encoder = new TextEncoder();

    const streamPromise = (async () => {
      try {
        switch (provider) {
          case 'openai': {
            const url = PROVIDER_URLS.openai;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'cerebras': {
            const url = PROVIDER_URLS.cerebras;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'nvidia': {
            const url = PROVIDER_URLS.nvidia;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'moonshot': {
            const url = PROVIDER_URLS.moonshot;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'groq': {
            const url = PROVIDER_URLS.groq;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'together': {
            const url = PROVIDER_URLS.together;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'deepseek': {
            const url = PROVIDER_URLS.deepseek;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'xai': {
            const url = PROVIDER_URLS.xai;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            if (web_search) reqBody.search_parameters = { mode: 'on' };
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'openrouter': {
            const url = PROVIDER_URLS.openrouter;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'anthropic': {
            const reqBody = buildAnthropicRequest(model, safeMessages, temp, maxOut, useThinking);
            await streamAnthropic(apiKey, reqBody, encoder, writer);
            break;
          }
          case 'google': {
            const reqBody = buildGoogleRequest(model, safeMessages, temp, maxOut, useThinking);
            if (web_search) {
              if (!reqBody.tools) reqBody.tools = [{ googleSearch: {} }];
              else reqBody.tools.push({ googleSearch: {} });
            }
            await streamGoogle(model, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'custom': {
            if (!baseURL) {
              await writer.write(encoder.encode(JSON.stringify({ error: { message: 'Missing base URL for custom provider' } }) + '\n'));
              break;
            }
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
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

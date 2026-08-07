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

function buildOpenAIRequest(model, messages, temperature, maxTokens, effort) {
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

function buildAnthropicRequest(model, messages, temperature, maxTokens, effortBudget, searchTool) {
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
  if (searchTool) {
    body.tools = [searchTool];
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

function buildGoogleRequest(model, messages, temperature, maxTokens, effortBudget) {
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
      collectAnnotations(delta && delta.annotations);
      collectAnnotations(parsed.choices && parsed.choices[0] && parsed.choices[0].message && parsed.choices[0].message.annotations);
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
        } else if (type === 'content_block_delta') {
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
                }
              }
            }
          }
        }
      } catch (_) { /* skip */ }
    }
  }
  if (searchSources.length) {
    await writer.write(encoder.encode(JSON.stringify({ search: { query: '', sources: searchSources } }) + '\n'));
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

    const { provider, model, apiKey, messages, temperature, max_tokens, thinking, baseURL, web_search, searchMode: rawSearchMode, effort } = body;

    if (!provider) return jsonResponse({ error: { message: 'Missing provider' } }, 400);
    if (!model) return jsonResponse({ error: { message: 'Missing model' } }, 400);
    if (!apiKey) return jsonResponse({ error: { message: 'Missing API key' } }, 400);
    if (!messages || !messages.length) return jsonResponse({ error: { message: 'Missing messages' } }, 400);

    const searchMode = rawSearchMode === 'on' || rawSearchMode === 'off' || rawSearchMode === 'auto' ? rawSearchMode : (web_search ? 'on' : 'auto');
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
          let counter = 0;
          try {
            const val = await env.SEARCH_COUNTER.get(monthKey);
            counter = val ? parseInt(val, 10) : 0;
          } catch (e) { console.error('KV READ FAILED:', e.message); }
          if (counter >= MONTHLY_CAP) {
            console.error('TAVILY CAP HIT:', monthKey, counter);
            searchEvent = { search: { error: 'Monthly search cap reached (' + MONTHLY_CAP + ').' } };
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
                  try { await env.SEARCH_COUNTER.put(monthKey, String(counter + queries.length), { expirationTtl: 3456000 }); }
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
    const safeMessages = messages.map((m) => ({
      role: m.role,
      content: m.content,
    }));

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

    const deepseekMessages = provider === 'deepseek' ? safeMessages.map((m) => ({ role: m.role, content: stripImageParts(m.content) })) : safeMessages;

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
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'cerebras': {
            const url = PROVIDER_URLS.cerebras;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'nvidia': {
            const url = PROVIDER_URLS.nvidia;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'moonshot': {
            const url = PROVIDER_URLS.moonshot;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'groq': {
            const url = PROVIDER_URLS.groq;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
            if (searchMode !== 'off' && /^openai\/gpt-oss-(20b|120b)$/.test(model)) {
              reqBody.tools = [{ type: 'browser_search' }];
            }
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'together': {
            const url = PROVIDER_URLS.together;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'deepseek': {
            const url = PROVIDER_URLS.deepseek;
            const reqBody = buildOpenAIRequest(model, deepseekMessages, temp, maxOut, effortTier);
            if (effortTier) reqBody.thinking = { type: 'enabled' };
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'xai': {
            const url = PROVIDER_URLS.xai;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
            if (searchMode === 'on') reqBody.search_parameters = { mode: 'on' };
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer);
            break;
          }
          case 'openrouter': {
            const url = PROVIDER_URLS.openrouter;
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut);
            if (effortTier) reqBody.reasoning = { effort: effortTier };
            if (searchMode !== 'off') {
              reqBody.tools = [{ type: 'openrouter:web_search', parameters: { max_results: 5, max_uses: 5 } }];
            }
            await streamOpenAICompatible(url, apiKey, reqBody, encoder, writer, searchMode !== 'off');
            break;
          }
          case 'anthropic': {
            const effortBudget = ANTHROPIC_EFFORT_BUDGET[effortTier] || null;
            const searchTool = searchMode !== 'off' ? anthropicSearchTool(model) : null;
            const reqBody = buildAnthropicRequest(model, safeMessages, temp, maxOut, effortBudget, searchTool);
            await streamAnthropic(apiKey, reqBody, encoder, writer);
            break;
          }
          case 'google': {
            const effortBudget = GOOGLE_EFFORT_BUDGET[effortTier] || null;
            const reqBody = buildGoogleRequest(model, safeMessages, temp, maxOut, effortBudget);
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
            const reqBody = buildOpenAIRequest(model, safeMessages, temp, maxOut, effortTier);
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

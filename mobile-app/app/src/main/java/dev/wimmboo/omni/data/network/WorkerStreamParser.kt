package dev.wimmboo.omni.data.network

import dev.wimmboo.omni.data.model.SearchData
import dev.wimmboo.omni.data.model.SearchSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ToolStartPayload(val slot: Int, val id: String, val name: String)
data class ToolArgsPayload(val slot: Int, val delta: String)

data class UsagePayload(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
)

data class ServerTimingPayload(
    val source: String? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val promptMs: Double? = null,
    val genMs: Double? = null,
    val queueMs: Double? = null,
)

data class WorkerEvent(
    val delta: String? = null,
    val thinkingDelta: String? = null,
    val thinkingDone: Boolean = false,
    val toolStart: ToolStartPayload? = null,
    val toolArgs: ToolArgsPayload? = null,
    val toolEndSlot: Int? = null,
    val usage: UsagePayload? = null,
    val serverTiming: ServerTimingPayload? = null,
    val stopReason: String? = null,
    val stopToolUse: Boolean = false,
    val search: SearchData? = null,
    val error: String? = null,
)

object WorkerStreamParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses a single NDJSON or SSE `data:` line from worker.js into a [WorkerEvent],
     * or null for blank lines, `[DONE]`, comments, or unparseable fragments.
     */
    fun parseLine(rawLine: String): WorkerEvent? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null
        var payload = trimmed
        if (payload.startsWith("data:")) {
            payload = payload.removePrefix("data:").trim()
        }
        if (payload.isEmpty() || payload == "[DONE]") return null

        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val delta = obj.stringField("delta")
            ?: obj.stringField("content")
            ?: obj.stringField("token")
            ?: obj.stringField("text")

        val thinkingDelta = obj.stringField("thinking_delta")
            ?: obj.stringField("thinking")
            ?: obj.stringField("reasoning")

        val thinkingDone = (obj["thinking_done"] as? JsonPrimitive)?.booleanOrNull == true ||
            (obj["thinking_complete"] as? JsonPrimitive)?.booleanOrNull == true

        val toolStart = (obj["tool_start"] as? JsonObject)?.let { ts ->
            val slot = (ts["i"] as? JsonPrimitive)?.intOrNull ?: 0
            val id = ts.stringField("id").orEmpty()
            val name = ts.stringField("name").orEmpty()
            ToolStartPayload(slot = slot, id = id, name = name)
        }

        val toolArgs = (obj["tool_args"] as? JsonObject)?.let { ta ->
            val slot = (ta["i"] as? JsonPrimitive)?.intOrNull ?: 0
            val d = ta.stringField("d").orEmpty()
            ToolArgsPayload(slot = slot, delta = d)
        }

        val toolEndSlot = (obj["tool_end"] as? JsonObject)?.let { te ->
            (te["i"] as? JsonPrimitive)?.intOrNull
        }

        val usage = (obj["usage"] as? JsonObject)?.let { u ->
            UsagePayload(
                promptTokens = u.longField("prompt_tokens") ?: u.longField("input_tokens"),
                completionTokens = u.longField("completion_tokens") ?: u.longField("output_tokens"),
                totalTokens = u.longField("total_tokens"),
                cachedTokens = u.longField("cached_tokens"),
                cacheWriteTokens = u.longField("cache_write_tokens"),
                reasoningTokens = u.longField("reasoning_tokens"),
            )
        }

        val serverTiming = (obj["server_timing"] as? JsonObject)?.let { st ->
            ServerTimingPayload(
                source = st.stringField("source"),
                promptTokens = st.longField("prompt_tokens"),
                completionTokens = st.longField("completion_tokens"),
                promptMs = st.doubleField("prompt_ms"),
                genMs = st.doubleField("gen_ms"),
                queueMs = st.doubleField("queue_ms"),
            )
        }

        val stopReason = obj.stringField("stop_reason")
        val stopToolUse = (obj["stop"] as? JsonObject)?.stringField("reason") == "tool_use"

        val search = (obj["search"] as? JsonObject)?.let { s ->
            val sources = s["sources"]?.let { el ->
                try {
                    el.jsonArray.mapNotNull { item ->
                        val so = item as? JsonObject ?: return@mapNotNull null
                        SearchSource(
                            title = so.stringField("title").orEmpty(),
                            url = so.stringField("url").orEmpty(),
                            domain = so.stringField("domain").orEmpty(),
                            date = so.stringField("date").orEmpty(),
                            snippet = so.stringField("snippet").orEmpty(),
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()
            SearchData(
                query = s.stringField("query").orEmpty(),
                sources = sources,
                credits = (s["credits"] as? JsonPrimitive)?.intOrNull ?: 0,
                depth = s.stringField("depth").orEmpty(),
                cached = (s["cached"] as? JsonPrimitive)?.booleanOrNull ?: false,
                error = s.stringField("error"),
                code = s.stringField("code"),
            )
        }

        val error = obj["error"]?.let { errEl ->
            when (errEl) {
                is JsonObject -> errEl.stringField("message") ?: "The provider returned an error."
                is JsonPrimitive -> errEl.contentOrNull ?: "The provider returned an error."
                else -> "The provider returned an error."
            }
        }

        return WorkerEvent(
            delta = delta,
            thinkingDelta = thinkingDelta,
            thinkingDone = thinkingDone,
            toolStart = toolStart,
            toolArgs = toolArgs,
            toolEndSlot = toolEndSlot,
            usage = usage,
            serverTiming = serverTiming,
            stopReason = stopReason,
            stopToolUse = stopToolUse,
            search = search,
            error = error,
        )
    }

    fun providerStatusText(status: Int): String = when {
        status == 401 || status == 403 -> "The provider rejected the API key."
        status == 404 -> "The provider could not find that model."
        status == 408 -> "The provider timed out. Try again."
        status == 413 -> "The request was too large for the provider."
        status == 429 -> "The provider rate limit was reached. Try again in a moment."
        status == 402 -> "The provider billing limit was reached."
        status == 400 || status == 422 -> "The provider rejected the request."
        status >= 500 -> "The provider is temporarily unavailable."
        status > 0 -> "The provider returned an error (HTTP $status)."
        else -> "The provider returned an error."
    }

    private val WHITESPACE_RE = Regex("\\s+")
    private val HTTP_CODE_RE = Regex("^(?:Provider error|HTTP) (\\d{3})\\b")

    fun friendlyErrorText(raw: String?): String {
        val msg = (raw ?: "").replace(WHITESPACE_RE, " ").trim()
        if (msg.isEmpty()) return "The provider returned an error."
        val coded = HTTP_CODE_RE.find(msg)
        if (coded != null) {
            val code = coded.groupValues[1].toIntOrNull() ?: 0
            // Preserve explicit Worker auth/origin messages like HTTP 403: Origin is not allowed
            val detail = msg.substringAfter(":", "").trim()
            if (detail.isNotEmpty() && !detail.startsWith("{") && !detail.startsWith("[") && detail.length <= 160) {
                return detail
            }
            return providerStatusText(code)
        }
        if (msg.startsWith("{") || msg.startsWith("[") || msg.length > 240) {
            return "The provider returned an error."
        }
        return msg
    }

    private fun JsonObject.stringField(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    private fun JsonObject.longField(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.doubleField(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}

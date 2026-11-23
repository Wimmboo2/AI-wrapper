package dev.wimmboo.omni.domain

import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.ChatMessage
import dev.wimmboo.omni.data.model.MessageMetrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class ServerTimingData(
    var source: String? = null,
    var promptTokens: Long? = null,
    var completionTokens: Long? = null,
    var promptMs: Double? = null,
    var genMs: Double? = null,
    var queueMs: Double? = null,
)

data class TurnRecord(
    val startMs: Double,
    var firstTokenMs: Double? = null,
    var firstVisibleMs: Double? = null,
    var endMs: Double? = null,
    var deltaCount: Int = 0,
    var promptTokens: Long? = null,
    var completionTokens: Long? = null,
    var cachedTokens: Long? = null,
    var cacheWriteTokens: Long? = null,
    var reasoningTokens: Long? = null,
    var server: ServerTimingData? = null,
)

data class MetricPhases(
    val total: Double,
    val wait: Double,
    val think: Double,
    val tool: Double,
    val gen: Double,
)

data class SpeedTier(val max: Double, val tone: Int, val word: String)
data class ContextTier(val max: Double, val tone: String, val barTone: String, val word: String)

data class ChatTokenTotals(
    val input: Long,
    val output: Long,
    val cached: Long,
    val replies: Int,
    val estimated: Boolean,
    val inputEstimated: Boolean,
    val outputEstimated: Boolean,
)

object MetricsCalculator {
    const val DASH = "\u2014"
    const val MIN_RATE_WINDOW_MS = 50.0
    const val MIN_PREFILL_WINDOW_MS = 20.0

    val SPEED_TIERS = listOf(
        SpeedTier(8.0, 1, "crawling"),
        SpeedTier(30.0, 2, "steady"),
        SpeedTier(120.0, 3, "quick"),
        SpeedTier(Double.POSITIVE_INFINITY, 4, "blazing"),
    )

    val CONTEXT_TIERS = listOf(
        ContextTier(50.0, "", "good", "plenty of room"),
        ContextTier(75.0, "warning", "warning", "past halfway"),
        ContextTier(90.0, "serious", "serious", "filling up"),
        ContextTier(Double.POSITIVE_INFINITY, "critical", "critical", "almost full"),
    )

    val STOP_REASON_TEXT = mapOf(
        "max_tokens" to "truncated \u2014 hit the output cap",
        "content_filter" to "stopped by a content filter",
        "stop_sequence" to "hit a stop sequence",
        "error" to "the provider ended the turn on an error",
    )

    fun pickSpeedTier(v: Double): SpeedTier =
        SPEED_TIERS.firstOrNull { v < it.max } ?: SPEED_TIERS.last()

    fun pickContextTier(pct: Double): ContextTier =
        CONTEXT_TIERS.firstOrNull { pct < it.max } ?: CONTEXT_TIERS.last()

    fun fmtInt(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

    fun formatMs(ms: Double?): String {
        if (ms == null || ms.isNaN() || ms.isInfinite()) return DASH
        if (ms < 1000.0) return "${ms.roundToLong()} ms"
        if (ms < 10000.0) return String.format(Locale.US, "%.2f s", ms / 1000.0)
        if (ms < 60000.0) return String.format(Locale.US, "%.1f s", ms / 1000.0)
        var mins = (ms / 60000.0).toLong()
        var secs = ((ms % 60000.0) / 1000.0).roundToLong()
        if (secs == 60L) {
            mins += 1L
            secs = 0L
        }
        return "${mins}m ${if (secs < 10) "0" else ""}${secs}s"
    }

    fun formatRate(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return DASH
        return when {
            v >= 1000.0 -> fmtInt(v.roundToLong())
            v >= 100.0 -> String.format(Locale.US, "%.0f", v)
            v >= 10.0 -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }
    }

    fun keepCount(prev: Long?, next: Long?): Long? {
        if (next == null || next < 0L) return prev
        if (prev == null) return next
        return if (next > prev) next else prev
    }

    fun keepDouble(prev: Double?, next: Double?): Double? {
        if (next == null || next.isNaN() || next.isInfinite() || next < 0.0) return prev
        if (prev == null || prev.isNaN()) return next
        return if (next > prev) next else prev
    }

    fun metricPhases(m: MessageMetrics): MetricPhases? {
        val total = m.totalDurationMs ?: return null
        if (total <= 0.0) return null
        var wait = m.prefillMs?.coerceAtLeast(0.0) ?: m.ttftMs?.coerceAtLeast(0.0) ?: 0.0
        var think = m.thinkMs?.coerceAtLeast(0.0) ?: 0.0
        var tool = m.toolMs.coerceAtLeast(0.0)
        val head = wait + think + tool
        if (head > total) {
            val scale = total / head
            wait *= scale
            think *= scale
            tool *= scale
        }
        return MetricPhases(
            total = total,
            wait = wait,
            think = think,
            tool = tool,
            gen = max(0.0, total - wait - think - tool),
        )
    }

    fun approxContentLength(content: JsonElement?): Int = when (content) {
        is JsonPrimitive -> if (content.isString) content.content.length else 0
        is JsonArray -> content.sumOf { part ->
            val obj = part as? JsonObject
            val txt = obj?.get("text")?.jsonPrimitive?.contentOrNull
            txt?.length ?: 24
        }
        else -> 0
    }

    fun promptHasImages(messages: List<JsonObject>): Boolean {
        for (m in messages) {
            val arr = m["content"] as? JsonArray ?: continue
            for (partEl in arr) {
                val obj = partEl as? JsonObject ?: continue
                if (obj["text"] == null) return true
            }
        }
        return false
    }

    fun chatDisplayMetrics(chat: Chat?): MessageMetrics? {
        if (chat == null) return null
        for (i in chat.messages.indices.reversed()) {
            val msg = chat.messages[i]
            if (msg.role == "assistant" && msg.metrics != null) return msg.metrics
        }
        return chat.metrics
    }

    fun chatTokenTotals(chat: Chat?): ChatTokenTotals {
        if (chat == null) return ChatTokenTotals(0, 0, 0, 0, false, false, false)
        var input = 0L
        var output = 0L
        var cached = 0L
        var replies = 0
        var inputEstimated = false
        var outputEstimated = false
        for (msg in chat.messages) {
            if (msg.role != "assistant") continue
            val mm = msg.metrics
            val inTok = mm?.inputTokens ?: msg.inTokens
            val outTok = mm?.outputTokens ?: msg.outTokens
            if (inTok == null && outTok == null) continue
            input += inTok ?: 0L
            output += outTok ?: 0L
            if (mm?.cachedTokens != null) cached += mm.cachedTokens
            val legacy = mm != null && mm.tokensEstimated && mm.inputEstimated == null && mm.outputEstimated == null
            if (mm == null || mm.inputEstimated == true || legacy) inputEstimated = true
            if (mm == null || mm.outputEstimated == true || legacy) outputEstimated = true
            replies++
        }
        return ChatTokenTotals(
            input = input,
            output = output,
            cached = cached,
            replies = replies,
            estimated = inputEstimated || outputEstimated,
            inputEstimated = inputEstimated,
            outputEstimated = outputEstimated,
        )
    }

    fun finalizeMetrics(
        runStartMs: Double,
        nowMs: Double,
        turnRecords: List<TurnRecord>,
        requestMessages: List<JsonObject>,
        assistantMsg: ChatMessage,
        contextWindow: Int,
        toolMs: Double,
        toolCalls: Int,
        stopReason: String?,
        partial: Boolean,
    ): MessageMetrics {
        val totalDurationMs = max(0.0, nowMs - runStartMs)
        val first = turnRecords.firstOrNull()
        val ttftMs = if (first?.firstTokenMs != null) first.firstTokenMs!! - first.startMs else null
        val thinkMs = if (first?.firstTokenMs != null && first.firstVisibleMs != null && first.firstVisibleMs!! > first.firstTokenMs!!) {
            first.firstVisibleMs!! - first.firstTokenMs!!
        } else null

        var inSum = 0L
        var outSum = 0L
        var cachedSum = 0L
        var writeSum = 0L
        var reasonSum = 0L
        var sawIn = false
        var sawOut = false
        var prefillMs = 0.0
        var genMs = 0.0
        var deltaCount = 0
        var srvPromptMs = 0.0
        var srvGenMs = 0.0
        var srvEngine: String? = null
        var srvEveryTurn = turnRecords.isNotEmpty()

        for (t in turnRecords) {
            val s = t.server
            if (s != null) {
                if (t.promptTokens == null && (s.promptTokens ?: 0L) > 0L) t.promptTokens = s.promptTokens
                if (t.completionTokens == null && (s.completionTokens ?: 0L) > 0L) t.completionTokens = s.completionTokens
            }
            if (t.promptTokens != null) {
                inSum += t.promptTokens!!
                sawIn = true
            }
            if (t.completionTokens != null) {
                outSum += t.completionTokens!!
                sawOut = true
            }
            cachedSum += t.cachedTokens ?: 0L
            writeSum += t.cacheWriteTokens ?: 0L
            reasonSum += t.reasoningTokens ?: 0L

            deltaCount += t.deltaCount
            val end = t.endMs ?: nowMs
            if (t.firstTokenMs != null) {
                prefillMs += max(0.0, t.firstTokenMs!! - t.startMs)
                genMs += max(0.0, end - t.firstTokenMs!!)
            } else {
                prefillMs += max(0.0, end - t.startMs)
            }

            if (s != null) {
                srvEngine = s.source
                srvPromptMs += s.promptMs ?: 0.0
                srvGenMs += s.genMs ?: 0.0
            } else {
                srvEveryTurn = false
            }
        }

        var inputEstimated = false
        var outputEstimated = false
        var finalOutSum: Long? = if (sawOut) outSum else null
        var finalInSum: Long? = if (sawIn) inSum else null

        if (!sawOut) {
            var producedChars = assistantMsg.content.length + assistantMsg.thinking.length
            assistantMsg.steps?.forEach { producedChars += it.text.length }
            if (producedChars > 0) {
                finalOutSum = max(1L, (producedChars / 3.5).roundToLong())
                outputEstimated = true
            }
        }
        if (!sawIn) {
            if (!promptHasImages(requestMessages)) {
                val promptChars = requestMessages.sumOf { approxContentLength(it["content"]) }
                finalInSum = max(1L, (promptChars / 3.5).roundToLong())
                inputEstimated = true
            }
        }

        val contextUsed: Long?
        val contextEstimated: Boolean
        if (turnRecords.size <= 1) {
            if (finalInSum == null && finalOutSum == null) {
                contextUsed = null
                contextEstimated = false
            } else {
                contextUsed = (finalInSum ?: 0L) + (finalOutSum ?: 0L)
                contextEstimated = inputEstimated || outputEstimated
            }
        } else {
            val last = turnRecords.lastOrNull()
            if (last != null && (last.promptTokens != null || last.completionTokens != null)) {
                contextUsed = (last.promptTokens ?: 0L) + (last.completionTokens ?: 0L)
                contextEstimated = false
            } else {
                contextUsed = null
                contextEstimated = false
            }
        }

        var genTps: Double? = null
        var promptTps: Double? = null
        var rateSource: String? = null
        var promptRateSource: String? = null
        var rateEngine: String? = null
        var genWindowMs: Double? = null
        var promptWindowMs: Double? = null
        var rateUnmeasurable = false

        val outCount = finalOutSum ?: 0L
        val inCount = finalInSum ?: 0L
        if (srvEveryTurn && srvGenMs > 0.0 && outCount > 0L) {
            genTps = (outCount / srvGenMs) * 1000.0
            rateSource = "server"
            rateEngine = srvEngine
            genWindowMs = srvGenMs
        } else if (outCount > 0L && deltaCount >= 2 && genMs >= MIN_RATE_WINDOW_MS) {
            genTps = (outCount / genMs) * 1000.0
            rateSource = "client"
            genWindowMs = genMs
        } else if (outCount > 0L) {
            rateUnmeasurable = true
        }

        if (srvEveryTurn && srvPromptMs > 0.0 && inCount > 0L) {
            promptTps = (inCount / srvPromptMs) * 1000.0
            promptRateSource = "server"
            rateEngine = rateEngine ?: srvEngine
            promptWindowMs = srvPromptMs
        } else if (inCount > 0L && prefillMs >= MIN_PREFILL_WINDOW_MS) {
            promptTps = (inCount / prefillMs) * 1000.0
            promptRateSource = "client"
            promptWindowMs = prefillMs
        }

        return MessageMetrics(
            turns = turnRecords.size,
            inputTokens = finalInSum,
            outputTokens = finalOutSum,
            cachedTokens = if (cachedSum > 0L) cachedSum else null,
            cacheWriteTokens = if (writeSum > 0L) writeSum else null,
            reasoningTokens = if (reasonSum > 0L) reasonSum else null,
            generationTokensPerSecond = genTps,
            promptTokensPerSecond = promptTps,
            rateSource = rateSource,
            rateEngine = rateEngine,
            ttftMs = ttftMs,
            thinkMs = thinkMs,
            prefillMs = prefillMs,
            toolMs = toolMs,
            toolCalls = toolCalls,
            totalDurationMs = totalDurationMs,
            stopReason = stopReason,
            rateUnmeasurable = rateUnmeasurable,
            contextUsed = contextUsed,
            contextWindow = contextWindow,
            tokensEstimated = inputEstimated || outputEstimated,
            inputEstimated = inputEstimated,
            outputEstimated = outputEstimated,
            contextEstimated = contextEstimated,
            genWindowMs = genWindowMs,
            promptWindowMs = promptWindowMs,
            promptRateSource = promptRateSource,
            partial = partial,
            live = false,
        )
    }

    fun contextPercent(used: Long, window: Int): Double {
        if (window <= 0) return 0.0
        return min(100.0, (used.toDouble() / window.toDouble()) * 100.0)
    }
}

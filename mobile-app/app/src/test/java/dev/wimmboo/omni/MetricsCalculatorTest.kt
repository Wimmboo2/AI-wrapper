package dev.wimmboo.omni

import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.ChatMessage
import dev.wimmboo.omni.domain.MetricsCalculator
import dev.wimmboo.omni.domain.ServerTimingData
import dev.wimmboo.omni.domain.TurnRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsCalculatorTest {

    @Test
    fun formatsDurationsAndRatesMatchingWeb() {
        assertEquals("250 ms", MetricsCalculator.formatMs(250.0))
        assertEquals("2.45 s", MetricsCalculator.formatMs(2450.0))
        assertEquals("15.5 s", MetricsCalculator.formatMs(15490.0))
        assertEquals("1m 05s", MetricsCalculator.formatMs(65000.0))

        assertEquals("4.50", MetricsCalculator.formatRate(4.5))
        assertEquals("42.3", MetricsCalculator.formatRate(42.34))
        assertEquals("159", MetricsCalculator.formatRate(159.2))
        assertEquals("1,250", MetricsCalculator.formatRate(1250.4))
    }

    @Test
    fun computesMultiTurnBilledVsLastTurnContextAndServerTimings() {
        val turn1 = TurnRecord(
            startMs = 0.0,
            firstTokenMs = 400.0,
            firstVisibleMs = 900.0,
            endMs = 2400.0,
            deltaCount = 20,
            promptTokens = 500,
            completionTokens = 100,
            cachedTokens = 200,
            server = ServerTimingData(source = "groq", promptMs = 50.0, genMs = 500.0),
        )
        val turn2 = TurnRecord(
            startMs = 3000.0,
            firstTokenMs = 3300.0,
            firstVisibleMs = 3300.0,
            endMs = 4300.0,
            deltaCount = 15,
            promptTokens = 800,
            completionTokens = 150,
            cachedTokens = 400,
            server = ServerTimingData(source = "groq", promptMs = 80.0, genMs = 500.0),
        )
        val metrics = MetricsCalculator.finalizeMetrics(
            runStartMs = 0.0,
            nowMs = 4300.0,
            turnRecords = listOf(turn1, turn2),
            requestMessages = listOf(buildJsonObject { put("role", "user"); put("content", "hi") }),
            assistantMsg = ChatMessage(role = "assistant", content = "Final answer"),
            contextWindow = 100000,
            toolMs = 600.0,
            toolCalls = 1,
            stopReason = "end_turn",
            partial = false,
        )

        assertEquals(2, metrics.turns)
        assertEquals(1300L, metrics.inputTokens) // 500 + 800 billed
        assertEquals(250L, metrics.outputTokens) // 100 + 150 billed
        assertEquals(950L, metrics.contextUsed) // last turn alone: 800 + 150
        assertEquals("server", metrics.rateSource)
        assertEquals("groq", metrics.rateEngine)
        assertEquals(250.0, metrics.generationTokensPerSecond ?: 0.0, 0.01) // 250 tok / 1000ms
        assertEquals(400.0, metrics.ttftMs ?: 0.0, 0.01)
        assertEquals(500.0, metrics.thinkMs ?: 0.0, 0.01)
        assertFalse(metrics.tokensEstimated)

        val chat = Chat(messages = listOf(ChatMessage(role = "assistant", content = "a", metrics = metrics)))
        val totals = MetricsCalculator.chatTokenTotals(chat)
        assertEquals(1300L, totals.input)
        assertEquals(250L, totals.output)
        assertEquals(600L, totals.cached)
        assertEquals(1, totals.replies)
    }

    @Test
    fun marksSingleChunkRepliesUnmeasurableInsteadOfFakeRates() {
        val turn = TurnRecord(
            startMs = 0.0,
            firstTokenMs = 100.0,
            firstVisibleMs = 100.0,
            endMs = 102.0,
            deltaCount = 1,
            promptTokens = 50,
            completionTokens = 100,
        )
        val metrics = MetricsCalculator.finalizeMetrics(
            runStartMs = 0.0,
            nowMs = 102.0,
            turnRecords = listOf(turn),
            requestMessages = emptyList(),
            assistantMsg = ChatMessage(role = "assistant", content = "fast"),
            contextWindow = 8192,
            toolMs = 0.0,
            toolCalls = 0,
            stopReason = "end_turn",
            partial = false,
        )
        assertNull(metrics.generationTokensPerSecond)
        assertTrue(metrics.rateUnmeasurable)
    }
}

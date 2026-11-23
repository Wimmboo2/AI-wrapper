package dev.wimmboo.omni

import dev.wimmboo.omni.data.network.WorkerStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerStreamParserTest {

    @Test
    fun parsesNdjsonAndSseLinesAndSkipsDone() {
        assertNull(WorkerStreamParser.parseLine(""))
        assertNull(WorkerStreamParser.parseLine("   "))
        assertNull(WorkerStreamParser.parseLine("data: [DONE]"))
        assertNull(WorkerStreamParser.parseLine("[DONE]"))
        assertNull(WorkerStreamParser.parseLine("not valid json"))

        val ev1 = WorkerStreamParser.parseLine("""{"delta":"Hello "}""")
        assertEquals("Hello ", ev1?.delta)

        val ev2 = WorkerStreamParser.parseLine("""data: {"content":"world"}""")
        assertEquals("world", ev2?.delta)

        val ev3 = WorkerStreamParser.parseLine("""{"token":"!"}""")
        assertEquals("!", ev3?.delta)

        val ev4 = WorkerStreamParser.parseLine("""{"thinking_delta":"step 1","thinking_done":true}""")
        assertEquals("step 1", ev4?.thinkingDelta)
        assertTrue(ev4?.thinkingDone == true)
    }

    @Test
    fun parsesToolCallStreamEvents() {
        val start = WorkerStreamParser.parseLine("""{"tool_start":{"i":0,"id":"call_abc","name":"run_python"}}""")
        assertEquals(0, start?.toolStart?.slot)
        assertEquals("call_abc", start?.toolStart?.id)
        assertEquals("run_python", start?.toolStart?.name)

        val args = WorkerStreamParser.parseLine("""{"tool_args":{"i":0,"d":"{\"code\":\"print(1)\"}"}}""")
        assertEquals(0, args?.toolArgs?.slot)
        assertEquals("""{"code":"print(1)"}""", args?.toolArgs?.delta)

        val end = WorkerStreamParser.parseLine("""{"tool_end":{"i":0}}""")
        assertEquals(0, end?.toolEndSlot)

        val stop = WorkerStreamParser.parseLine("""{"stop":{"reason":"tool_use"}}""")
        assertTrue(stop?.stopToolUse == true)
    }

    @Test
    fun parsesUsageServerTimingSearchAndErrors() {
        val usageEv = WorkerStreamParser.parseLine(
            """{"usage":{"prompt_tokens":1200,"completion_tokens":350,"cached_tokens":800,"cache_write_tokens":50,"reasoning_tokens":100}}""",
        )
        assertNotNull(usageEv?.usage)
        assertEquals(1200L, usageEv?.usage?.promptTokens)
        assertEquals(350L, usageEv?.usage?.completionTokens)
        assertEquals(800L, usageEv?.usage?.cachedTokens)
        assertEquals(50L, usageEv?.usage?.cacheWriteTokens)
        assertEquals(100L, usageEv?.usage?.reasoningTokens)

        val timingEv = WorkerStreamParser.parseLine(
            """{"server_timing":{"source":"groq","prompt_tokens":100,"completion_tokens":50,"prompt_ms":12.5,"gen_ms":250.0}}""",
        )
        assertEquals("groq", timingEv?.serverTiming?.source)
        assertEquals(250.0, timingEv?.serverTiming?.genMs ?: 0.0, 0.001)

        val searchEv = WorkerStreamParser.parseLine(
            """{"search":{"query":"kotlin compose","credits":1,"depth":"basic","cached":false,"sources":[{"title":"Compose","url":"https://developer.android.com","domain":"developer.android.com","date":"2026-11-01"}]}}""",
        )
        assertEquals("kotlin compose", searchEv?.search?.query)
        assertEquals(1, searchEv?.search?.sources?.size)
        assertEquals("developer.android.com", searchEv?.search?.sources?.first()?.domain)

        val errObj = WorkerStreamParser.parseLine("""{"error":{"message":"The provider rejected the API key."}}""")
        assertEquals("The provider rejected the API key.", errObj?.error)

        val errStr = WorkerStreamParser.parseLine("""{"error":"Upstream failed"}""")
        assertEquals("Upstream failed", errStr?.error)
    }
}

package dev.wimmboo.omni

import dev.wimmboo.omni.domain.ThinkSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkSplitterTest {

    @Test
    fun splitsThinkTagAcrossChunkBoundaries() {
        val st = ThinkSplitter()
        val r1 = st.feed("Hello <thi")
        assertEquals("Hello ", r1.text)
        assertEquals("", r1.think)

        val r2 = st.feed("nk>deep thought</th")
        assertEquals("", r2.text)
        assertEquals("deep thought", r2.think)
        assertFalse(r2.closed)

        val r3 = st.feed("ink> final answer")
        assertEquals(" final answer", r3.text)
        assertEquals("", r3.think)
        assertTrue(r3.closed)

        val flush = st.flush()
        assertEquals("", flush.text)
        assertEquals("", flush.think)
    }

    @Test
    fun handlesCohereAndSeedDropTagsAndOtherDelimiters() {
        val st = ThinkSplitter()
        val r = st.feed("<|START_THINKING|>plan<|END_THINKING|><|START_RESPONSE|>answer<|END_RESPONSE|>")
        assertEquals("plan", r.think)
        assertEquals("answer", r.text)
        assertTrue(r.closed)

        val mistral = ThinkSplitter()
        val rm = mistral.feed("[THINK]hmm[/THINK]ok")
        assertEquals("hmm", rm.think)
        assertEquals("ok", rm.text)

        val kimi = ThinkSplitter()
        val rk = kimi.feed("\u25c1think\u25b7trace\u25c1/think\u25b7done")
        assertEquals("trace", rk.think)
        assertEquals("done", rk.text)
    }

    @Test
    fun handlesHarmonyChannels() {
        val st = ThinkSplitter()
        val r = st.feed(
            "<|channel|>analysis<|message|>working it out<|end|>" +
                "<|start|>assistant<|channel|>final<|message|>42<|return|>",
        )
        assertEquals("working it out", r.think)
        assertEquals("42", r.text)
        assertTrue(r.closed)
    }

    @Test
    fun handlesImplicitOpenReclaimAndUnpresume() {
        val st = ThinkSplitter(presumeOpen = false)
        val r1 = st.feed("silent chain of thought")
        assertEquals("silent chain of thought", r1.text)
        val r2 = st.feed("</think>actual reply")
        assertTrue(r2.reclaim)
        assertTrue(r2.closed)
        assertEquals("actual reply", r2.text)

        val presumed = ThinkSplitter(presumeOpen = true)
        val p1 = presumed.feed("plain answer without close tag")
        assertEquals("plain answer without close tag", p1.think)
        val flush = presumed.flush()
        assertTrue(flush.unpresume)
    }

    @Test
    fun leavesCodeGenericsAndMarkdownLinksUntouched() {
        val st = ThinkSplitter()
        val r = st.feed("Use List<String> and [docs](https://example.com) if (a < b)")
        val f = st.flush()
        assertEquals("Use List<String> and [docs](https://example.com) if (a < b)", r.text + f.text)
        assertEquals("", r.think + f.think)
    }
}

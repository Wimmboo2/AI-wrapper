package dev.wimmboo.omni.domain

/**
 * Exact 1:1 port of the incremental inline reasoning / Harmony channel splitter
 * from index.html (makeThinkSplitter, feedThinkSplitter, flushThinkSplitter).
 */
class ThinkSplitter(presumeOpen: Boolean = false) {
    var buf: String = ""
    var inThink: Boolean = presumeOpen
    var sawOpen: Boolean = false
    var reclaimed: Boolean = false
    val presumed: Boolean = presumeOpen
    var everClosed: Boolean = false
    var harmony: Boolean = false
    var hState: String = "idle"
    var hChannel: String = ""
    var hSpec: String = ""

    data class FeedResult(
        val text: String,
        val think: String,
        val closed: Boolean,
        val reclaim: Boolean,
    )

    data class FlushResult(
        val text: String,
        val think: String,
        val unpresume: Boolean,
    )

    private data class TagHit(val idx: Int, val len: Int, val norm: String)
    private data class TaggedHit(val kind: String, val hit: TagHit?)

    fun isInThought(): Boolean =
        inThink ||
            (presumed && !everClosed) ||
            (harmony && hState == "message" && hChannel.isNotEmpty() && hChannel != "final")

    fun feed(chunk: String): FeedResult {
        buf += chunk
        val text = StringBuilder()
        val think = StringBuilder()
        var closed = false
        var reclaim = false

        while (buf.isNotEmpty()) {
            if (harmony) {
                val hm = findTag(buf, HARMONY_TAGS)
                val upto = hm?.idx ?: (buf.length - danglingTagLen(buf, HARMONY_TAGS))
                val seg = buf.substring(0, upto)
                if (seg.isNotEmpty()) {
                    if (hState == "message") {
                        if (hChannel == "final") text.append(seg) else think.append(seg)
                    } else if (hState == "channel") {
                        hSpec += seg
                    }
                }
                buf = buf.substring(upto)
                if (hm == null) break
                buf = buf.substring(hm.len)
                when (hm.norm) {
                    "<|channel|>" -> {
                        hState = "channel"
                        hSpec = ""
                    }
                    "<|message|>" -> {
                        hChannel = hSpec.trimStart().split(Regex("[\\s<]"), limit = 2).firstOrNull().orEmpty().lowercase()
                        hState = "message"
                    }
                    "<|start|>" -> {
                        hState = "header"
                    }
                    "<|end|>", "<|return|>", "<|call|>" -> {
                        if (hState == "message" && hChannel != "final") {
                            everClosed = true
                            closed = true
                        }
                        hState = "idle"
                        hChannel = ""
                    }
                }
                continue
            }

            val harmonyHit = findTag(buf, HARMONY_TAGS)
            val dropHit = findTag(buf, THINK_DROP_TAGS)

            if (!inThink) {
                val open = findTag(buf, THINK_OPEN_TAGS)
                val orphan = if (sawOpen || reclaimed) null else findTag(buf, THINK_CLOSE_TAGS)
                val first = earliest(
                    listOf(
                        TaggedHit("harmony", harmonyHit),
                        TaggedHit("drop", dropHit),
                        TaggedHit("open", open),
                        TaggedHit("orphan", orphan),
                    ),
                )
                if (first?.hit != null) {
                    text.append(buf.substring(0, first.hit.idx))
                    if (first.kind == "harmony") {
                        buf = buf.substring(first.hit.idx)
                        harmony = true
                        continue
                    }
                    buf = buf.substring(first.hit.idx + first.hit.len)
                    when (first.kind) {
                        "drop" -> continue
                        "open" -> {
                            inThink = true
                            sawOpen = true
                            continue
                        }
                        "orphan" -> {
                            think.append(text)
                            text.clear()
                            reclaimed = true
                            everClosed = true
                            reclaim = true
                            closed = true
                            continue
                        }
                    }
                }
                val openCandidates = buildList {
                    addAll(THINK_OPEN_TAGS)
                    if (!sawOpen && !reclaimed) addAll(THINK_CLOSE_TAGS)
                    addAll(THINK_DROP_TAGS)
                    addAll(HARMONY_TAGS)
                }
                val holdOpen = danglingTagLen(buf, openCandidates)
                text.append(buf.substring(0, buf.length - holdOpen))
                buf = buf.substring(buf.length - holdOpen)
                break
            }

            val close = findTag(buf, THINK_CLOSE_TAGS)
            val firstIn = earliest(
                listOf(
                    TaggedHit("harmony", harmonyHit),
                    TaggedHit("drop", dropHit),
                    TaggedHit("close", close),
                ),
            )
            if (firstIn?.hit != null) {
                think.append(buf.substring(0, firstIn.hit.idx))
                if (firstIn.kind == "harmony") {
                    buf = buf.substring(firstIn.hit.idx)
                    harmony = true
                    inThink = false
                    continue
                }
                buf = buf.substring(firstIn.hit.idx + firstIn.hit.len)
                if (firstIn.kind == "drop") continue
                inThink = false
                everClosed = true
                closed = true
                continue
            }
            val holdClose = danglingTagLen(buf, THINK_CLOSE_TAGS + THINK_DROP_TAGS + HARMONY_TAGS)
            think.append(buf.substring(0, buf.length - holdClose))
            buf = buf.substring(buf.length - holdClose)
            break
        }

        return FeedResult(
            text = text.toString(),
            think = think.toString(),
            closed = closed,
            reclaim = reclaim,
        )
    }

    fun flush(): FlushResult {
        val rest = buf
        buf = ""
        if (harmony) {
            return if (hState == "message") {
                if (hChannel == "final") FlushResult(rest, "", false) else FlushResult("", rest, false)
            } else {
                FlushResult("", "", false)
            }
        }
        if (presumed && !everClosed) {
            return FlushResult(rest, "", true)
        }
        if (rest.isEmpty()) return FlushResult("", "", false)
        return if (inThink) FlushResult("", rest, false) else FlushResult(rest, "", false)
    }

    companion object {
        private val THINK_OPEN_TAGS = listOf(
            "<think>", "<thinking>", "<reason>", "<reasoning>", "<thought>",
            "<seed:think>", "[think]", "<|start_thinking|>", "\u25c1think\u25b7",
        )
        private val THINK_CLOSE_TAGS = listOf(
            "</think>", "</thinking>", "</reason>", "</reasoning>", "</thought>",
            "</seed:think>", "[/think]", "<|end_thinking|>", "\u25c1/think\u25b7",
        )
        private val THINK_DROP_TAGS = listOf(
            "<|start_response|>", "<|end_response|>",
            "<seed:cot_budget_reflect>", "</seed:cot_budget_reflect>",
        )
        private val HARMONY_TAGS = listOf(
            "<|channel|>", "<|message|>", "<|start|>", "<|end|>", "<|return|>",
            "<|call|>", "<|constrain|>",
        )

        private val TAG_SCAN_RE = Regex(
            "<\\|[a-zA-Z_]+\\|>|<\\s*/?\\s*[a-zA-Z][\\w:.-]*\\s*>|\\[\\s*/?\\s*[A-Za-z_]+\\s*\\]|\u25c1\\s*/?\\s*[a-zA-Z]+\\s*\u25b7",
        )
        private val MARKER_OPENERS = charArrayOf('<', '[', '\u25c1')
        private val MARKER_CLOSERS = charArrayOf('>', ']', '\u25b7')
        private val WHITESPACE_RE = Regex("\\s+")

        private fun findTag(hay: String, tags: List<String>): TagHit? {
            for (m in TAG_SCAN_RE.findAll(hay)) {
                val norm = m.value.replace(WHITESPACE_RE, "").lowercase()
                if (norm in tags) {
                    return TagHit(m.range.first, m.value.length, norm)
                }
            }
            return null
        }

        private fun earliest(hits: List<TaggedHit>): TaggedHit? {
            var best: TaggedHit? = null
            for (h in hits) {
                if (h.hit != null && (best?.hit == null || h.hit.idx < best.hit.idx)) {
                    best = h
                }
            }
            return best
        }

        private fun danglingTagLen(s: String, tags: List<String>): Int {
            var start = -1
            for (opener in MARKER_OPENERS) {
                val p = s.lastIndexOf(opener)
                if (p > start) start = p
            }
            if (start == -1) return 0
            val tail = s.substring(start)
            for (closer in MARKER_CLOSERS) {
                if (tail.indexOf(closer) != -1) return 0
            }
            val norm = tail.replace(WHITESPACE_RE, "").lowercase()
            for (tag in tags) {
                if (tag.startsWith(norm)) return s.length - start
            }
            return 0
        }
    }
}

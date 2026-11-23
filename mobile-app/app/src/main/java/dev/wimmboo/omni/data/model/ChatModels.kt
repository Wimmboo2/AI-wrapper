package dev.wimmboo.omni.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

@Immutable
@Serializable
data class SearchSource(
    val title: String = "",
    val url: String = "",
    val domain: String = "",
    val date: String = "",
    val snippet: String = "",
)

@Immutable
@Serializable
data class SearchData(
    val query: String = "",
    val sources: List<SearchSource> = emptyList(),
    val credits: Int = 0,
    val depth: String = "",
    val cached: Boolean = false,
    val error: String? = null,
    val code: String? = null,
)

@Immutable
@Serializable
data class ToolCallRecord(
    val id: String = "",
    val name: String = "",
    val argsText: String = "{}",
    val args: JsonObject = JsonObject(emptyMap()),
)

@Immutable
@Serializable
data class ToolResultRecord(
    val id: String = "",
    val name: String = "",
    val content: String = "",
)

@Immutable
@Serializable
data class ToolStep(
    val text: String = "",
    val calls: List<ToolCallRecord> = emptyList(),
    val results: List<ToolResultRecord> = emptyList(),
)

@Immutable
@Serializable
data class MessageMetrics(
    val turns: Int = 0,
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("cached_tokens") val cachedTokens: Long? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
    @SerialName("generation_tokens_per_second") val generationTokensPerSecond: Double? = null,
    @SerialName("prompt_tokens_per_second") val promptTokensPerSecond: Double? = null,
    @SerialName("rate_source") val rateSource: String? = null,
    @SerialName("rate_engine") val rateEngine: String? = null,
    @SerialName("ttft_ms") val ttftMs: Double? = null,
    @SerialName("think_ms") val thinkMs: Double? = null,
    @SerialName("prefill_ms") val prefillMs: Double? = null,
    @SerialName("tool_ms") val toolMs: Double = 0.0,
    @SerialName("tool_calls") val toolCalls: Int = 0,
    @SerialName("total_duration_ms") val totalDurationMs: Double? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("rate_unmeasurable") val rateUnmeasurable: Boolean = false,
    @SerialName("context_used") val contextUsed: Long? = null,
    @SerialName("context_window") val contextWindow: Int = 8192,
    @SerialName("tokens_estimated") val tokensEstimated: Boolean = false,
    @SerialName("input_estimated") val inputEstimated: Boolean? = null,
    @SerialName("output_estimated") val outputEstimated: Boolean? = null,
    @SerialName("context_estimated") val contextEstimated: Boolean? = null,
    @SerialName("gen_window_ms") val genWindowMs: Double? = null,
    @SerialName("prompt_window_ms") val promptWindowMs: Double? = null,
    @SerialName("prompt_rate_source") val promptRateSource: String? = null,
    val partial: Boolean = false,
    val live: Boolean = false,
)

@Immutable
@Serializable
data class MessageBranch(
    val content: String = "",
    val thinking: String = "",
    val thinkingComplete: Boolean = true,
    @SerialName("_search") val search: SearchData? = null,
    @SerialName("_steps") val steps: List<ToolStep>? = null,
    @SerialName("_metrics") val metrics: MessageMetrics? = null,
    @SerialName("_inTokens") val inTokens: Long? = null,
    @SerialName("_outTokens") val outTokens: Long? = null,
)

@Immutable
@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val thinking: String = "",
    val thinkingComplete: Boolean = true,
    @SerialName("_apiContent") val apiContent: JsonElement? = null,
    @SerialName("_search") val search: SearchData? = null,
    @SerialName("_sourcesOpen") val sourcesOpen: Boolean = false,
    @SerialName("_steps") val steps: List<ToolStep>? = null,
    @SerialName("_metrics") val metrics: MessageMetrics? = null,
    @SerialName("_inTokens") val inTokens: Long? = null,
    @SerialName("_outTokens") val outTokens: Long? = null,
    @SerialName("_initialLabel") val initialLabel: String? = null,
) {
    fun toBranchSnapshot(): MessageBranch = MessageBranch(
        content = content,
        thinking = thinking,
        thinkingComplete = thinkingComplete,
        search = search,
        steps = steps,
        metrics = metrics,
        inTokens = inTokens,
        outTokens = outTokens,
    )

    fun withBranchRestored(branch: MessageBranch): ChatMessage = copy(
        content = branch.content,
        thinking = branch.thinking,
        thinkingComplete = branch.thinkingComplete,
        search = branch.search,
        steps = branch.steps,
        metrics = branch.metrics,
        inTokens = branch.inTokens,
        outTokens = branch.outTokens,
    )

    fun extractImageDataUrls(): List<String> {
        val arr = apiContent as? JsonArray ?: return emptyList()
        return arr.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "image_url") {
                obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }
    }
}

@Immutable
@Serializable
data class Chat(
    val id: String = generateChatId(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinnedAt: Long? = null,
    val metrics: MessageMetrics? = null,
    val incognito: Boolean = false,
    @SerialName("_agentMode") val agentMode: Boolean = false,
    @SerialName("_branches") val branches: Map<String, List<MessageBranch>> = emptyMap(),
    @SerialName("_branchActive") val branchActive: Map<String, Int> = emptyMap(),
    @SerialName("_liveReplies") val liveReplies: Map<String, MessageBranch> = emptyMap(),
) {
    fun recency(): Long = if (updatedAt > 0L) updatedAt else createdAt
}

@Immutable
data class PendingFile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val data: String,
    val size: Long = 0L,
) {
    val isImage: Boolean get() = type.startsWith("image/")
}

data class UserMessagePayload(
    val apiContent: JsonElement,
    val displayContent: String,
)

private val WHITESPACE_COLLAPSE_RE = Regex("\\s+")
private val ATTACHED_STRIP_RE = Regex("\\n*\\[Attached:[^\\]]*]\\s*$")
private val ATTACHED_EXTRACT_RE = Regex("\\[Attached:\\s*([^\\]]*)]")
private val DATA_URL_MIME_RE = Regex("^data:([^;,]+)[;,]")
private val EXT_MIME_MAP = mapOf(
    "txt" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "json" to "application/json",
    "py" to "text/x-python",
    "js" to "text/javascript",
    "ts" to "text/typescript",
    "html" to "text/html",
    "css" to "text/css",
)

fun generateChatId(): String {
    val timePart = System.currentTimeMillis().toString(36)
    val randPart = UUID.randomUUID().toString().replace("-", "").take(6)
    return timePart + randPart
}

fun deriveTitle(text: String): String {
    val clean = text.replace(WHITESPACE_COLLAPSE_RE, " ").trim()
    return if (clean.length > 42) clean.take(42) + "\u2026" else clean
}

fun stripAttachmentMarker(text: String): String =
    text.replace(ATTACHED_STRIP_RE, "").trim()

fun restorePendingFilesFromMessage(msg: ChatMessage): List<PendingFile> {
    val api = msg.apiContent as? JsonArray ?: return emptyList()
    val markerMatch = ATTACHED_EXTRACT_RE.find(msg.content)
    val markerNames = markerMatch?.groupValues?.getOrNull(1)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    val files = mutableListOf<PendingFile>()
    var mi = 0
    for (partEl in api) {
        val part = partEl as? JsonObject ?: continue
        val type = part["type"]?.jsonPrimitive?.contentOrNull
        if (type == "image_url") {
            val url = part["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: continue
            val mime = DATA_URL_MIME_RE.find(url)?.groupValues?.getOrNull(1) ?: "image/png"
            val ext = if (mime == "image/png") ".png" else ".jpg"
            val imgName = markerNames.getOrNull(mi) ?: "Image ${files.size + 1}$ext"
            files += PendingFile(name = imgName, type = mime, data = url, size = 0L)
            mi++
        } else if (type == "text") {
            val txt = part["text"]?.jsonPrimitive?.contentOrNull ?: continue
            if (txt.startsWith("[File: ")) {
                val body = txt.removePrefix("[File: ")
                val nl = body.indexOf('\n')
                val fname = if (nl == -1) body else body.substring(0, nl).removeSuffix("]")
                val fdata = if (nl == -1) "" else body.substring(nl + 1)
                val ext = fname.substringAfterLast('.', "").lowercase()
                val ftype = EXT_MIME_MAP[ext] ?: "text/plain"
                files += PendingFile(name = fname, type = ftype, data = fdata, size = 0L)
                mi++
            }
        }
    }
    return files
}

fun buildUserMessagePayload(
    text: String,
    files: List<PendingFile>,
    activeTemplate: PromptTemplate? = null,
    skipPrefix: Boolean = false,
): UserMessagePayload {
    val prefixed = if (!skipPrefix && !activeTemplate?.prefix.isNullOrEmpty()) {
        activeTemplate!!.prefix + text
    } else {
        text
    }
    if (files.isEmpty()) {
        return UserMessagePayload(
            apiContent = JsonPrimitive(prefixed),
            displayContent = text,
        )
    }
    val parts = mutableListOf<JsonObject>()
    if (prefixed.isNotEmpty()) {
        parts += buildJsonObject {
            put("type", "text")
            put("text", prefixed)
        }
    }
    val fileNames = mutableListOf<String>()
    for (f in files) {
        fileNames += f.name
        if (f.isImage) {
            parts += buildJsonObject {
                put("type", "image_url")
                put(
                    "image_url",
                    buildJsonObject {
                        put("url", f.data)
                        put("detail", "auto")
                    },
                )
            }
        } else {
            parts += buildJsonObject {
                put("type", "text")
                put("text", "[File: ${f.name}]\n${f.data}")
            }
        }
    }
    val apiEl: JsonElement = if (parts.size == 1 && parts[0]["type"]?.jsonPrimitive?.contentOrNull == "text") {
        JsonPrimitive(parts[0]["text"]?.jsonPrimitive?.contentOrNull ?: "")
    } else {
        buildJsonArray { parts.forEach { add(it) } }
    }
    val attachedLabel = "[Attached: ${fileNames.joinToString(", ")}]"
    val display = if (text.isNotEmpty()) "$text\n\n$attachedLabel" else attachedLabel
    return UserMessagePayload(apiContent = apiEl, displayContent = display)
}

fun stepToApiMessages(step: ToolStep): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    out += buildJsonObject {
        put("role", "assistant")
        if (step.text.isNotEmpty()) put("content", step.text) else put("content", kotlinx.serialization.json.JsonNull)
        put(
            "tool_calls",
            buildJsonArray {
                step.calls.forEach { tc ->
                    add(
                        buildJsonObject {
                            put("id", tc.id)
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", tc.argsText.ifBlank { "{}" })
                                },
                            )
                        },
                    )
                }
            },
        )
    }
    step.results.forEach { r ->
        out += buildJsonObject {
            put("role", "tool")
            put("tool_call_id", r.id)
            put("name", r.name)
            put("content", r.content)
        }
    }
    return out
}

fun messageToApiMessages(m: ChatMessage): List<JsonObject> {
    if (m.role == "assistant" && !m.steps.isNullOrEmpty()) {
        val out = mutableListOf<JsonObject>()
        m.steps.forEach { out += stepToApiMessages(it) }
        if (m.content.isNotEmpty()) {
            out += buildJsonObject {
                put("role", "assistant")
                put("content", m.content)
            }
        }
        return out
    }
    return listOf(
        buildJsonObject {
            put("role", m.role)
            put("content", m.apiContent ?: JsonPrimitive(m.content))
        },
    )
}

fun migrateChatBranches(chat: Chat): Chat {
    if (chat.branches.isEmpty()) return chat
    val msgs = chat.messages
    val normalized = linkedMapOf<String, MutableList<MessageBranch>>()
    for ((rawKey, arr) in chat.branches) {
        var key = rawKey.toIntOrNull() ?: continue
        if (key < 0 || arr.isEmpty()) continue
        if (key < msgs.size && msgs[key].role == "assistant" && key > 0 && msgs[key - 1].role == "user") {
            key -= 1
        }
        if (key >= msgs.size) continue
        val bucket = normalized.getOrPut(key.toString()) { mutableListOf() }
        bucket.addAll(arr)
    }
    val newActive = linkedMapOf<String, Int>()
    for ((rawAk, rawAv) in chat.branchActive) {
        var ak = rawAk.toIntOrNull() ?: continue
        if (ak < msgs.size && msgs[ak].role == "assistant" && ak > 0 && msgs[ak - 1].role == "user") {
            ak -= 1
        }
        val len = normalized[ak.toString()]?.size ?: 0
        if (len == 0) continue
        val clamped = if (rawAv < 0 || rawAv > len) len else rawAv
        newActive[ak.toString()] = clamped
    }
    return chat.copy(
        branches = normalized.mapValues { it.value.toList() },
        branchActive = newActive,
    )
}

val ChatComparator: Comparator<Chat> = Comparator { a, b ->
    val pa = if (a.pinnedAt != null) 1 else 0
    val pb = if (b.pinnedAt != null) 1 else 0
    if (pa != pb) return@Comparator pb - pa
    val ra = a.recency()
    val rb = b.recency()
    if (ra != rb) return@Comparator rb.compareTo(ra)
    b.createdAt.compareTo(a.createdAt)
}

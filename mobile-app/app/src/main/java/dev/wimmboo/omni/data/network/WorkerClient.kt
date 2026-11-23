package dev.wimmboo.omni.data.network

import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.data.model.ToolCallRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

data class SandboxSession(
    val provider: String,
    val sandboxId: String,
    val domain: String,
    val token: String,
)

class WorkerClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.SECONDS) // streaming connections stay open
                writeTimeout(60, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
    }

    private fun normalizedEndpoint(settings: OmniSettings): String =
        settings.apiEndpoint.trim().trimEnd('/')

    private fun io.ktor.client.request.HttpRequestBuilder.applyWorkerHeaders(
        settings: OmniSettings,
        accept: String? = null,
    ) {
        contentType(ContentType.Application.Json)
        if (accept != null) header(HttpHeaders.Accept, accept)
        val proxy = settings.proxyKey.trim()
        if (proxy.isNotEmpty()) header("X-Omni-Proxy-Key", proxy)
        val origin = settings.appOrigin.trim()
        if (origin.isNotEmpty()) header(HttpHeaders.Origin, origin)
    }

    /**
     * Streams NDJSON / SSE events from POST {endpoint}/api/chat as a cold Flow.
     * Cancelling collection cancels the underlying OkHttp call immediately.
     */
    fun streamChat(
        settings: OmniSettings,
        requestBody: JsonObject,
    ): Flow<WorkerEvent> = flow {
        val url = normalizedEndpoint(settings) + "/api/chat"
        val bodyText = json.encodeToString(JsonObject.serializer(), requestBody)

        client.preparePost(url) {
            applyWorkerHeaders(
                settings = settings,
                accept = "application/x-ndjson, application/json, text/event-stream",
            )
            setBody(bodyText)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errBody = try {
                    response.bodyAsText()
                } catch (_: Exception) {
                    response.status.description
                }
                val parsedErr = extractJsonErrorMessage(errBody) ?: errBody.ifBlank { response.status.description }
                throw WorkerHttpException(response.status.value, parsedErr)
            }

            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                val ev = WorkerStreamParser.parseLine(line) ?: continue
                emit(ev)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun sandboxCreate(settings: OmniSettings): SandboxSession = withContext(Dispatchers.IO) {
        val prov = settings.activeSandboxProviderId()
        val key = settings.activeSandboxKey(prov)
        val payload = buildJsonObject {
            put("sandboxProvider", prov)
            put("sandboxKey", key)
            if (prov == "e2b" && key.isNotEmpty()) put("e2bKey", key)
            if (settings.proxyKey.isNotBlank()) put("proxyKey", settings.proxyKey.trim())
            put("timeoutSec", settings.agentSandboxTimeout)
        }
        val res = client.post(normalizedEndpoint(settings) + "/sandbox/create") {
            applyWorkerHeaders(settings)
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
        val text = res.bodyAsText()
        val obj = parseObj(text)
        val errMsg = extractErrorFromObj(obj)
        if (!res.status.isSuccess() || errMsg != null) {
            throw RuntimeException(errMsg ?: "Could not start sandbox (HTTP ${res.status.value})")
        }
        SandboxSession(
            provider = obj?.get("provider")?.jsonPrimitive?.contentOrNull ?: prov,
            sandboxId = obj?.get("sandboxId")?.jsonPrimitive?.contentOrNull
                ?: throw RuntimeException("Sandbox create returned no sandboxId"),
            domain = obj["domain"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            token = obj["token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    suspend fun sandboxKeepalive(settings: OmniSettings, sb: SandboxSession): Boolean = withContext(Dispatchers.IO) {
        try {
            val prov = sb.provider.ifBlank { settings.activeSandboxProviderId() }
            val key = settings.activeSandboxKey(prov)
            val payload = buildJsonObject {
                put("sandboxProvider", prov)
                put("sandboxKey", key)
                if (prov == "e2b" && key.isNotEmpty()) put("e2bKey", key)
                if (settings.proxyKey.isNotBlank()) put("proxyKey", settings.proxyKey.trim())
                put("sandboxId", sb.sandboxId)
                put("timeoutSec", settings.agentSandboxTimeout)
            }
            val res = client.post(normalizedEndpoint(settings) + "/sandbox/keepalive") {
                applyWorkerHeaders(settings)
                setBody(json.encodeToString(JsonObject.serializer(), payload))
            }
            val obj = parseObj(res.bodyAsText())
            res.status.isSuccess() && (obj?.get("ok")?.jsonPrimitive?.booleanOrNull == true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sandboxKill(settings: OmniSettings, sb: SandboxSession) = withContext(Dispatchers.IO) {
        try {
            val prov = sb.provider.ifBlank { settings.activeSandboxProviderId() }
            val key = settings.activeSandboxKey(prov)
            val payload = buildJsonObject {
                put("sandboxProvider", prov)
                put("sandboxKey", key)
                if (prov == "e2b" && key.isNotEmpty()) put("e2bKey", key)
                if (settings.proxyKey.isNotBlank()) put("proxyKey", settings.proxyKey.trim())
                put("sandboxId", sb.sandboxId)
                if (sb.domain.isNotBlank()) put("domain", sb.domain)
            }
            client.post(normalizedEndpoint(settings) + "/sandbox/kill") {
                applyWorkerHeaders(settings)
                setBody(json.encodeToString(JsonObject.serializer(), payload))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }

    suspend fun sandboxExec(
        settings: OmniSettings,
        sb: SandboxSession,
        code: String,
        language: String,
    ): JsonObject = withContext(Dispatchers.IO) {
        val prov = sb.provider.ifBlank { settings.activeSandboxProviderId() }
        val key = settings.activeSandboxKey(prov)
        val payload = buildJsonObject {
            put("sandboxProvider", prov)
            put("sandboxKey", key)
            if (prov == "e2b" && key.isNotEmpty()) put("e2bKey", key)
            if (settings.proxyKey.isNotBlank()) put("proxyKey", settings.proxyKey.trim())
            put("sandboxId", sb.sandboxId)
            if (sb.domain.isNotBlank()) put("domain", sb.domain)
            if (sb.token.isNotBlank()) put("token", sb.token)
            put("code", code)
            put("language", language)
        }
        val res = client.post(normalizedEndpoint(settings) + "/sandbox/exec") {
            applyWorkerHeaders(settings)
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
        parseObj(res.bodyAsText()) ?: buildJsonObject {
            put("error", buildJsonObject { put("message", "Sandbox exec returned unreadable response (HTTP ${res.status.value})") })
        }
    }

    suspend fun executeAgentTool(
        settings: OmniSettings,
        sb: SandboxSession,
        call: ToolCallRecord,
    ): String {
        return try {
            val data: JsonObject = when (call.name) {
                "run_python" -> {
                    val code = call.args["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    sandboxExec(settings, sb, code, "python")
                }
                "run_bash" -> {
                    val script = call.args["script"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    sandboxExec(settings, sb, script, "bash")
                }
                "write_file" -> {
                    val wpath = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(call.args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    val wbody = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(call.args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    val py = "import os,pathlib\np=$wpath\nos.makedirs(os.path.dirname(p) or \".\",exist_ok=True)\n" +
                        "pathlib.Path(p).write_text($wbody)\nprint(\"wrote\",p,len($wbody),\"bytes\")"
                    sandboxExec(settings, sb, py, "python")
                }
                "read_file" -> {
                    val rpath = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(call.args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    val py = "import pathlib\nprint(pathlib.Path($rpath).read_text())"
                    sandboxExec(settings, sb, py, "python")
                }
                else -> return "Error: unknown tool \"${call.name}\""
            }
            formatToolResult(data)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            "Error: ${e.message ?: "tool execution failed"}"
        }
    }

    fun formatToolResult(data: JsonObject): String {
        val err = extractErrorFromObj(data)
        if (err != null) return "Error: $err"
        val sb = StringBuilder()
        val stdout = data["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val stderr = data["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val exitCode = data["exit_code"]?.jsonPrimitive?.intOrNull
        val timedOut = data["timed_out"]?.jsonPrimitive?.booleanOrNull == true
        val truncated = data["truncated"]?.jsonPrimitive?.booleanOrNull == true
        if (stdout.isNotEmpty()) sb.append(stdout)
        if (stderr.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("[stderr]\n").append(stderr)
        }
        if (exitCode != null && exitCode != 0) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("[exit code ").append(exitCode).append(']')
        }
        if (timedOut) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("[timed out]")
        }
        if (truncated) sb.append("\n[output truncated]")
        return sb.toString().ifEmpty { "(no output)" }
    }

    private fun parseObj(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun extractErrorFromObj(obj: JsonObject?): String? {
        val err = obj?.get("error") ?: return null
        return when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull ?: "sandbox call failed"
            is JsonPrimitive -> err.contentOrNull ?: "sandbox call failed"
            else -> "sandbox call failed"
        }
    }

    private fun extractJsonErrorMessage(raw: String): String? =
        extractErrorFromObj(parseObj(raw))

    fun close() {
        client.close()
    }
}

class WorkerHttpException(val statusCode: Int, val detail: String) :
    RuntimeException("HTTP $statusCode: $detail")

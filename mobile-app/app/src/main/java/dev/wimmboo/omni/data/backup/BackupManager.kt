package dev.wimmboo.omni.data.backup

import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.data.model.migrateChatBranches
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Serializable
data class WebChatsExport(
    val version: Int = 1,
    val exportedAt: Long,
    val chats: List<Chat>,
    val activeChatId: String? = null,
)

@Serializable
data class WebSettingsExport(
    val version: Int = 1,
    val kind: String = "omni-settings",
    val exportedAt: Long,
    val settings: OmniSettings,
)

sealed class ChatImportResult {
    data class Success(val newChats: List<Chat>, val skippedCount: Int) : ChatImportResult()
    data class Error(val message: String) : ChatImportResult()
}

sealed class SettingsImportResult {
    data class Success(val settings: OmniSettings) : SettingsImportResult()
    data class Error(val message: String) : SettingsImportResult()
}

/**
 * 100% web-compatible JSON export/import matching index.html's exact schemas:
 * - Chats:    { version: 1, exportedAt, chats, activeChatId } (never includes API keys or incognito chats)
 * - Settings: { version: 1, kind: "omni-settings", exportedAt, settings }
 */
object BackupManager {
    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val importJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun defaultChatsFilename(nowMs: Long = System.currentTimeMillis()): String =
        "omni-chats-${isoDate(nowMs)}.json"

    fun defaultSettingsFilename(nowMs: Long = System.currentTimeMillis()): String =
        "omni-settings-${isoDate(nowMs)}.json"

    private fun isoDate(nowMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(nowMs))
    }

    fun exportChatsJson(
        chats: List<Chat>,
        activeChatId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val persistedOnly = chats.filter { !it.incognito }.map { it.copy(incognito = false) }
        val active = activeChatId?.takeIf { id -> persistedOnly.any { it.id == id } }
        val payload = WebChatsExport(
            version = 1,
            exportedAt = nowMs,
            chats = persistedOnly,
            activeChatId = active,
        )
        return exportJson.encodeToString(WebChatsExport.serializer(), payload)
    }

    fun importChatsJson(
        raw: String,
        existingChatIds: Set<String>,
    ): ChatImportResult {
        val root = try {
            importJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return ChatImportResult.Error("Invalid chat export file")

        val kind = root["kind"]?.jsonPrimitive?.content
        if (kind == "omni-settings") {
            return ChatImportResult.Error("That file is a settings export")
        }

        val chatsEl = root["chats"] as? JsonArray
            ?: return ChatImportResult.Error("Invalid chat export file")

        val parsed = mutableListOf<Chat>()
        var skipped = 0
        for (el in chatsEl) {
            val chat = try {
                importJson.decodeFromJsonElement(Chat.serializer(), el)
            } catch (_: Exception) {
                continue
            }
            if (chat.id.isBlank() || chat.incognito) continue
            if (chat.id in existingChatIds || parsed.any { it.id == chat.id }) {
                skipped++
                continue
            }
            parsed += migrateChatBranches(chat.copy(incognito = false))
        }
        return ChatImportResult.Success(newChats = parsed, skippedCount = skipped)
    }

    fun exportSettingsJson(
        settings: OmniSettings,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val payload = WebSettingsExport(
            version = 1,
            kind = "omni-settings",
            exportedAt = nowMs,
            settings = settings.normalized(),
        )
        return exportJson.encodeToString(WebSettingsExport.serializer(), payload)
    }

    fun importSettingsJson(raw: String): SettingsImportResult {
        val root = try {
            importJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return SettingsImportResult.Error("Invalid settings export file")

        if (root["chats"] is JsonArray && root["settings"] !is JsonObject) {
            return SettingsImportResult.Error("That file is a chat export")
        }

        val kind = root["kind"]?.jsonPrimitive?.content
        val version = root["version"]?.jsonPrimitive?.intOrNull
        val settingsObj = root["settings"] as? JsonObject
        if (kind != "omni-settings" || version != 1 || settingsObj == null) {
            return SettingsImportResult.Error("Invalid settings export file")
        }

        return try {
            val decoded = importJson.decodeFromJsonElement(OmniSettings.serializer(), settingsObj)
            SettingsImportResult.Success(decoded.normalized())
        } catch (_: Exception) {
            SettingsImportResult.Error("Invalid settings export file")
        }
    }
}

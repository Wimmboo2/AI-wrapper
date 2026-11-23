package dev.wimmboo.omni

import dev.wimmboo.omni.data.backup.BackupManager
import dev.wimmboo.omni.data.backup.ChatImportResult
import dev.wimmboo.omni.data.backup.SettingsImportResult
import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.ChatMessage
import dev.wimmboo.omni.data.model.CustomModel
import dev.wimmboo.omni.data.model.MessageBranch
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.data.model.QuickCustomModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    @Test
    fun settingsJsonMatchesWebSchemaAndRoundTrips() {
        val original = OmniSettings(
            apiEndpoint = "https://omni-api.wimmboo.workers.dev",
            provider = "anthropic",
            apiKey = "sk-ant-secret",
            apiKeys = mapOf("anthropic" to "sk-ant-secret", "openai" to "sk-openai-secret"),
            model = "claude-sonnet-5-5",
            temperature = 0.45,
            maxTokens = 4096,
            thinking = true,
            effort = "high",
            searchMode = "on",
            searchBackend = "tavily",
            searchDepth = "advanced",
            tavilyKey = "tvly-secret",
            proxyKey = "proxy-secret",
            customBaseURL = "https://my.endpoint/v1/chat/completions",
            customModels = listOf(CustomModel(id = "my-model", name = "My Model", ctx = 65536)),
            quickCustomModels = listOf(QuickCustomModel(provider = "openai", id = "gpt-custom", name = "Custom GPT", ctx = 131072)),
            systemPrompt = "Be concise.",
            sandboxProvider = "novita",
            sandboxKeys = mapOf("e2b" to "e2b-secret", "novita" to "nvta-secret"),
            e2bKey = "e2b-secret",
            agentMaxSteps = 12,
        )

        val exported = BackupManager.exportSettingsJson(original, nowMs = 1700000000000L)
        assertTrue(exported.contains(""""kind": "omni-settings""""))
        assertTrue(exported.contains(""""version": 1"""))
        assertTrue(exported.contains(""""quickCustomModels""""))
        assertTrue(exported.contains("sk-ant-secret"))

        val imported = BackupManager.importSettingsJson(exported)
        assertTrue(imported is SettingsImportResult.Success)
        val restored = (imported as SettingsImportResult.Success).settings
        assertEquals("anthropic", restored.provider)
        assertEquals("sk-ant-secret", restored.effectiveApiKey("anthropic"))
        assertEquals("sk-openai-secret", restored.effectiveApiKey("openai"))
        assertEquals("tvly-secret", restored.tavilyKey)
        assertEquals("proxy-secret", restored.proxyKey)
        assertEquals("nvta-secret", restored.activeSandboxKey("novita"))
        assertEquals("e2b-secret", restored.activeSandboxKey("e2b"))
        assertEquals(12, restored.agentMaxSteps)
        assertEquals(1, restored.quickCustomModels.size)
        // Secrets must never appear in toString()
        assertFalse(restored.toString().contains("sk-ant-secret"))
        assertFalse(restored.toString().contains("tvly-secret"))
    }

    @Test
    fun importsRealWebExportFormatAndRemapsLegacyProviders() {
        val webJson = """
            {
              "version": 1,
              "kind": "omni-settings",
              "exportedAt": 1730000000000,
              "settings": {
                "apiEndpoint": "",
                "provider": "groq",
                "apiKey": "gsk-old",
                "apiKeys": { "openai": "sk-live" },
                "model": "llama-old",
                "temperature": 0.9,
                "maxTokens": 8192,
                "thinking": false,
                "effort": "max",
                "searchMode": "auto",
                "searchBackend": "auto",
                "searchDepth": "basic",
                "tavilyKey": "tvly-web",
                "proxyKey": "",
                "customBaseURL": "",
                "customModels": [],
                "quickCustomModels": [],
                "systemPrompt": "Hello"
              }
            }
        """.trimIndent()

        val result = BackupManager.importSettingsJson(webJson)
        assertTrue(result is SettingsImportResult.Success)
        val s = (result as SettingsImportResult.Success).settings
        assertEquals("https://omni-api.wimmboo.workers.dev", s.apiEndpoint)
        assertEquals("openai", s.provider)
        assertEquals("gpt-6.1-sol", s.model)
        assertEquals("tvly-web", s.tavilyKey)
        assertTrue(s.promptTemplates.any { it.key == "search" })
    }

    @Test
    fun chatsExportExcludesIncognitoAndKeysAndCrossRejects() {
        val multipart = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "Look at this") })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/png;base64,AAAA") })
            })
        }
        val persisted = Chat(
            id = "chat_1",
            title = "First chat",
            messages = listOf(
                ChatMessage(role = "user", content = "Look at this\n\n[Attached: pic.png]", apiContent = multipart),
                ChatMessage(role = "assistant", content = "Nice image!"),
            ),
            branches = mapOf("0" to listOf(MessageBranch(content = "Old reply"))),
            branchActive = mapOf("0" to 1),
        )
        val incognito = Chat(
            id = "chat_incog",
            title = "Secret",
            messages = listOf(ChatMessage(role = "user", content = "private")),
            incognito = true,
        )

        val chatsJson = BackupManager.exportChatsJson(listOf(persisted, incognito), activeChatId = "chat_1")
        assertFalse(chatsJson.contains("chat_incog"))
        assertFalse(chatsJson.contains("apiKey"))
        assertTrue(chatsJson.contains(""""activeChatId": "chat_1""""))

        // Cross-file guard: importing a chat file into settings must say so
        val wrongTarget = BackupManager.importSettingsJson(chatsJson)
        assertTrue(wrongTarget is SettingsImportResult.Error)
        assertEquals("That file is a chat export", (wrongTarget as SettingsImportResult.Error).message)

        // Round-trip chat import
        val imported = BackupManager.importChatsJson(chatsJson, existingChatIds = emptySet())
        assertTrue(imported is ChatImportResult.Success)
        val list = (imported as ChatImportResult.Success).newChats
        assertEquals(1, list.size)
        assertEquals("chat_1", list[0].id)
        assertTrue(list[0].messages[0].apiContent is JsonArray)
        assertEquals("Old reply", list[0].branches["0"]?.first()?.content)

        // Deduplication on second import
        val dedup = BackupManager.importChatsJson(chatsJson, existingChatIds = setOf("chat_1"))
        assertEquals(0, (dedup as ChatImportResult.Success).newChats.size)
        assertEquals(1, dedup.skippedCount)

        // Legacy string _apiContent also imports cleanly
        val webChatJson = """
            {
              "version": 1,
              "exportedAt": 1730000000000,
              "chats": [
                {
                  "id": "web_99",
                  "title": "From web",
                  "messages": [
                    { "role": "user", "content": "hi", "_apiContent": "hi" },
                    { "role": "assistant", "content": "hello" }
                  ],
                  "createdAt": 1730000000000
                }
              ]
            }
        """.trimIndent()
        val webImport = BackupManager.importChatsJson(webChatJson, existingChatIds = emptySet())
        assertTrue(webImport is ChatImportResult.Success)
        val webChat = (webImport as ChatImportResult.Success).newChats.single()
        assertEquals("web_99", webChat.id)
        assertEquals(JsonPrimitive("hi"), webChat.messages[0].apiContent)
    }
}

package dev.wimmboo.omni.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.data.model.SecretsBundle
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.omniSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omni_settings_store",
)

/**
 * Persists non-secret settings in DataStore and encrypts all API keys
 * (provider keys, worker proxy key, Tavily key, E2B/Novita sandbox keys)
 * with Android Keystore AES-GCM before writing them to DataStore.
 */
class SettingsStore(
    private val context: Context,
    private val crypto: OmniKeystoreCrypto = OmniKeystoreCrypto(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    suspend fun loadSettings(): OmniSettings {
        val prefs = context.omniSettingsDataStore.data.first()
        val publicRaw = prefs[KEY_PUBLIC_SETTINGS]
        val publicSettings = if (publicRaw != null) {
            try {
                json.decodeFromString(OmniSettings.serializer(), publicRaw)
            } catch (_: Exception) {
                OmniSettings()
            }
        } else {
            OmniSettings()
        }

        val encSecrets = prefs[KEY_ENCRYPTED_SECRETS]
        val secrets = if (encSecrets != null) {
            val plain = crypto.decrypt(encSecrets)
            if (plain != null) {
                try {
                    json.decodeFromString(SecretsBundle.serializer(), plain)
                } catch (_: Exception) {
                    SecretsBundle()
                }
            } else {
                SecretsBundle()
            }
        } else {
            SecretsBundle()
        }

        return publicSettings.copy(
            apiKey = secrets.apiKey,
            apiKeys = secrets.apiKeys,
            tavilyKey = secrets.tavilyKey,
            proxyKey = secrets.proxyKey,
            e2bKey = secrets.e2bKey,
            sandboxKeys = secrets.sandboxKeys,
        ).normalized()
    }

    suspend fun saveSettings(settings: OmniSettings) {
        val norm = settings.normalized()
        val publicOnly = norm.copy(
            apiKey = "",
            apiKeys = emptyMap(),
            tavilyKey = "",
            proxyKey = "",
            e2bKey = "",
            sandboxKeys = emptyMap(),
        )
        val secrets = SecretsBundle(
            apiKey = norm.apiKey,
            apiKeys = norm.apiKeys,
            tavilyKey = norm.tavilyKey,
            proxyKey = norm.proxyKey,
            e2bKey = norm.e2bKey,
            sandboxKeys = norm.sandboxKeys,
        )
        val publicJson = json.encodeToString(OmniSettings.serializer(), publicOnly)
        val secretsJson = json.encodeToString(SecretsBundle.serializer(), secrets)
        val encrypted = crypto.encrypt(secretsJson)

        context.omniSettingsDataStore.edit { prefs ->
            prefs[KEY_PUBLIC_SETTINGS] = publicJson
            prefs[KEY_ENCRYPTED_SECRETS] = encrypted
        }
    }

    suspend fun loadActiveChatId(): String? {
        val prefs = context.omniSettingsDataStore.data.first()
        return prefs[KEY_ACTIVE_CHAT_ID]?.takeIf { it.isNotBlank() }
    }

    suspend fun saveActiveChatId(chatId: String?) {
        context.omniSettingsDataStore.edit { prefs ->
            if (chatId.isNullOrBlank()) prefs.remove(KEY_ACTIVE_CHAT_ID)
            else prefs[KEY_ACTIVE_CHAT_ID] = chatId
        }
    }

    companion object {
        private val KEY_PUBLIC_SETTINGS = stringPreferencesKey("public_settings_json")
        private val KEY_ENCRYPTED_SECRETS = stringPreferencesKey("encrypted_secrets_v1")
        private val KEY_ACTIVE_CHAT_ID = stringPreferencesKey("active_chat_id")
    }
}

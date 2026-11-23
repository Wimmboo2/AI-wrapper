package dev.wimmboo.omni.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.data.model.SecretsBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val Context.omniSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omni_settings_store",
)

/**
 * Persists non-secret settings in DataStore and encrypts all API keys
 * (provider keys, worker proxy key, Tavily key, E2B/Novita sandbox keys)
 * with Android Keystore AES-GCM before writing them to DataStore.
 * Caches decrypted secrets in memory so non-secret updates (model switch,
 * effort change, thinking toggle) never re-invoke Keystore encryption.
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

    @Volatile
    private var cachedCiphertext: String? = null

    @Volatile
    private var cachedSecrets: SecretsBundle? = null

    suspend fun loadSettings(): OmniSettings = withContext(Dispatchers.IO) {
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
            if (encSecrets == cachedCiphertext && cachedSecrets != null) {
                cachedSecrets!!
            } else {
                val plain = crypto.decrypt(encSecrets)
                val decoded = if (plain != null) {
                    try {
                        json.decodeFromString(SecretsBundle.serializer(), plain)
                    } catch (_: Exception) {
                        SecretsBundle()
                    }
                } else {
                    SecretsBundle()
                }
                cachedCiphertext = encSecrets
                cachedSecrets = decoded
                decoded
            }
        } else {
            SecretsBundle()
        }

        publicSettings.copy(
            apiKey = secrets.apiKey,
            apiKeys = secrets.apiKeys,
            tavilyKey = secrets.tavilyKey,
            proxyKey = secrets.proxyKey,
            e2bKey = secrets.e2bKey,
            sandboxKeys = secrets.sandboxKeys,
        ).normalized()
    }

    suspend fun saveSettings(settings: OmniSettings) = withContext(Dispatchers.IO) {
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
        val encrypted = if (secrets == cachedSecrets && cachedCiphertext != null) {
            cachedCiphertext!!
        } else {
            val secretsJson = json.encodeToString(SecretsBundle.serializer(), secrets)
            val enc = crypto.encrypt(secretsJson)
            cachedSecrets = secrets
            cachedCiphertext = enc
            enc
        }

        context.omniSettingsDataStore.edit { prefs ->
            prefs[KEY_PUBLIC_SETTINGS] = publicJson
            prefs[KEY_ENCRYPTED_SECRETS] = encrypted
        }
    }

    suspend fun loadActiveChatId(): String? = withContext(Dispatchers.IO) {
        val prefs = context.omniSettingsDataStore.data.first()
        prefs[KEY_ACTIVE_CHAT_ID]?.takeIf { it.isNotBlank() }
    }

    suspend fun saveActiveChatId(chatId: String?) = withContext(Dispatchers.IO) {
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

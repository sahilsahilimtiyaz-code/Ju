package com.brahma.jarvis.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/** Non-secret configuration. API keys NEVER live here - KeyVault keeps them encrypted. */
data class JarvisSettings(
    val openAiBaseUrl: String = "https://openrouter.ai/api/v1",
    val openAiModelFast: String = "",
    val openAiModelStrong: String = "",
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    val anthropicModelStrong: String = "claude-sonnet-4-20250514",
    val llmChain: String = "openai,anthropic",
    val remoteBrainUrl: String = "",
    val remoteBrainToken: String = "",
    val personaName: String = "Jarves",
    val sirAddress: String = "sir",
    val initiativeLevel: Int = 2,
    val quietHoursStart: Int = 23,
    val quietHoursEnd: Int = 7,
    val proactiveDailyBudget: Int = 8,
    val wakeEnabled: Boolean = false,
    val wakePhrases: String = "hey jarves,jarves,wake up jarves,hey jarvis",
    val wakeSensitivity: Int = 50,
    val ttsPrimary: Int = 0,
    val ttsFishRefVoice: String = "",
    val ttsElevenVoiceId: String = "",
    val ttsGenericBaseUrl: String = "",
    val ttsGenericHeadersJson: String = "{}",
    val ttsGenericBodyTemplate: String = "{\"text\":\"{{text}}\",\"format\":\"mp3\"}",
    val ttsGenericAudioFormat: String = "mp3",
    val ttsLengthLimitChars: Int = 800,
    val sttPrimary: Int = 0,
    val sttGenericBaseUrl: String = "",
    val sttGenericHeadersJson: String = "{}",
    val sttDeepgramModel: String = "nova-2",
    val evolutionEnabled: Boolean = true,
    val allowExactAlarms: Boolean = true,
    val featurePhoneCall: Boolean = false,
    val mcpServers: String = "[]",
) {
    fun asJson(): JSONObject = JSONObject().apply {
        put("openAiBaseUrl", openAiBaseUrl); put("openAiModelFast", openAiModelFast)
        put("openAiModelStrong", openAiModelStrong); put("anthropicBaseUrl", anthropicBaseUrl)
        put("anthropicModelStrong", anthropicModelStrong); put("llmChain", llmChain)
        put("remoteBrainUrl", remoteBrainUrl); put("remoteBrainToken", remoteBrainToken)
        put("personaName", personaName); put("sirAddress", sirAddress)
        put("initiativeLevel", initiativeLevel); put("quietHoursStart", quietHoursStart)
        put("quietHoursEnd", quietHoursEnd); put("proactiveDailyBudget", proactiveDailyBudget)
        put("wakeEnabled", wakeEnabled); put("wakePhrases", wakePhrases)
        put("wakeSensitivity", wakeSensitivity); put("ttsPrimary", ttsPrimary)
        put("ttsFishRefVoice", ttsFishRefVoice); put("ttsElevenVoiceId", ttsElevenVoiceId)
        put("ttsGenericBaseUrl", ttsGenericBaseUrl); put("ttsGenericHeadersJson", ttsGenericHeadersJson)
        put("ttsGenericBodyTemplate", ttsGenericBodyTemplate); put("ttsGenericAudioFormat", ttsGenericAudioFormat)
        put("ttsLengthLimitChars", ttsLengthLimitChars); put("sttPrimary", sttPrimary)
        put("sttGenericBaseUrl", sttGenericBaseUrl); put("sttGenericHeadersJson", sttGenericHeadersJson)
        put("sttDeepgramModel", sttDeepgramModel); put("evolutionEnabled", evolutionEnabled)
        put("allowExactAlarms", allowExactAlarms); put("featurePhoneCall", featurePhoneCall)
        put("mcpServers", mcpServers)
    }

    companion object {
        fun fromJson(o: JSONObject): JarvisSettings {
            fun s(k: String, d: String): String = o.optString(k, d)
            fun i(k: String, d: Int): Int = if (o.has(k)) o.optInt(k, d) else d
            fun b(k: String, d: Boolean): Boolean = if (o.has(k)) o.optBoolean(k, d) else d
            return JarvisSettings(
                openAiBaseUrl = s("openAiBaseUrl", JarvisSettings().openAiBaseUrl),
                openAiModelFast = s("openAiModelFast", ""), openAiModelStrong = s("openAiModelStrong", ""),
                anthropicBaseUrl = s("anthropicBaseUrl", JarvisSettings().anthropicBaseUrl),
                anthropicModelStrong = s("anthropicModelStrong", JarvisSettings().anthropicModelStrong),
                llmChain = s("llmChain", JarvisSettings().llmChain),
                remoteBrainUrl = s("remoteBrainUrl", ""), remoteBrainToken = s("remoteBrainToken", ""),
                personaName = s("personaName", "Jarvis"), sirAddress = s("sirAddress", "sir"),
                initiativeLevel = i("initiativeLevel", 2), quietHoursStart = i("quietHoursStart", 23),
                quietHoursEnd = i("quietHoursEnd", 7), proactiveDailyBudget = i("proactiveDailyBudget", 8),
                wakeEnabled = b("wakeEnabled", false),
                wakePhrases = s("wakePhrases", JarvisSettings().wakePhrases),
                wakeSensitivity = i("wakeSensitivity", 50), ttsPrimary = i("ttsPrimary", 0),
                ttsFishRefVoice = s("ttsFishRefVoice", ""), ttsElevenVoiceId = s("ttsElevenVoiceId", ""),
                ttsGenericBaseUrl = s("ttsGenericBaseUrl", ""), ttsGenericHeadersJson = s("ttsGenericHeadersJson", "{}"),
                ttsGenericBodyTemplate = s("ttsGenericBodyTemplate", JarvisSettings().ttsGenericBodyTemplate),
                ttsGenericAudioFormat = s("ttsGenericAudioFormat", "mp3"),
                ttsLengthLimitChars = i("ttsLengthLimitChars", 800), sttPrimary = i("sttPrimary", 0),
                sttGenericBaseUrl = s("sttGenericBaseUrl", ""), sttGenericHeadersJson = s("sttGenericHeadersJson", "{}"),
                sttDeepgramModel = s("sttDeepgramModel", "nova-2"),
                evolutionEnabled = b("evolutionEnabled", true),
                allowExactAlarms = b("allowExactAlarms", true),
                featurePhoneCall = b("featurePhoneCall", false),
                mcpServers = s("mcpServers", "[]"),
            )
        }
    }
}

object Providers {
    val TTS = listOf("Fish Audio", "ElevenLabs", "Android TTS (offline)", "Custom HTTP")
    val STT = listOf("Android SpeechRecognizer", "Deepgram", "Fish Audio", "Custom HTTP")
    val KEYS = listOf(
        "openAi", "anthropic", "fishAudio", "elevenlabs", "deepgram", "customLlm",
    )
}

class SettingsStore(private val context: Context) {
    private val jsonKey = stringPreferencesKey("settings_json")

    suspend fun current(): JarvisSettings {
        val prefs: Preferences = context.dataStore.data.first()
        val raw = prefs[stringPreferencesKey("settings_json")].orEmpty()
        return if (raw.isBlank()) JarvisSettings() else runCatching {
            JarvisSettings.fromJson(JSONObject(raw))
        }.getOrDefault(JarvisSettings())
    }

    fun currentBlocking(): JarvisSettings = kotlinx.coroutines.runBlocking { current() }

    suspend fun update(block: (JarvisSettings) -> JarvisSettings) {
        context.dataStore.edit { prefs ->
            val raw = prefs[stringPreferencesKey("settings_json")]
            val cur = if (raw.isNullOrBlank()) JarvisSettings() else runCatching {
                JarvisSettings.fromJson(JSONObject(raw))
            }.getOrDefault(JarvisSettings())
            prefs[stringPreferencesKey("settings_json")] = block(cur).asJson().toString()
        }
    }

    fun updateBlocking(block: (JarvisSettings) -> JarvisSettings) = kotlinx.coroutines.runBlocking { update(block) }

    val flow: Flow<JarvisSettings> = context.dataStore.data.map { prefs ->
        val raw = prefs[stringPreferencesKey("settings_json")].orEmpty()
        if (raw.isBlank()) JarvisSettings() else runCatching {
            JarvisSettings.fromJson(JSONObject(raw))
        }.getOrDefault(JarvisSettings())
    }
}

/** API keys - encrypted at rest, never in code, DataStore or git. */
class KeyVault(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jarvis_keys_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun set(provider: String, key: String) = prefs.edit().putString(provider, key.trim()).apply()
    fun get(provider: String): String = prefs.getString(provider, "") ?: ""
    fun registered(): List<String> = prefs.all.keys.filter { (prefs.getString(it, "") ?: "").isNotBlank() }
    fun wipe(provider: String) = prefs.edit().remove(provider).apply()
}

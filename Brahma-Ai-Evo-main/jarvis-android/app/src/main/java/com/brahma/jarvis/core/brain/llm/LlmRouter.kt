package com.brahma.jarvis.core.brain.llm

import com.brahma.jarvis.core.settings.JarvisSettings
import com.brahma.jarvis.core.settings.KeyVault
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fast-vs-strong model routing + provider fallback chain.
 * Remote-brain mode: non-empty settings.remoteBrainUrl routes every chat to the user's
 * own gateway (https, Authorization: Bearer token), which is the on-Python-core desktop path.
 */
class LlmRouter(private val settings: () -> JarvisSettings, private val keys: KeyVault) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface Turn
    data class TextTurn(val text: String) : Turn
    data class ToolTurn(val name: String, val args: String) : Turn

    /** Returns ordered (adapter, model) pairs honouring the fallback chain. */
    private fun chain(strong: Boolean): List<Pair<LlmAdapter, String>> {
        val s = settings()
        val out = mutableListOf<Pair<LlmAdapter, String>>()
        s.llmChain.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { pid ->
            val key = keys.get(pid)
            when (pid) {
                "openai" -> if (key.isNotBlank()) {
                    val fallback = "google/gemini-2.0-flash-001"
                    out += OpenAiCompatAdapter(s.openAiBaseUrl, key, "openai", http) to
                        (if (strong) s.openAiModelStrong.trim() else s.openAiModelFast.trim())
                            .ifBlank { fallback }
                }
                "anthropic" -> if (key.isNotBlank()) {
                    out += AnthropicAdapter(s.anthropicBaseUrl, key, "anthropic", http) to
                        s.anthropicModelStrong.trim().ifBlank { "claude-sonnet-4-20250514" }
                }
                "customLlm" -> if (key.isNotBlank()) {
                    out += OpenAiCompatAdapter("https://api.openai.com/v1", key, "customLlm", http) to
                        (if (strong) s.openAiModelStrong else s.openAiModelFast).ifBlank { "gpt-4o-mini" }
                }
            }
        }
        return out
    }

    /**
     * Streams a response; onText fires for text deltas. Returns text turns and tool calls.
     * Tries each adapter in the chain and only falls forward on exception.
     */
    fun stream(
        messages: List<Pair<String, String>>,
        tools: List<JSONObject>,
        strong: Boolean,
        onText: (String) -> Unit,
        temperature: Double = 0.7,
    ): List<Turn> {
        val s = settings()
        if (s.remoteBrainUrl.isNotBlank()) {
            val r = remoteBrainStream(s.remoteBrainUrl, s.remoteBrainToken, messages, tools, onText)
            if (r != null) return r
        }
        var lastErr: Exception? = null
        for ((adapter, model) in chain(strong)) {
            try {
                val calls = adapter.chat(messages, tools, model, temperature, onText)
                val turns = mutableListOf<Turn>()
                calls.forEach { turns += ToolTurn(it.name, it.argsJson) }
                return turns
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw IllegalStateException("all LLM providers failed" +
            (lastErr?.let { ": ${it.message}" } ?: ""))
    }

    /** Small-temperature single-shot helper (used by memory extractor / evaluators). */
    fun complete(
        system: String,
        user: String,
        strong: Boolean = false,
        expectJson: Boolean = false,
    ): String {
        val sb = StringBuilder()
        stream(
            listOf("system" to system, "user" to user),
            emptyList(),
            strong,
            onText = { sb.append(it) },
        )
        var t = sb.toString()
        if (expectJson) {
            val a = t.indexOf('{'); val last = t.lastIndexOf('}')
            if (a >= 0 && last > a) t = t.substring(a, last + 1)
        }
        return t
    }

    fun healthCheck(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        chain(strong = false).forEach { (a, model) ->
            try {
                a.chat(listOf("user" to "Reply with exactly: ok"), emptyList(), model, 0.0) {}
                out += a.id to "ok"
            } catch (e: Exception) {
                out += a.id to "fail: ${e.message?.take(120)}"
            }
        }
        if (out.isEmpty()) out += "none" to "no API key set (expected: needs key)"
        return out
    }

    /**
     * Remote-brain mode: POST the conversation to the user's own gateway
     * (e.g. the Python Brahma core) over https with Bearer token auth.
     * Returns null on failure so the on-device chain can still try providers.
     */
    private fun remoteBrainStream(
        url: String,
        token: String,
        messages: List<Pair<String, String>>,
        tools: List<JSONObject>,
        onText: (String) -> Unit,
    ): List<Turn>? {
        return runCatching {
            val body = JSONObject().apply {
                put("messages", JSONArray().apply {
                    messages.forEach { (role, content) ->
                        put(JSONObject().put("role", role).put("content", content))
                    }
                })
                put("tools", JSONArray(tools))
            }
            val req = Request.Builder()
                .url(url.trimEnd('/') + "/v1/chat")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(200)
                resp.close()
                throw IllegalStateException("remote brain HTTP ${resp.code}: $err")
            }
            val text = resp.body?.string().orEmpty()
            resp.close()
            val obj = runCatching { JSONObject(text) }.getOrNull()
            val out = mutableListOf<Turn>()
            obj?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { onText(it); out += TextTurn(it) }
            obj?.optJSONArray("tool_calls")?.let { tcs ->
                for (i in 0 until tcs.length()) {
                    val t = tcs.optJSONObject(i) ?: continue
                    out += ToolTurn(t.optString("name"), t.optJSONObject("arguments")?.toString() ?: "{}")
                }
            }
            out
        }.getOrNull()
    }
}

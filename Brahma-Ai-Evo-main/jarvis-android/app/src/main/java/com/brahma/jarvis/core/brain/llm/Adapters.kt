package com.brahma.jarvis.core.brain.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** Tool call parsed from a provider stream. */
data class ToolCall(val id: String, val name: String, val argsJson: String)

interface LlmAdapter {
    val id: String
    /** Streams assistant text deltas; returns tool calls if the model decided to use any. */
    fun chat(
        messages: List<Pair<String, String>>,   // role -> content ("system"/"user"/"assistant"/"tool")
        tools: List<JSONObject>,                // OpenAI-style: {"type":"function","function":{name,description,parameters}}
        model: String,
        temperature: Double,
        onText: (String) -> Unit,
    ): List<ToolCall>
}

class OpenAiCompatAdapter(
    private val baseUrl: String,
    private val apiKey: String,
    override val id: String = "openai",
    private val http: OkHttpClient,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmAdapter {

    override fun chat(
        messages: List<Pair<String, String>>,
        tools: List<JSONObject>,
        model: String,
        temperature: Double,
        onText: (String) -> Unit,
    ): List<ToolCall> {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray(tools))
                put("tool_choice", "auto")
            }
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/sahilsahilimtiyaz-code/Ju")
            .header("X-Title", "Brahma JARVIS")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string().orEmpty().take(300)
            resp.close()
            throw IllegalStateException("LLM HTTP ${resp.code}: $err")
        }
        val outText = StringBuilder()
        val callArgs = HashMap<Int, StringBuilder>()
        val callNames = HashMap<Int, String>()
        val resp_body = resp.body ?: return emptyList()
        resp_body.byteStream().bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta") ?: continue
                delta.optString("content", "").takeIf { it.isNotEmpty() }?.let {
                    outText.append(it); onText(it)
                }
                delta.optJSONArray("tool_calls")?.let { tcs ->
                    for (i in 0 until tcs.length()) {
                        val tc = tcs.optJSONObject(i) ?: continue
                        val idx = tc.optInt("index", i)
                        tc.optJSONObject("function")?.let { fn ->
                            fn.optString("name", "").takeIf { it.isNotEmpty() }
                                ?.let { callNames[idx] = it }
                            fn.optString("arguments", "").takeIf { it.isNotEmpty() }
                                ?.let { callArgs.getOrPut(idx) { StringBuilder() }.append(it) }
                        }
                    }
                }
            }
        }
        resp.close()
        return callNames.entries.sortedBy { it.key }.map { (i, name) ->
            ToolCall("call_$i", name, callArgs[i]?.toString() ?: "{}")
        }
    }
}

class AnthropicAdapter(
    private val baseUrl: String,
    private val apiKey: String,
    override val id: String = "anthropic",
    private val http: OkHttpClient,
) : LlmAdapter {

    override fun chat(
        messages: List<Pair<String, String>>,
        tools: List<JSONObject>,
        model: String,
        temperature: Double,
        onText: (String) -> Unit,
    ): List<ToolCall> {
        val sys = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("stream", true)
            put("temperature", temperature)
            if (sys.isNotBlank()) put("system", sys)
            put("messages", JSONArray().apply {
                messages.filter { it.first != "system" }.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { t ->
                        val fn = t.optJSONObject("function") ?: t
                        put(JSONObject()
                            .put("name", fn.optString("name"))
                            .put("description", fn.optString("description"))
                            .put("input_schema", fn.optJSONObject("parameters") ?: JSONObject()))
                    }
                })
            }
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string().orEmpty().take(300)
            resp.close()
            throw IllegalStateException("anthropic HTTP ${resp.code}: $err")
        }
        val calls = mutableListOf<ToolCall>()
        resp.body?.byteStream()?.bufferedReader()?.use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val obj = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                when (obj.optString("type")) {
                    "content_block_delta" -> obj.optJSONObject("delta")?.let { d ->
                        d.optString("text").takeIf { it.isNotEmpty() }?.let(onText)
                    }
                    "content_block_start" -> obj.optJSONObject("content_block")?.let { cb ->
                        if (cb.optString("type") == "tool_use") {
                            calls += ToolCall(cb.optString("id"), cb.optString("name"),
                                cb.optJSONObject("input")?.toString() ?: "{}")
                        }
                    }
                    "message_stop" -> break
                }
            }
        }
        resp.close()
        return calls
    }
}

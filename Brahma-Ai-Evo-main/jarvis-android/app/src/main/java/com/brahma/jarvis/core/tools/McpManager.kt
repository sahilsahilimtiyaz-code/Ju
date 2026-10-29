package com.brahma.jarvis.core.tools

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MCP client over Streamable HTTP (MCP spec: JSON-RPC 2.0 via POST, optional SSE,
 * Mcp-Session-Id continuation). Servers are user-configured name+URL pairs so any
 * desktop MCP gateway (filesystem, home automation, the Brahma Python core...) works.
 */
class McpManager(private val ctx: Context) {
    companion object {
        const val PREFIX = "mcp_"
        private const val KEY_SERVERS = "mcp_servers"
        private const val PROTOCOL = "2024-11-05"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val toolsCache = mutableListOf<Triple<String, String, JSONObject>>() // qualifiedName, desc, toolJson

    private fun servers(): JSONArray {
        val s = ctx.getSharedPreferences("jarvis_mcp", Context.MODE_PRIVATE)
        return JSONArray(s.getString(KEY_SERVERS, "[]") ?: "[]")
    }

    fun addServer(name: String, url: String): Boolean {
        val arr = servers()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("name") == name) return false
        arr.put(JSONObject().put("name", name).put("url", url.trim()))
        ctx.getSharedPreferences("jarvis_mcp", Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVERS, arr.toString()).apply()
        return refreshTools().isNotEmpty() || true
    }

    fun remove(name: String): Boolean {
        val arr = servers()
        val out = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("name") != name) out.put(arr.getJSONObject(i)) else removed = true
        }
        ctx.getSharedPreferences("jarvis_mcp", Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVERS, out.toString()).apply()
        refreshTools()
        return removed
    }

    fun listServers(): JSONArray = servers()

    /** JSON-RPC POST returning the result object (handles both direct JSON and SSE-wrapped data lines). */
    private fun rpc(url: String, method: String, params: JSONObject, sessionId: String?): Pair<JSONObject?, String> {
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val req = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null to "HTTP ${resp.code}"
            val sid = resp.header("Mcp-Session-Id") ?: sessionId ?: ""
            val text = resp.body?.string().orEmpty()
            // SSE responses carry "data:" lines
            val jsonStr = if (text.trimStart().startsWith("{")) text else
                text.lines().filter { it.startsWith("data:") }.firstOrNull()
                    ?.removePrefix("data:").orEmpty()
            val obj = runCatching { JSONObject(jsonStr) }.getOrNull()
                ?: runCatching { JSONObject(text) }.getOrNull()
            return (obj?.optJSONObject("result")) to if (obj == null) "bad json" else sid
        }
    }

    private fun reachable(name: String, url: String): String? {
        val (init, err) = rpc(url, "initialize", JSONObject()
            .put("protocolVersion", PROTOCOL)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "jarvis-android").put("version", "3.0")),
            null)
        if (init == null) return "initialize failed: $err"
        // notifications/initialized is fire-and-forget
        runCatching {
            val body = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
            http.newCall(Request.Builder().url(url)
                .header("Mcp-Session-Id", init.optString("_sessionId", "")).let { r ->
                    r.post(body.toString().toRequestBody("application/json".toMediaType())).build()
                }.let { it }).execute().use { }
        }
        return null
    }

    fun refreshTools(): List<String> {
        val arr = servers()
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val url = o.optString("url")
            val name = o.optString("name")
            if (url.isBlank()) continue
            val pair = runCatching {
                val (init, err) = rpc(url, "initialize", JSONObject()
                    .put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "jarvis-android").put("version", "3.0")),
                    null)
                if (init == null) throw IllegalStateException(err)
                val sid = init.optString("_sessionId", "")
                val (res, _) = rpc(url, "tools/list", JSONObject(), if (sid.isBlank()) null else sid)
                res
            }.getOrNull()
            if (pair != null) {
                runCatching {
                    val toolArr = pair.optJSONArray("tools")
                    for (ti in 0 until (toolArr?.length() ?: 0)) {
                        val t = toolArr!!.getJSONObject(ti)
                        val tn = t.optString("name")
                        if (tn.isNotBlank()) {
                            val qualified = PREFIX + name + "_" + tn
                            toolsCache.removeAll { it.first == qualified }
                            toolsCache.add(Triple(qualified, t.optString("description"), t))
                            names += qualified
                        }
                    }
                }
            }
        }
        return names
    }

    fun llmSchemas(): List<JSONObject> {
        if (toolsCache.isEmpty()) refreshTools()
        return toolsCache.map { (qualified, desc, t) ->
            JSONObject().apply {
                put("type", "function")
                put("function", JSONObject()
                    .put("name", qualified)
                    .put("description", "[MCP] $desc")
                    .put("parameters", t.optJSONObject("inputSchema") ?: JSONObject()))
            }
        }
    }

    fun toolNames(): List<String> = toolsCache.map { it.first }

    fun call(qualifiedName: String, args: JSONObject): ToolResult {
        val serverName = qualifiedName.removePrefix(PREFIX).substringBefore("_")
        val server = servers().let { a ->
            (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }
                .firstOrNull { it.optString("name") == serverName }
        } ?: return ToolResult("MCP server $serverName not found", "error")
        val url = server.optString("url")
        val rawName = qualifiedName.removePrefix(PREFIX).removePrefix("${serverName}_")
        return runCatching {
            val (_, initErr) = rpc(url, "initialize", JSONObject()
                .put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "jarvis-android").put("version", "3.0")), null)
            if (initErr != null && initErr.startsWith("HTTP")) return ToolResult("MCP init $initErr", "error")
            val (res, _) = rpc(url, "tools/call", JSONObject()
                .put("name", rawName).put("arguments", args), null)
            if (res == null) ToolResult("MCP call failed", "error")
            else {
                val contentArr = res.optJSONArray("content")
                val sb = StringBuilder()
                for (ci in 0 until (contentArr?.length() ?: 0)) {
                    runCatching { contentArr!!.getJSONObject(ci).optString("text") }
                        .getOrDefault("").let { if (it.isNotBlank()) sb.append(it).append(' ') }
                }
                ToolResult(sb.toString().trim().ifBlank { res.toString().take(400) }, "ok")
            }
        }.getOrElse { ToolResult("MCP error: ${it.message}", "error") }
    }

    fun selfTest(): List<Pair<String, String>> =
        servers().let { a ->
            (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }
        }.map { s ->
            val reach = runCatching { reachable(s.optString("name"), s.optString("url")) }.getOrNull()
            "mcp:${s.optString("name")}" to (reach ?: "reachable")
        }
}

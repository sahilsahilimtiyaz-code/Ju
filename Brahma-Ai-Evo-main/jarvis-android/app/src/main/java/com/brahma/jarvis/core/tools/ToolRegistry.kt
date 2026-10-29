package com.brahma.jarvis.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val resultText: String, val status: String) {
    fun ok() = this
}

data class ToolDef(
    val name: String,
    val description: String,
    val schema: JSONObject,
    val needsApproval: Boolean, // irreversible / money / messages
)

/**
 * Tool registry: declared here (schemas for the LLM), executed through
 * [DeviceTools] (on-phone actions) and [McpClient] (external MCP servers).
 * Every call is permission-checked and audit-logged; irreversible calls get
 * confirm=required semantics from the caller.
 */
class ToolRegistry(private val ctx: Context) {
    private val device = DeviceTools(ctx)
    private val mcp = McpManager(ctx)

    val toolsMap = mutableListOf<Pair<ToolDef, (JSONObject) -> ToolResult>>()
    private val audit = mutableListOf<String>()

    init {
        device.registerAll(this)
    }

    fun add(def: ToolDef, fn: (JSONObject) -> ToolResult) {
        toolsMap += def to fn
    }

    fun registryHooks() = device

    /** OpenAI-style tool schemas for the LLM. */
    fun llmSchemas(): List<JSONObject> = toolsMap.map { (def, _) ->
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject()
                .put("name", def.name)
                .put("description", def.description)
                .put("parameters", def.schema))
        }
    } + mcp.llmSchemas()

    fun names(): List<String> = toolsMap.map { it.first.name } + mcp.toolNames()

    fun execute(name: String, argsJson: String): Triple<String, String, String> {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        audit += "$name ${args.toString().take(160)}"
        if (name.startsWith(McpManager.PREFIX)) {
            val r = mcp.call(name, args)
            return Triple(r.resultText, r.status, name)
        }
        val entry = toolsMap.firstOrNull { it.first.name == name }
            ?: return Triple("unknown tool: $name", "error", name)
        val def = entry.first
        if (def.needsApproval && !args.optBoolean("confirmed", false)) {
            return Triple(
                "Ask the user to confirm explicitly before running again. Pass confirmed:true only after a clear yes.",
                "needs-approval", name)
        }
        return runCatching {
            val r = entry.second(args)
            Triple(r.resultText, r.status, name)
        }.getOrElse { Triple("tool fault: ${it.message}", "error", name) }
    }

    fun recentAudit(): List<String> = audit.takeLast(50).reversed()

    fun connectMcp(name: String, url: String): Boolean = mcp.addServer(name, url)
    fun mcpServers(): JSONArray = mcp.listServers()
    fun refreshMcpTools(): List<String> = mcp.refreshTools()
    fun removeMcp(name: String): Boolean = mcp.remove(name)
    fun systemReportForPrompt(): String = device.systemReportForPrompt()
    fun deviceForUi(): JSONObject = device.snapshotForUi()
    fun diagnosticsSelfTest(): List<Pair<String, String>> = device.selfTest() + mcp.selfTest()
}

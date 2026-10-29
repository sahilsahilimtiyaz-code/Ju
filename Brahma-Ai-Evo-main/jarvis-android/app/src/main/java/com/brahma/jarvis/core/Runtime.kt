package com.brahma.jarvis.core

import android.content.Context
import com.brahma.jarvis.core.brain.BrainBridge
import com.brahma.jarvis.core.memory.MemoryStore
import com.brahma.jarvis.core.settings.JarvisSettings
import com.brahma.jarvis.core.settings.KeyVault
import com.brahma.jarvis.core.settings.SettingsStore
import com.brahma.jarvis.core.tools.ToolRegistry
import com.brahma.jarvis.core.tools.ToolRegistryHooks
import com.brahma.jarvis.core.voice.TtsRouter
import com.brahma.jarvis.core.session.AssistantSession
import com.brahma.jarvis.core.brain.BrainEvent

/**
 * Process-wide runtime: builds the brain once, links memory hooks into the tool
 * registry, and hands fresh settings on every read (no stale snapshots).
 */
class Runtime private constructor(ctx: Context) {
    val appCtx: Context = ctx.applicationContext
    val settings = SettingsStore(appCtx)
    val keys = KeyVault(appCtx)
    val memory = MemoryStore(appCtx)
    val tools = ToolRegistry(appCtx)
    val tts = TtsRouter(appCtx, { settings.currentBlocking() }, keys)
    val brain = BrainBridge(
        memory,
        { settings.currentBlocking() },
        keys,
        tools,
        tts,
    ) { e -> AssistantSession.addEvent(e) }

    init {
        ToolRegistryHooks.memoryHook = { fact, cat ->
            memory.addFact(fact, cat, 0.95, "tool_memory_save")
            com.brahma.jarvis.core.tools.ToolResult("Noted, permanently.", "ok")
        }
        ToolRegistryHooks.memorySearchHook = { q ->
            memory.recall(q, 8).joinToString("\n").take(900)
        }
        com.brahma.jarvis.core.session.AssistantSession.toolCount.value = tools.names().size
    }

    fun current(): JarvisSettings = settings.currentBlocking()

    companion object {
        @Volatile private var instance: Runtime? = null

        fun get(ctx: Context): Runtime =
            instance ?: synchronized(this) {
                instance ?: Runtime(ctx.applicationContext).also { instance = it }
            }

        fun peek(ctx: Context): Runtime? = instance
    }
}

package com.brahma.jarvis.core.session

import com.brahma.jarvis.core.brain.BrainEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Chat turn shown in the UI. */
data class Turn(val role: String, val text: String, val ts: Long = System.currentTimeMillis())

/**
 * Process-wide live state shared between MainActivity (Compose) and the foreground
 * mic service. UI subscribes to flows; the service pushes updates here.
 */
object AssistantSession {
    val events = MutableSharedFlow<BrainEvent>(extraBufferCapacity = 64)
    val turns = MutableStateFlow<List<Turn>>(emptyList())
    val livePartial = MutableStateFlow("")
    val listening = MutableStateFlow(false)
    val speaking = MutableStateFlow(false)
    val wakeActive = MutableStateFlow(false)     // always-on mic running
    val micLive = MutableStateFlow(false)        // foreground service actually recording
    val statusLine = MutableStateFlow("OFFLINE")
    val toolFeed = MutableStateFlow<List<Turn>>(emptyList())
    val toolCount = MutableStateFlow(0)
    val route = MutableStateFlow("home")         // simple nav composite for quick jumps

    fun pushTurn(t: Turn) {
        turns.value = (turns.value + t).takeLast(120)
    }

    fun pushToolLine(name: String, status: String, detail: String) {
        toolFeed.value = (toolFeed.value + Turn("tool", "$name · $status · $detail")).takeLast(12)
    }

    fun addEvent(e: BrainEvent) {
        events.tryEmit(e)
        when (e) {
            is BrainEvent.PartialText -> livePartial.value += e.text
            is BrainEvent.ToolUsed -> pushToolLine(e.name, e.status, e.detail)
            is BrainEvent.TurnDone -> {
                pushTurn(Turn("jarvis", e.fullText))
                livePartial.value = ""
            }
            is BrainEvent.Error -> { pushTurn(Turn("error", e.message)); livePartial.value = "" }
            is BrainEvent.SentenceSpoken -> {}
        }
    }

    fun clearTranscript() {
        turns.value = emptyList()
        livePartial.value = ""
    }
}

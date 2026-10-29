package com.brahma.jarvis.core.brain

import com.brahma.jarvis.core.brain.llm.LlmRouter
import com.brahma.jarvis.core.brain.llm.ToolCall
import com.brahma.jarvis.core.memory.MemoryStore
import com.brahma.jarvis.core.settings.JarvisSettings
import com.brahma.jarvis.core.tools.ToolRegistry
import com.brahma.jarvis.core.voice.Chunker
import com.brahma.jarvis.core.voice.TtsRouter
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Events pushed to the UI while the brain answers. */
sealed interface BrainEvent {
    data class PartialText(val text: String) : BrainEvent
    data class SentenceSpoken(val text: String, val provider: String) : BrainEvent
    data class ToolUsed(val name: String, val status: String, val detail: String) : BrainEvent
    data class TurnDone(val fullText: String) : BrainEvent
    data class Error(val message: String) : BrainEvent
}

/**
 * Brain = LLM router + memory + persona + tools + TTS. UI-agnostic: the Compose UI and
 * the mic service both call ask() and observe events.
 */
class BrainBridge(
    memory: MemoryStore,
    private val settings: () -> JarvisSettings,
    keys: com.brahma.jarvis.core.settings.KeyVault,
    private val tools: ToolRegistry,
    private val tts: TtsRouter,
    eventSink: (BrainEvent) -> Unit,
) {
    private val mem = memory
    private val router = LlmRouter(settings, keys)
    private val onEvent = eventSink
    @Volatile var busy: Boolean = false
        private set
    private val bail = AtomicBoolean(false)

    private fun nowIso(): String {
        val f = SimpleDateFormat("EEE, d MMM yyyy, HH:mm", Locale.US)
        return f.format(Date())
    }

    private fun deviceLine(): String = tools.systemReportForPrompt()

    /** Full turn: ask -> stream -> speak-as-it-comes -> tool loop -> final text. */
    fun ask(userText: String, strong: Boolean = false, onAnswerDone: (String) -> Unit = {}) {
        if (busy) { onEvent(BrainEvent.Error("One moment, I am already on it, sir.")); return }
        busy = true
        bail.set(false)
        Thread {
            var answer = ""
            try {
                mem.logMessage("user", userText, "app")
                val s = settings()
                val ctx = mem.buildContext(userText)
                val evolved = mem.summaries().firstOrNull { it.first == "evo_rules" }?.second ?: ""
                val system = Persona.systemPrompt(
                    s.personaName, s.sirAddress, ctx.profile, ctx.topFacts, ctx.recalled,
                    ctx.recent.take(8).map { "${it.role}: ${it.text}" },
                    evolved, nowIso(), deviceLine())

                val convo = mutableListOf("system" to system, "user" to userText)
                val chunker = Chunker.LiveStream()

                for (hop in 0 until 6) {
                    val textBuf = StringBuilder()
                    val turns = router.stream(convo.toList(), tools.llmSchemas(), strong,
                        onText = { delta ->
                            textBuf.append(delta)
                        onEvent(BrainEvent.PartialText(delta))
                        val speakables = chunker.push(delta)
                        speakables.forEach { sen ->
                            val clean = Persona.spoken(sen)
                            if (clean.isNotBlank()) {
                                tts.speak(clean) { _, degraded ->
                                    if (degraded) onEvent(BrainEvent.SentenceSpoken(clean, "fallback"))
                                }
                                answer += " " + clean
                            }
                        }
                        }
                    )
                    // flush any tail as speech
                    chunker.flush().takeIf { it.isNotBlank() }?.let {
                        val clean = Persona.spoken(it)
                        if (clean.isNotBlank()) { tts.speak(clean, onDone = { _, _ -> }); answer += " " + clean }
                    }

                    val decidedTools = turns.filterIsInstance<com.brahma.jarvis.core.brain.llm.LlmRouter.ToolTurn>()
                    if (decidedTools.isEmpty()) {
                        answer = textBuf.toString()
                        break
                    }

                    // run each tool, feed results back
                    convo.add("assistant" to textBuf.toString())
                    for (t in decidedTools) {
                        if (bail.get()) break
                        val result = registerSafeToolRun(t)
                        onEvent(BrainEvent.ToolUsed(t.name, result.second, result.third))
                        convo.add("user" to "[tool ${t.name} -> ${result.second}] ${result.first.take(1200)}")
                    }
                    if (bail.get()) break
                }

                if (bail.get()) onEvent(BrainEvent.Error("Stopped, sir."))
                if (answer.isBlank() && !bail.get()) {
                    onEvent(BrainEvent.Error("No answer came back from any provider."))
                }
                mem.logMessage("assistant", answer.ifBlank { "(error)" }, "app")
                if (answer.length > 20) mem.captureGolden(userText, answer)
                onAnswerDone(answer)
            } catch (e: Exception) {
                onEvent(BrainEvent.Error("I hit a fault: ${e.message?.take(180)}"))
            } finally {
                busy = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun registerSafeToolRun(t: com.brahma.jarvis.core.brain.llm.LlmRouter.ToolTurn): Triple<String, String, String> {
        return try {
            val out = tools.execute(t.name, t.args)
            Triple(out.first, out.second, t.name)
        } catch (e: Exception) {
            Triple("tool error: ${e.message}", "error", t.name)
        }
    }

    /** Interrupt current speech (barge-in). */
    fun stopSpeaking() = tts.stopSpeaking()

    fun health() = router.healthCheck()

    fun cancel() { bail.set(true); tts.stopSpeaking() }

    /** Single-shot completion for internal engines (proactive, evolution, extraction). */
    fun completeLlm(system: String, user: String, strong: Boolean): String =
        router.complete(system, user, strong, expectJson = false)

    /** Summarizer + fact-extraction batch, used by session end / nightly workers. */
    fun reflect(onDone: (Boolean) -> Unit) {
        Thread {
            val msgs = mem.since(System.currentTimeMillis() - 12 * 3600_000)
            if (msgs.size < 4) { onDone(false); return@Thread }
            val convo = msgs.joinToString("\n") { "${it.role}: ${it.text.take(240)}" }
            try {
                val s = settings()
                val facts = router.complete(
                    "You write MEMORY as atomic, deduplicated facts. Reply ONLY as JSON array of " +
                        "{\"fact\":..,\"category\":..,\"confidence\":0..1}. Categories: " +
                        "identity, goal, preference, person, routine, project, event.",
                    "Conversation:\n$convo\nExisting active facts:\n" +
                        mem.facts(60).joinToString("\n") { it.fact } +
                        "\nReturn only NEW or changed facts.", strong = true, expectJson = true)
                runCatching {
                    org.json.JSONArray(facts).let { arr ->
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            mem.addFact(o.optString("fact"), o.optString("category", "unknown"),
                                o.optDouble("confidence", 0.8), "extractor")
                        }
                    }
                }
                val profile = router.complete(
                    "Maintain a compact ~500 token CORE PROFILE of the user as plain lines. " +
                        "Include identity, goals, routines, preferences, people, projects, current work.",
                    "Current profile:\n${mem.profile()}\n\nRecent conversation:\n$convo\n" +
                        "\nFacts:\n" + mem.facts(80).joinToString("\n") { it.fact },
                    strong = true)
                mem.saveProfile(profile.take(1400))
                val summary = router.complete(
                    "Write a short session summary (max 6 lines) in JARVIS's log style.",
                    convo.take(4000), strong = false)
                mem.addSummary("session", summary.take(500))
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private val http = OkHttpClient()
    }
}

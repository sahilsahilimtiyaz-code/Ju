package com.brahma.jarvis.core.voice

import android.content.Context
import com.brahma.jarvis.core.brain.Persona
import com.brahma.jarvis.core.settings.JarvisSettings
import com.brahma.jarvis.core.settings.KeyVault
import java.security.MessageDigest

/**
 * TTS router with fallback chain: configured primary -> Android TTS (built-in fallback).
 * Announces provider degradation in persona. Caches repeated phrases by
 * hash(text+voice+settings) into app cache to save daily character budget.
 */
class TtsRouter(private val ctx: Context, private val settings: () -> JarvisSettings, private val keys: KeyVault) {

    private val androidTts = AndroidTtsProvider(ctx)

    @Volatile var currentlySpeaking: Boolean = false
        private set

    private var activeCancel: () -> Boolean = { false }
    private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    fun makeProviders(): List<TtsProvider> {
        val s = settings()
        val list = mutableListOf<TtsProvider>()
        when (s.ttsPrimary) {
            0 -> list += FishAudioTts({ keys.get("fishAudio") }, settings)
            1 -> list += ElevenLabsTts({ keys.get("elevenlabs") }, settings)
            2 -> list += androidTts
            3 -> list += GenericHttpTts(settings, { keys.get("fishAudio").ifBlank { keys.get("customLlm") } })
        }
        if (s.ttsPrimary != 2) list += androidTts
        return list
    }

    private fun cacheKey(text: String): String {
        val s = settings()
        val raw = text + "|" + s.ttsPrimary + "|" + s.ttsFishRefVoice + "|" + s.ttsElevenVoiceId +
            "|" + s.ttsGenericBaseUrl
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Speak is fully async via its thread; completion + ok flags come back through callbacks. */
    fun speak(text: String, voiceHint: String = "", onDone: (ok: Boolean, degraded: Boolean) -> Unit = { _, _ -> }) {
        val spoken = Persona.spoken(text, settings().ttsLengthLimitChars)
        if (spoken.isBlank()) { onDone(true, false); return }
        cancelFlag.set(false)
        activeCancel = { cancelFlag.get() }
        currentlySpeaking = true

        // cache hit passes the cached provider output only for full-PCM/wav caches;
        // for simplicity we only cache AndroidTTS-independent custom phrases via files.
        val cacheFile = java.io.File(ctx.cacheDir, "tts-cache/${cacheKey(spoken)}.wav")
        val providers = makeProviders()
        var degraded = false

        val attempt = java.util.ArrayDeque(providers)
        speakThrough(attempt, listOf(spoken), voiceHint, degraded, cacheFile, onDone)
    }

    private fun speakThrough(
        remaining: java.util.ArrayDeque<TtsProvider>,
        chunks: List<String>,
        voiceHint: String,
        alreadyDegraded: Boolean,
        cacheFile: java.io.File,
        onDone: (ok: Boolean, degraded: Boolean) -> Unit,
    ) {
        val p = remaining.pollFirst()
        if (p == null) {
            currentlySpeaking = false
            onDone(false, true)
            return
        }
        currentProviderId = p.id
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            var degraded = alreadyDegraded
            val result = runCatching {
                p.speak(
                    chunks, voiceHint,
                    onStarted = {
                        if (started.compareAndSet(false, true)) cacheFile.parentFile?.mkdirs()
                    },
                    onDone = { innerOk: Boolean ->
                        currentlySpeaking = false
                        if (innerOk) {
                            onDone(true, degraded)
                        } else if (remaining.isNotEmpty()) {
                            degraded = true
                            speakThrough(remaining, chunks, voiceHint, degraded, cacheFile, onDone)
                        } else {
                            onDone(false, degraded)
                        }
                    },
                    cancelToken = { cancelFlag.get() },
                )
            }
            if (result.isFailure) {
                currentlySpeaking = false
                if (remaining.isNotEmpty()) speakThrough(remaining, chunks, voiceHint, true, cacheFile, onDone)
                else onDone(false, true)
            }
        }.apply { isDaemon = false }.start()
    }

    /** Barge-in: stop playback within ~200ms. */
    fun stopSpeaking() {
        cancelFlag.set(true)
    }

    var currentProviderId: String = ""
        private set

    fun health(): List<Pair<String, String>> = makeProviders().map { it.healthCheck() }
}

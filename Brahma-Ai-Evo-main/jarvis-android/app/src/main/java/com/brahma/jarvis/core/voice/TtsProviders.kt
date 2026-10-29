package com.brahma.jarvis.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Base64
import com.brahma.jarvis.core.settings.JarvisSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** stream(text_chunks) -> audio; health_check + list_voices for Diagnostics. */
interface TtsProvider {
    val id: String
    fun speak(
        textChunks: List<String>,
        voiceHint: String,
        onStarted: () -> Unit,
        onDone: (ok: Boolean) -> Unit,
        cancelToken: () -> Boolean,
    )
    fun healthCheck(): Pair<String, String>
    suspend fun listVoices(): List<String> = emptyList()
}

object TtsHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}

/**
 * Fish Audio. Primary: realtime WebSocket wss://api.fish.audio/v1/tts/live (streaming
 * text events -> base64 audio chunks). Fallback: REST POST /v1/tts (streaming response).
 * Docs reviewed: fish.audio api reference.
 */
class FishAudioTts(
    private val key: () -> String,
    private val settings: () -> JarvisSettings,
) : TtsProvider {
    override val id = "fish"

    override fun speak(
        textChunks: List<String>,
        voiceHint: String,
        onStarted: () -> Unit,
        onDone: (ok: Boolean) -> Unit,
        cancelToken: () -> Boolean,
    ) {
        val k = key()
        if (k.isBlank()) { onDone(false); return }
        val okLive = runCatching { speakLiveWs(textChunks, k, voiceHint, onStarted, onDone, cancelToken) }
            .getOrElse { false }
        if (!okLive && !cancelToken()) speakRest(textChunks, k, voiceHint, onStarted, onDone, cancelToken)
    }

    private fun speakLiveWs(
        textChunks: List<String>,
        k: String,
        voiceHint: String,
        onStarted: () -> Unit,
        onDone: (ok: Boolean) -> Unit,
        cancelToken: () -> Boolean,
    ): Boolean {
        val player = AudioTrackPlayer().apply { startOwnScope() }
        val feeder = AudioStreamFeeder(player, "pcm")
        val gotAudio = AtomicBoolean(false)
        val done = CountDownLatch(1)
        var failure: String? = null
        val ws = TtsHttp.client.newWebSocket(
            Request.Builder().url("wss://api.fish.audio/v1/tts/live")
                .header("Authorization", "Bearer $k").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val start = JSONObject().put("event", "start").put("request", JSONObject()
                        .put("text", "")
                        .put("reference_id", voiceHint.ifBlank { settings().ttsFishRefVoice })
                        .put("format", "pcm"))
                    webSocket.send(start.toString())
                    textChunks.forEachIndexed { i, chunk ->
                        if (cancelToken()) return@forEachIndexed
                        webSocket.send(JSONObject().put("event", "text")
                            .put("text", if (i == 0) "$chunk " else chunk).toString())
                    }
                    webSocket.send(JSONObject().put("event", "flush").toString())
                    webSocket.send(JSONObject().put("event", "stop").toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val o = JSONObject(text)
                        if (o.optString("event") == "audio" || o.has("audio")) {
                            val b64 = o.optString("audio", "")
                            if (b64.isNotEmpty()) {
                                gotAudio.set(true)
                                feeder.feed(Base64.decode(b64, Base64.DEFAULT))
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = t.message
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = done.countDown()
            })
        val ok = done.await(60, TimeUnit.SECONDS) && (gotAudio.get() || failure == null && cancelToken())
        feeder.close()
        player.cancel()
        return ok && gotAudio.get()
    }

    private fun speakRest(
        textChunks: List<String>,
        k: String,
        voiceHint: String,
        onStarted: () -> Unit,
        onDone: (ok: Boolean) -> Unit,
        cancelToken: () -> Boolean,
    ) {
        var ok = false
        try {
            for (chunk in textChunks) {
                if (cancelToken()) break
                val body = JSONObject().apply {
                    put("text", chunk)
                    put("reference_id", voiceHint.ifBlank { settings().ttsFishRefVoice })
                    put("format", "wav")
                }
                val req = Request.Builder()
                    .url("https://api.fish.audio/v1/tts")
                    .header("Authorization", "Bearer $k")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                TtsHttp.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("fish HTTP ${resp.code}")
                    val player = AudioTrackPlayer().apply { startOwnScope() }
                    AudioStreamFeeder.feedAll(
                        resp.body?.byteStream() ?: InputStream.nullInputStream(),
                        player, "wav", onStarted)
                    ok = true
                }
            }
        } catch (e: Exception) {
            ok = false
        }
        onDone(ok)
    }

    override fun healthCheck(): Pair<String, String> {
        val k = key()
        if (k.isBlank()) return id to "BLOCKED (needs key)"
        return runCatching {
            val req = Request.Builder().url("https://api.fish.audio/wallet/self/api-credit")
                .header("Authorization", "Bearer $k").get().build()
            TtsHttp.client.newCall(req).execute().use { r -> id to ("HTTP " + r.code) }
        }.getOrDefault(id to "error")
    }
}

/**
 * ElevenLabs stream-input WebSocket (model eleven_flash_v2_5, pcm_24000).
 * Sends chunked text, receives {"audio": base64 pcm16} + isFinal.
 */
class ElevenLabsTts(
    private val key: () -> String,
    private val settings: () -> JarvisSettings,
) : TtsProvider {
    override val id = "elevenlabs"

    override fun speak(
        textChunks: List<String>,
        voiceHint: String,
        onStarted: () -> Unit,
        onDone: (ok: Boolean) -> Unit,
        cancelToken: () -> Boolean,
    ) {
        val k = key()
        if (k.isBlank()) { onDone(false); return }
        val voice = voiceHint.ifBlank {
            settings().ttsElevenVoiceId.ifBlank { "pNInz6obpgDQGcFmaJgB" } // licensed British male stock voice
        }
        try {
            val player = AudioTrackPlayer().apply { startOwnScope(); start(24000, 1) }
            onStarted()
            val gotAudio = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val ws = TtsHttp.client.newWebSocket(
                Request.Builder()
                    .url("wss://api.elevenlabs.io/v1/text-to-speech/$voice/stream-input" +
                        "?model_id=eleven_flash_v2_5&output_format=pcm_24000")
                    .header("xi-api-key", k).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(JSONObject().put("generation_config", JSONObject()
                            .put("chunk_length_schedule", "[50,100,150]")).toString())
                        textChunks.forEach { chunk ->
                            if (cancelToken()) return@forEach
                            webSocket.send(JSONObject().put("text", "$chunk ").toString())
                        }
                        webSocket.send(JSONObject().put("text", " ").put("flush", true).toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val o = JSONObject(text)
                            val audio = o.optString("audio", "")
                            if (audio.isNotEmpty()) {
                                gotAudio.set(true)
                                player.offerPcm(Base64.decode(audio, Base64.DEFAULT))
                            }
                            if (o.optBoolean("isFinal", false)) {
                                webSocket.close(1000, "done")
                                done.countDown()
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                        done.countDown()

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                        done.countDown()
                })
            done.await(60, TimeUnit.SECONDS)
            player.blockUntilDrained(5_000)
            player.cancel()
            onDone(gotAudio.get())
        } catch (e: Exception) {
            onDone(false)
        }
    }

    override fun healthCheck(): Pair<String, String> {
        val k = key()
        if (k.isBlank()) return id to "BLOCKED (needs key)"
        return runCatching {
            val req = Request.Builder().url("https://api.elevenlabs.io/v1/user")
                .header("xi-api-key", k).get().build()
            TtsHttp.client.newCall(req).execute().use { r -> id to ("HTTP " + r.code) }
        }.getOrDefault(id to "error")
    }
}

/** Generic custom provider: base URL + headers + body template + format (pcm/wav/mp3). */
class GenericHttpTts(
    private val settings: () -> JarvisSettings,
    private val key: () -> String,
) : TtsProvider {
    override val id = "custom"

    override fun speak(
        textChunks: List<String>,
        voiceHint: String,
        onStarted: () -> Unit,
        onDone: (ok: Boolean) -> Unit,
        cancelToken: () -> Boolean,
    ) {
        val s = settings()
        if (s.ttsGenericBaseUrl.isBlank()) { onDone(false); return }
        var ok = false
        try {
            for (chunk in textChunks) {
                if (cancelToken()) break
                val body = s.ttsGenericBodyTemplate
                    .replace("{{text}}", JSONObject.quote(chunk).trim('"'))
                    .replace("{{voice}}", voiceHint)
                    .replace("{{key}}", key())
                val rb = Request.Builder().url(s.ttsGenericBaseUrl.trim())
                    .post(body.toRequestBody("application/json".toMediaType()))
                JSONObject(s.ttsGenericHeadersJson).let { h ->
                    h.keys().forEach { k2 -> rb.header(k2, h.optString(k2)) }
                }
                TtsHttp.client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("custom tts HTTP ${resp.code}")
                    val player = AudioTrackPlayer().apply { startOwnScope() }
                    AudioStreamFeeder.feedAll(
                        resp.body?.byteStream() ?: InputStream.nullInputStream(),
                        player, s.ttsGenericAudioFormat, onStarted)
                    ok = true
                }
            }
        } catch (e: Exception) {
            ok = false
        }
        onDone(ok)
    }

    override fun healthCheck(): Pair<String, String> {
        val s = settings()
        return if (s.ttsGenericBaseUrl.isBlank()) id to "not configured"
        else id to "configured (${s.ttsGenericAudioFormat})"
    }
}

/** Android built-in TTS - offline fallback, always available. */
class AndroidTtsProvider(private val ctx: Context) : TtsProvider {
    override val id = "androidtts"
    private var tts: TextToSpeech? = null
    private val initLatch = CountDownLatch(1)
    private val initOk = AtomicBoolean(false)

    init {
        tts = TextToSpeech(ctx) { st ->
            initOk.set(st == TextToSpeech.SUCCESS)
            if (st == TextToSpeech.SUCCESS) {
                runCatching {
                    tts?.language = java.util.Locale.UK
                    tts?.setSpeechRate(1.0f)
                }
            }
            initLatch.countDown()
        }
    }

    override fun speak(
        textChunks: List<String>,
        voiceHint: String,
        onStarted: () -> Unit,
        onDone: (ok: Boolean) -> Unit,
        cancelToken: () -> Boolean,
    ) {
        initLatch.await(4, TimeUnit.SECONDS)
        val engine = tts
        if (!initOk.get() || engine == null) { onDone(false); return }
        onStarted()
        var queued = 0
        textChunks.filter { it.isNotBlank() }.forEach { chunk ->
            if (cancelToken()) return@forEach
            engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, "jarvis-" + UUID.randomUUID())
            queued++
        }
        // Wait for completion so barge-in logic sees the player as busy.
        val end = System.currentTimeMillis() + 60_000
        while (engine.isSpeaking && System.currentTimeMillis() < end) {
            if (cancelToken()) { engine.stop(); break }
            Thread.sleep(100)
        }
        onDone(queued > 0)
    }

    override fun healthCheck(): Pair<String, String> {
        initLatch.await(4, TimeUnit.SECONDS)
        return if (initOk.get()) id to "ok" else id to "engine unavailable"
    }
}

package com.brahma.jarvis.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.brahma.jarvis.core.settings.JarvisSettings
import com.brahma.jarvis.core.settings.KeyVault
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One-shot streaming STT: mic PCM in, finalized text out. */
interface SttProvider {
    val id: String
    /**
     * Streams capture into a recognizer; returns the best final transcript.
     * [capture] yields 16k mono PCM16 frames; call `cancel()` to stop early.
     */
    fun listen(
        capture: MicCapture,
        onPartial: (String) -> Unit,
        cancelToken: () -> Boolean,
    ): String
}

interface MicCapture {
    /** Immediate in-memory capture restreamed (Android recognizer path). */
    fun captureShort(cb: (ok: Boolean) -> Unit)
    val pcmStream: java.io.InputStream?
    fun stop()
}

class AndroidRecognizerStt(private val ctx: Context) : SttProvider {
    override val id = "android"

    override fun listen(
        capture: MicCapture,
        onPartial: (String) -> Unit,
        cancelToken: () -> Boolean,
    ): String {
        // We capture a clip first (recognizer would conflict with our own AudioRecord).
        val text = AtomicReference("")
        val latch = CountDownLatch(1)
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { text.set(it); onPartial(it) }
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { text.set(it) }
                latch.countDown()
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) latch.countDown()
                else latch.countDown()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        ctx.mainExecutor.execute { sr.startListening(intent) }
        latch.await(15, TimeUnit.SECONDS)
        sr.destroy()
        return text.get()
    }
}

/** Deepgram streaming websocket STT (nova-2). PCM16 in via websocket binary. */
class DeepgramStt(private val settings: () -> JarvisSettings, private val keys: KeyVault) : SttProvider {
    override val id = "deepgram"

    override fun listen(
        capture: MicCapture,
        onPartial: (String) -> Unit,
        cancelToken: () -> Boolean,
    ): String {
        val key = keys.get("deepgram")
        if (key.isBlank()) return ""
        val result = AtomicReference("")
        val done = CountDownLatch(1)
        val url = "wss://api.deepgram.com/v1/listen?model=${settings().sttDeepgramModel}" +
            "&language=en&punctuate=true&encoding=linear16&sample_rate=16000&channels=1"
        val ws = TtsHttp.client.newWebSocket(
            Request.Builder().url(url).header("Authorization", "Token $key").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        JSONObject(text).let { o ->
                            o.optJSONObject("channel")?.optJSONArray("alternatives")
                                ?.optJSONObject(0)?.let { alt ->
                                    val t = alt.optString("transcript", "")
                                    val final = o.optBoolean("is_final", false)
                                    if (t.isNotBlank()) {
                                        if (final) { result.set(t); webSocket.close(1000, "done"); done.countDown() }
                                        else onPartial(t)
                                    }
                                }
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.countDown()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.countDown()
                }
            })
        // Feed PCM from capture.
        val stream = capture.pcmStream
        if (stream != null) {
            val buf = ByteArray(3200) // 100ms @16k 16bit mono
            val end = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < end) {
                if (cancelToken()) break
                val n = runCatching { stream.read(buf) }.getOrDefault(-1)
                if (n <= 0) break
                if (!ws.send(okio.ByteString.of(*buf.copyOf(n)))) break
                if (result.get().isNotBlank()) break
                runCatching { Thread.sleep(20) }
            }
        }
        capture.stop()
        done.await(3, TimeUnit.SECONDS)
        ws.cancel()
        return result.get()
    }
}

/** Fish Audio streaming STT using their SSE transcription endpoint with PCM input. */
class FishStt(private val settings: () -> JarvisSettings, private val keys: KeyVault) : SttProvider {
    override val id = "fishstt"

    override fun listen(
        capture: MicCapture,
        onPartial: (String) -> Unit,
        cancelToken: () -> Boolean,
    ): String {
        val key = keys.get("fishAudio")
        if (key.isBlank()) return ""
        // Capture up to 12s, send as multipart audio file to Fish Audio /v1/transcribe.
        val pcm = ByteArrayOutputStream()
        val stream = capture.pcmStream
        if (stream != null) {
            val buf = ByteArray(3200)
            val end = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < end) {
                if (cancelToken()) break
                val n = runCatching { stream.read(buf) }.getOrDefault(-1)
                if (n <= 0) break
                pcm.write(buf, 0, n)
                if (pcm.size() > 400_000) break
            }
        }
        capture.stop()
        if (pcm.size() < 3200) return ""
        // Build WAV container for upload
        val out = java.io.ByteArrayOutputStream()
        val size = pcm.size()
        val header = ByteArray(44)
        fun putI(i: Int, v: Int) { header[i] = v.toByte(); header[i+1] = (v shr 8).toByte(); header[i+2] = (v shr 16).toByte(); header[i+3] = (v shr 24).toByte() }
        fun putS(i: Int, s: String) { s.toByteArray().forEachIndexed { k, b -> header[i + k] = b } }
        putS(0, "RIFF"); putI(4, 36 + size); putS(8, "WAVE")
        putS(12, "fmt "); putI(16, 16); header[20] = 1; header[22] = 1
        putI(24, 16000); putI(28, 32000); header[34] = 16; putS(36, "data"); putI(40, size)
        out.write(header); pcm.writeTo(out)
        val wav = out.toByteArray()
        val boundary = "jarvis-b-" + System.currentTimeMillis()
        val body = okhttp3.MultipartBody.Builder(boundary).setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", "capture.wav",
                okhttp3.RequestBody.create("audio/wav".toMediaType(), wav))
            .build()
        val req = Request.Builder().url("https://api.fish.audio/openapi/v1/asr/general")
            .header("Authorization", "Bearer $key").post(body).build()
        return runCatching {
            TtsHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val o = JSONObject(resp.body?.string().orEmpty())
                o.optString("text", "").also { if (it.isNotBlank()) onPartial(it) }
            }
        }.getOrDefault("")
    }
}

/** Generic HTTP STT: posts WAV to user's endpoint, expects JSON {"text": "..."} */
class GenericStt(private val settings: () -> JarvisSettings, private val keys: KeyVault) : SttProvider {
    override val id = "custom"

    override fun listen(
        capture: MicCapture,
        onPartial: (String) -> Unit,
        cancelToken: () -> Boolean,
    ): String {
        val s = settings()
        if (s.sttGenericBaseUrl.isBlank()) return ""
        val pcm = ByteArrayOutputStream()
        val stream = capture.pcmStream
        if (stream != null) {
            val buf = ByteArray(3200)
            val end = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < end) {
                if (cancelToken()) break
                val n = runCatching { stream.read(buf) }.getOrDefault(-1)
                if (n <= 0) break
                pcm.write(buf, 0, n)
                if (pcm.size() > 600_000) break
            }
        }
        capture.stop()
        if (pcm.size() < 3200) return ""
        val wav = WavBuilder.wrap(pcm.toByteArray(), 16000)
        val rb = Request.Builder().url(s.sttGenericBaseUrl.trim())
            .post(okhttp3.RequestBody.create("audio/wav".toMediaType(), wav))
        JSONObject(s.sttGenericHeadersJson).let { h -> h.keys().forEach { k -> rb.header(k, h.optString(k)) } }
        return runCatching {
            TtsHttp.client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val o = JSONObject(resp.body?.string().orEmpty())
                o.optString("text", o.optString("transcript", ""))
            }
        }.getOrDefault("")
    }
}

object WavBuilder {
    fun wrap(pcm: ByteArray, rate: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val header = ByteArray(44)
        fun putI(i: Int, v: Int) { header[i] = v.toByte(); header[i+1] = (v shr 8).toByte(); header[i+2] = (v shr 16).toByte(); header[i+3] = (v shr 24).toByte() }
        fun putS(i: Int, s: String) { s.toByteArray().forEachIndexed { k, b -> header[i + k] = b } }
        putS(0, "RIFF"); putI(4, 36 + pcm.size); putS(8, "WAVE")
        putS(12, "fmt "); putI(16, 16)
        header[20] = 1; header[22] = 1
        putI(24, rate); putI(28, rate * 2)
        header[32] = 2; header[34] = 16
        putS(36, "data"); putI(40, pcm.size)
        out.write(header); out.write(pcm)
        return out.toByteArray()
    }
}

object SttRouter {
    fun makeProviders(settings: () -> JarvisSettings, ctx: Context, keys: KeyVault): List<SttProvider> {
        val os = AndroidRecognizerStt(ctx)
        val list = mutableListOf<SttProvider>()
        when (settings().sttPrimary) {
            0 -> list += os
            1 -> { list += DeepgramStt(settings, keys); list += os }
            2 -> { list += FishStt(settings, keys); list += os }
            3 -> { list += GenericStt(settings, keys); list += os }
        }
        if (settings().sttPrimary != 0) list.add(0, os)
        return list
    }
}

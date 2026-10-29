package com.brahma.jarvis.core.voice

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Wake word: Vosk offline keyword spotting ("hey jarvis" / "jarvis" / "wake up jarvis").
 * Small model (~40 MB) downloaded once into filesDir/vosk-model from alphacephei.com
 * official mirror with progress callbacks; push-to-talk and manual Wake work without it.
 */
class WakeWordEngine(
    private val ctx: Context,
    private val phrases: () -> List<String>,
    private val sensitivity: () -> Int,
    private val onWake: () -> Unit,
) {
    sealed interface State {
        object IdleNoModel : State
        data class Downloading(val percent: Int) : State
        object Ready : State
        data class Failed(val why: String) : State
    }

    val state = AtomicReference<State>(State.IdleNoModel)

    private val modelDir get() = File(ctx.filesDir, "vosk-model")
    @Volatile private var recognizer: KaldiRecognizerBridge? = null

    val isReady: Boolean get() = state.get() is State.Ready
    val modelInstalled: Boolean get() = modelDir.resolve("final.mdl").exists()

    /** Downloads + extracts the official vosk small english model. */
    fun downloadModel(onProgress: (Int) -> Unit, onDone: (ok: Boolean, why: String) -> Unit) {
        if (modelInstalled) { state.set(State.Ready); onDone(true, "already installed"); return }
        Thread {
            try {
                state.set(State.Downloading(0))
                val tmp = File(ctx.cacheDir, "vosk_small.zip")
                val url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 60_000
                conn.connect()
                val total = conn.contentLengthLong
                conn.inputStream.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n); read += n
                            val pct = if (total > 0) (read * 100 / total).toInt() else 1
                            state.set(State.Downloading(pct))
                            onProgress(pct)
                        }
                    }
                }
                // extract
                modelDir.deleteRecursively()
                modelDir.mkdirs()
                ZipInputStream(FileInputStream(tmp)).use { z ->
                    var e = z.nextEntry
                    val rootPrefix = "vosk-model-small-en-us-0.15/"
                    while (e != null) {
                        val outName = e.name.removePrefix(rootPrefix)
                        if (outName.isNotBlank() && !outName.contains("..")) {
                            val out = modelDir.resolve(outName)
                            if (e.isDirectory) out.mkdirs()
                            else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { z.copyTo(it) }
                            }
                        }
                        e = z.nextEntry
                    }
                }
                tmp.delete()
                if (modelInstalled) {
                    state.set(State.Ready)
                    onDone(true, "ok")
                } else {
                    state.set(State.Failed("archive incomplete"))
                    onDone(false, "archive incomplete")
                }
            } catch (e: Exception) {
                state.set(State.Failed(e.message ?: "download failed"))
                onDone(false, e.message ?: "download failed")
            }
        }.apply { isDaemon = true }.start()
    }

    /** Feed one PCM16 short[] frame; fires onWake when a phrase completes. */
    fun accept(samples: ShortArray, n: Int) {
        val r = recognizer ?: wakeBridgeLazy()?.also { recognizer = it } ?: return
        r.accept(samples, n) { heard ->
            if (phrases().any { p -> heard.lowercase().contains(p) }) onWake()
        }
    }

    private fun wakeBridgeLazy(): KaldiRecognizerBridge? = runCatching {
        if (!modelInstalled) return null
        KaldiRecognizerBridge(modelDir, phrases())
    }.getOrNull()

    fun stop() {
        recognizer?.close()
        recognizer = null
    }

    /** Test entry used by Diagnostics ("does the spotter fire on 'jarvis' in test clip?"). */
    fun selfTest(): Pair<String, String> {
        val s = state.get()
        return when (s) {
            is State.Ready, is State.IdleNoModel ->
                if (modelInstalled) "wakeword" to "model ready" else "wakeword" to "no model (BLOCKED - run download in Voice settings)"
            is State.Downloading -> "wakeword" to "downloading ${s.percent}%"
            is State.Failed -> "wakeword" to "failed: ${s.why}"
        }
    }
}

/**
 * Thin wrapper: loads the Vosk model via reflection-free Kaldi API and matches keyword
 * grammar results. Vosk-android exposes org.kaldi.Recognizer.
 */
class KaldiRecognizerBridge(modelDir: File, phrases: List<String>) {
    private val model: Any
    private val recognizer: Any
    private val phraseList = phrases.map { it.trim().lowercase() }.filter { it.isNotBlank() }

    init {
        val klazz = Class.forName("org.kaldi.Model")
        model = klazz.getDeclaredConstructor(String::class.java).newInstance(modelDir.absolutePath)
        val recK = Class.forName("org.kaldi.Recognizer")
        val grammar = JSONArray().apply {
            phraseList.forEach { put(it) }
            put("[unk]")
        }.toString()
        recognizer = recK.getDeclaredConstructor(Class.forName("org.kaldi.Model"), Float::class.javaPrimitiveType, String::class.java)
            .newInstance(model, 16000.0f, grammar)
    }

    fun accept(samples: ShortArray, n: Int, onMatch: (String) -> Unit) {
        val r = recognizer
        val m = r.javaClass.getMethod("acceptWaveForm", ShortArray::class.java, Int::class.javaPrimitiveType)
        val isFinal = m.invoke(r, samples, n) as Boolean
        val resMethod = r.javaClass.getMethod(if (isFinal) "getResult" else "getPartialResults")
        val json = resMethod.invoke(r).toString()
        runCatching {
            val o = org.json.JSONObject(json)
            o.optJSONArray("result")?.let { arr ->
                val words = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("word") }
                val phrase = words.joinToString(" ").trim().lowercase()
                if (phrase.isNotBlank()) onMatch(phrase)
            }
        }
    }

    fun close() {
        runCatching {
            recognizer.javaClass.getMethod("close").invoke(recognizer)
        }
        runCatching {
            model.javaClass.getMethod("free").invoke(model)
        }
    }
}

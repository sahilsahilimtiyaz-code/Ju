package com.brahma.jarvis.core.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Live sentence chunker over an LLM token stream: emits complete sentences as they
 * close so TTS starts while the model is still generating (first audio < ~1 s).
 */
object Chunker {
    class LiveStream(private val maxLen: Int = 220) {
        private val buf = StringBuilder()
        val pending: String get() = buf.toString()

        fun push(delta: String): List<String> {
            buf.append(delta)
            val out = mutableListOf<String>()
            while (true) {
                // A sentence closes only when a terminator is followed by whitespace:
                // end-of-buffer is NOT end-of-sentence while the LLM is still streaming.
                val m = Regex("[.!?][\"')]?(?=\\s)").find(buf) ?: break
                val end = m.range.last + 1
                var s = buf.substring(0, end).trim()
                buf.delete(0, end)
                if (s.length > maxLen) out += splitLong(s, maxLen) else if (s.isNotBlank()) out += s
            }
            if (buf.length > 600) { // paranoia cap for pathological output
                val force = buf.substring(0, 400).trim()
                buf.replace(0, 400, "")
                out += force
            }
            return out
        }

        fun flush(): String {
            val rest = buf.toString().trim()
            buf.setLength(0)
            return rest
        }

        private fun splitLong(s: String, maxLen: Int): List<String> {
            val words = s.split(" ")
            val lumps = mutableListOf<String>()
            var cur = StringBuilder()
            words.forEach { w ->
                if (cur.length + w.length > maxLen) { lumps += cur.toString().trim(); cur = StringBuilder() }
                cur.append(w).append(' ')
            }
            if (cur.isNotBlank()) lumps += cur.toString().trim()
            return lumps
        }
    }
}

/**
 * One-shot PCM16 player: start the AudioTrack, feed bytes as they arrive from a
 * streaming TTS provider, stop on barge-in. sampleRate/channels are provider-set.
 */
class AudioTrackPlayer(private val scopeOuter: CoroutineScope? = null) {
    private var track: AudioTrack? = null
    private var scope: CoroutineScope? = null
    @Volatile private var active = false

    fun start(sampleRate: Int, channels: Int = 1) {
        cancel()
        active = true
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(mask).build())
            .setBufferSizeInBytes(maxOf(min, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    fun offerPcm(bytes: ByteArray) {
        if (!active) return
        val t = track ?: return
        scope?.launch(Dispatchers.IO) {
            runCatching { t.write(bytes, 0, bytes.size) }
        }
    }

    fun blockUntilDrained(timeoutMs: Long = 10_000) {
        val t = track ?: return
        val start = System.currentTimeMillis()
        while (t.playbackHeadPosition >= 0 && System.currentTimeMillis() - start < timeoutMs) {
            runCatching { Thread.sleep(80) }
        }
    }

    fun cancel() {
        active = false
        scope?.cancel(); scope = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    val isPlaying: Boolean get() = active

    fun startOwnScope() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}

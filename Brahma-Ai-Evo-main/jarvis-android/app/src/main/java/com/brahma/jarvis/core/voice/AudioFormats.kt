package com.brahma.jarvis.core.voice

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** WAV header: sample rate, channels, and bytes to skip before data. */
object WavHeader {
    fun probe(b: ByteArray): Triple<Int, Int, Int>? {
        if (b.size < 44) return null
        val riff = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte()
        if (!riff) return null
        val rate = (b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or
            ((b[26].toInt() and 0xFF) shl 16) or ((b[27].toInt() and 0xFF) shl 24)
        val ch = b[22].toInt() and 0xFF
        return Triple(if (rate > 4000) rate else 24000, if (ch == 2) 2 else 1, 44)
    }
}

/**
 * Real MP3 -> PCM16 decode via platform MediaCodec ("audio/mpeg"), streaming.
 * Feeder thread: push(mp3 bytes) accumulates; codec loop draining output into a sink.
 */
class Mp3Decoder(private val sink: (pcm: ByteArray, rate: Int, ch: Int) -> Unit) {
    private val queue = ArrayDeque<ByteArray>()
    private val done = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var decoder: MediaCodec? = null
    private var thread: Thread? = null
    private var rate = 24000
    private var channels = 1

    fun push(mp3: ByteArray) {
        synchronized(queue) { queue.add(mp3) }
    }

    fun finish() = done.set(true)

    fun run() {
        if (!started.compareAndSet(false, true)) return
        thread = Thread {
            try {
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 24000, 1)
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
                codec.configure(fmt, null, null, 0)
                codec.start()
                decoder = codec
                rate = 24000; channels = 1
                val info = MediaCodec.BufferInfo()
                var sawFormat = false
                var inputEos = false
                while (!cancelled.get()) {
                    if (!inputEos) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val b = codec.getInputBuffer(idx) ?: continue
                            b.clear()
                            val chunk = takeBytes(b.capacity())
                            if (chunk == null && done.get()) {
                                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else if (chunk == null) {
                                codec.queueInputBuffer(idx, 0, 0, 0, 0)
                            } else {
                                b.put(chunk)
                                codec.queueInputBuffer(idx, 0, chunk.size, 0, 0)
                            }
                        }
                    }
                    val st = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        st == MediaCodec.INFO_TRY_AGAIN_LATER ->
                            if (inputEos && sawFormat) break
                        st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            sawFormat = true
                        }
                        st >= 0 -> {
                            val out = codec.getOutputBuffer(st)
                            if (out != null && info.size > 0) {
                                val pcm = ByteArray(info.size)
                                out.position(info.offset); out.limit(info.offset + info.size)
                                out.get(pcm)
                                sink(pcm, rate, channels)
                            }
                            if (st >= 0) codec.releaseOutputBuffer(st, false)
                        }
                    }
                }
                codec.stop(); codec.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        thread?.start()
    }

    private val cancelled = AtomicBoolean(false)
    fun cancel(): Boolean { cancelled.set(true); return true }

    private fun isEmpty(): Boolean = synchronized(queue) { queue.isEmpty() }

    private fun takeBytes(cap: Int): ByteArray? = synchronized(queue) {
        if (queue.isEmpty()) return null
        var acc = queue.removeFirst()
        while (acc.size < cap && queue.isNotEmpty()) {
            val nxt = queue.removeFirst()
            acc += nxt
        }
        if (acc.size > cap) {
            queue.addFirst(acc.copyOfRange(cap, acc.size))
            acc = acc.copyOf(cap)
        }
        acc
    }

    fun join() {
        runCatching { thread?.join(15_000) }
    }
}

/** Unified streamed-audio feeder: raw PCM / WAV / MP3 into an AudioTrackPlayer. */
class AudioStreamFeeder(
    private val dest: AudioTrackPlayer,
    private val fmtHint: String, // "pcm" | "mp3" | "wav" | "auto"
) {
    private val first = AtomicBoolean(true)
    private var mp3: Mp3Decoder? = null
    private var pendingHeader = ByteArray(0)

    fun feed(bytes: ByteArray) {
        when (fmtHint) {
            "pcm" -> direct(bytes)
            "wav" -> {
                pendingHeader += bytes
                val p = WavHeader.probe(pendingHeader)
                if (p != null) {
                    val (rate, ch, off) = p
                    dest.start(rate, ch); markStarted()
                    dest.offerPcm(pendingHeader.copyOfRange(off, pendingHeader.size))
                    pendingHeader = ByteArray(0)
                }
            }
            else -> { // auto / mp3
                if (bytes.size > 3 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
                    pendingHeader += bytes
                    val p = WavHeader.probe(pendingHeader)
                    if (p != null) {
                        val (rate, ch, off) = p
                        dest.start(rate, ch); markStarted()
                        dest.offerPcm(pendingHeader.copyOfRange(off, pendingHeader.size))
                        pendingHeader = ByteArray(0)
                    }
                    return
                }
                val dec = mp3 ?: Mp3Decoder { pcm, rate, ch ->
                    if (cmp(first)) { dest.start(rate, ch); markStarted() }
                    dest.offerPcm(pcm)
                }.also { it.run(); mp3 = it }
                dec.push(bytes)
            }
        }
    }

    var onStarted: () -> Unit = {}
    private fun markStarted() { runCatching { onStarted() } }
    private fun direct(bytes: ByteArray) { cmp(first); dest.offerPcm(bytes) }

    private fun cmp(a: AtomicBoolean): Boolean = a.compareAndSet(true, false)

    fun close() {
        mp3?.finish(); mp3?.join()
        mp3 = null
        // if the format was PCM and we never 'started', start with defaults so audio still plays
        if (first.get()) { dest.start(24000, 1); markStarted(); cmp(first) }
    }

    fun cancel() { mp3?.cancel() }

    companion object {
        /** Reusable read-to-end feeder for full-body responses. */
        fun feedAll(stream: InputStream, dest: AudioTrackPlayer, fmt: String, onStarted: () -> Unit) {
            val feeder = AudioStreamFeeder(dest, fmt).apply { this.onStarted = onStarted }
            dest.startOwnScope()
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = runCatching { stream.read(buf) }.getOrDefault(-1)
                    if (n <= 0) break
                    feeder.feed(buf.copyOf(n))
                }
            } finally {
                feeder.close()
                dest.blockUntilDrained(20_000)
                dest.cancel()
            }
        }
    }
}

/** Convenience for legacy call sites that pass a raw InputStream. */
fun InputStream.feedPlayer(dest: AudioTrackPlayer, fmt: String, onStarted: () -> Unit) {
    AudioStreamFeeder.feedAll(this, dest, fmt, onStarted)
}

private fun ByteArrayInputStream.unused() = false

package com.brahma.jarvis.core.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.brahma.jarvis.MainActivity
import com.brahma.jarvis.R
import com.brahma.jarvis.core.Runtime
import com.brahma.jarvis.core.brain.BrainEvent
import com.brahma.jarvis.core.brain.GreetingEngine
import com.brahma.jarvis.core.session.AssistantSession
import com.brahma.jarvis.core.session.Turn
import com.brahma.jarvis.core.voice.MicCapture
import com.brahma.jarvis.core.voice.SttRouter
import com.brahma.jarvis.core.voice.SttProvider
import com.brahma.jarvis.core.voice.WakeWordEngine
import java.util.concurrent.TimeUnit

/**
 * Foreground service (microphone type): continuous mic, on-device wake word
 * ("hey jarvis"/"jarvis"/"wake up jarvis"), STT round trip, TTS out, barge-in.
 * The mic stays live until the user switches it off.
 */
class JarvisMicService : Service() {

    private val runtime: Runtime by lazy { Runtime.get(this) }
    private var record: AudioRecord? = null
    @Volatile private var recording = false
    private lateinit var wake: WakeWordEngine

    enum class Mode { LISTENING_FOR_WAKE, WATCH_SPEAKING, CAPTURING }
    @Volatile private var currentMode = Mode.LISTENING_FOR_WAKE
    private var spikeMs = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wake = WakeWordEngine(
            this,
            { runtime.current().wakePhrases.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() } },
            { runtime.current().wakeSensitivity },
            ::onWakePhrase,
        )
        startForeground(NOTIF_ID, buildNotification())
        AssistantSession.micLive.value = true
        AssistantSession.statusLine.value = "MIC LIVE"
        startMicLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        recording = false
        runCatching { record?.stop(); record?.release() }
        record = null
        wake.stop()
        isRunning = false
        AssistantSession.micLive.value = false
        AssistantSession.statusLine.value = "SLEEPING"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startMicLoop() {
        if (!hasMic()) {
            AssistantSession.statusLine.value = "MIC PERMISSION NEEDED"
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, 8192))
        rec.startRecording()
        record = rec
        recording = true
        Thread {
            val buf = ShortArray(2048)
            while (recording) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) continue
                when (currentMode) {
                    Mode.LISTENING_FOR_WAKE -> wake.accept(buf, n)
                    Mode.WATCH_SPEAKING -> if (amplitudeSpike(buf, n)) currentMode = Mode.LISTENING_FOR_WAKE
                    Mode.CAPTURING -> {} // streaming STT reads the record directly
                }
            }
        }.apply { isDaemon = false }.start()
    }

    /** Loud sustained speech while Jarvis talks => barge-in: stop playback. */
    private fun amplitudeSpike(buf: ShortArray, n: Int): Boolean {
        var e = 0L
        for (i in 0 until n) e += buf[i].toLong() * buf[i]
        val rms = Math.sqrt(e.toDouble() / n)
        val threshold = 1200.0 + (100 - runtime.current().wakeSensitivity) * 18.0
        if (rms > threshold) {
            if (spikeMs == 0L) spikeMs = System.currentTimeMillis()
            if (System.currentTimeMillis() - spikeMs > 250) {
                runtime.brain.cancel()
                runtime.tts.stopSpeaking()
                AssistantSession.speaking.value = false
                return true
            }
        } else if (spikeMs > 0 && System.currentTimeMillis() - spikeMs > 250) spikeMs = 0
        return false
    }

    // ---- wake -> greeting -> capture -> ask ----
    private fun onWakePhrase() {
        if (runtime.brain.busy || runtime.tts.currentlySpeaking) return
        Thread {
            currentMode = Mode.WATCH_SPEAKING // never listen for wake words while Jarvis talks
            val greeting = GreetingEngine.wakeGreeting(runtime)
            AssistantSession.statusLine.value = "RESPONDING"
            AssistantSession.pushTurn(Turn("jarvis", greeting))
            AssistantSession.speaking.value = true
            val done = java.util.concurrent.CountDownLatch(1)
            runtime.tts.speak(greeting) { _, _ ->
                AssistantSession.speaking.value = false
                done.countDown()
            }
            done.await(60, TimeUnit.SECONDS)
            if (currentMode != Mode.WATCH_SPEAKING) return@Thread
            captureAndAnswer()
        }.apply { isDaemon = false }.start()
    }

    private fun captureAndAnswer() {
        if (!hasMic()) { currentMode = Mode.LISTENING_FOR_WAKE; return }
        currentMode = Mode.CAPTURING
        AssistantSession.listening.value = true
        AssistantSession.statusLine.value = "LISTENING"
        val provider: SttProvider = runCatching {
            SttRouter.makeProviders({ runtime.current() }, this, runtime.keys).first()
        }.getOrDefault(com.brahma.jarvis.core.voice.AndroidRecognizerStt(this))
        val rec = provider.let { p ->
            val cap = MicCaptureShim(this, record)
            runCatching { p.listen(cap, { }, { false }) }.getOrDefault("")
        }
        AssistantSession.listening.value = false
        if (rec.isBlank() || rec.length > 2000) {
            currentMode = Mode.LISTENING_FOR_WAKE
            AssistantSession.statusLine.value = "MIC LIVE"
            return
        }
        AssistantSession.pushTurn(Turn("user", rec))
        AssistantSession.statusLine.value = "THINKING"
        val done = java.util.concurrent.CountDownLatch(1)
        runtime.brain.ask(rec) {
            AssistantSession.statusLine.value = "MIC LIVE"
            done.countDown()
        }
        done.await(120, TimeUnit.SECONDS)
        currentMode = Mode.LISTENING_FOR_WAKE
    }

    /** MicCapture wrapper exposing the running AudioRecord to STT providers. */
    class MicCaptureShim(ctx: Context, private val rec: AudioRecord?) : MicCapture {
        private val active = java.util.concurrent.atomic.AtomicBoolean(false)
        override val pcmStream: java.io.InputStream get() = PcmStream(rec, active)
        override fun captureShort(cb: (Boolean) -> Unit) { active.set(true); cb(true) }
        override fun stop() { active.set(false) }
    }

    class PcmStream(private val rec: AudioRecord?, private val active: java.util.concurrent.atomic.AtomicBoolean) :
        java.io.InputStream() {
        private val tmp = ShortArray(2048)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = rec ?: return -1
            if (!active.get()) return -1
            val shorts = ShortArray(minOf(len / 2, tmp.size))
            val n = runCatching { r.read(shorts, 0, shorts.size) }.getOrDefault(-1)
            if (n <= 0) return -1
            for (i in 0 until n) {
                b[off + i * 2] = (shorts[i].toInt() and 0xFF).toByte()
                b[off + i * 2 + 1] = (shorts[i].toInt() shr 8).toByte()
            }
            return n * 2
        }
        override fun read(): Int {
            val r = rec ?: return -1
            if (!active.get()) return -1
            val n = runCatching { r.read(tmp, 0, 1) }.getOrDefault(-1)
            return if (n <= 0) -1 else tmp[0].toInt() and 0xFF
        }
    }

    // ---- notifications ----
    private fun buildNotification(): Notification {
        val ch = NotificationChannel(CH_MIC, getString(R.string.notif_mic_channel),
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_MIC)
            .setContentTitle("Jarves is listening")
            .setContentText("Wake: \"hey jarves\" · tap to open")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    /** Post-boot notice: cannot start a mic FGS from the background on Android 14+. */
    fun postBootNotice(ctx: Context) {
        val ch = NotificationChannel(CH_MIC, ctx.getString(R.string.notif_mic_channel),
            NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val open = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_MIC)
            .setContentTitle(ctx.getString(R.string.notif_wake_title))
            .setContentText(ctx.getString(R.string.notif_wake_body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(BOOT_NOTIF_ID, n) }
    }

    companion object {
        const val CH_MIC = "jarvis_mic"
        const val NOTIF_ID = 11
        const val BOOT_NOTIF_ID = 12

        var isRunning = false
            private set

        fun start(ctx: Context) {
            if (!isRunning) ctx.startForegroundService(Intent(ctx, JarvisMicService::class.java))
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, JarvisMicService::class.java))
    }
}

/** Standalone boot notice so the receiver never instantiates the service. */
object JarvisBootNotice {
    fun post(ctx: Context) {
        val ch = NotificationChannel(JarvisMicService.CH_MIC, ctx.getString(R.string.notif_mic_channel),
            NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val open = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, JarvisMicService.CH_MIC)
            .setContentTitle(ctx.getString(R.string.notif_wake_title))
            .setContentText(ctx.getString(R.string.notif_wake_body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(JarvisMicService.BOOT_NOTIF_ID, n) }
    }
}

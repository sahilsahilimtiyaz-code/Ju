package com.brahma.jarvis.core.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brahma.jarvis.MainActivity
import com.brahma.jarvis.R
import com.brahma.jarvis.core.Runtime
import com.brahma.jarvis.core.brain.GreetingEngine
import com.brahma.jarvis.core.brain.Persona
import com.brahma.jarvis.core.evolve.EvolutionWorker
import com.brahma.jarvis.core.session.AssistantSession
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Pure gate logic - unit-testable, no Android deps. */
data class GateState(
    val initiativeLevel: Int,      // 0..3
    val hour: Int,
    val quietStart: Int,
    val quietEnd: Int,
    val todayCount: Int,
    val budget: Int,
    val lastFiredHourAgo: Double,  // hours since last proactive line of this kind
    val inCall: Boolean,
    val reactionWeight: Double,    // negative = user ignored recent lines
    val kind: String,
)

object ProactiveGate {
    const val COOLDOWN_HOURS = 3.0

    fun allow(g: GateState): Boolean {
        if (g.initiativeLevel <= 0) return false
        if (g.todayCount >= g.budget) return false
        if (g.inCall) return false
        if (g.lastFiredHourAgo < COOLDOWN_HOURS) return false
        val quiet = if (g.quietStart <= g.quietEnd)
            g.hour >= g.quietStart && g.hour < g.quietEnd
        else g.hour >= g.quietStart || g.hour < g.quietEnd
        if (quiet) return false
        // reactions learned: repeated ignores -> progressively more reluctant
        if (g.reactionWeight <= -6.0) return false
        val levelBias = when (g.initiativeLevel) {
            1 -> g.reactionWeight > -2.0
            2 -> g.reactionWeight > -4.0
            else -> true
        }
        return levelBias
    }
}

object ProactiveEngine {
    const val CH_PROACTIVE = "jarvis_proactive"

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "jarvis-proactive",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES).build())
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "jarvis-evolution",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EvolutionWorker>(24, TimeUnit.HOURS).build())
    }

    fun inCallOrMedia(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.mode != AudioManager.MODE_NORMAL
    }

    fun buildGate(runtime: Runtime, kind: String): GateState {
        val s = runtime.current()
        val hours = lastAgo(runtime, kind)
        return GateState(
            s.initiativeLevel, Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            s.quietHoursStart, s.quietHoursEnd, todayCount(runtime),
            s.proactiveDailyBudget, hours, inCallOrMedia(runtime.appCtx),
            runtime.memory.signalWeights().getOrDefault("proactive_ignored", 0.0) +
                runtime.memory.signalWeights().getOrDefault("proactive_engaged", 0.0) *
                // engaged counteracts ignored
                (if (runtime.memory.signalWeights().getOrDefault("proactive_engaged", 0.0) < 0) 0.0 else 1.0) -
                runtime.memory.signalWeights().getOrDefault("proactive_engaged", 0.0) * 0.5,
            kind,
        )
    }

    fun todayCount(runtime: Runtime): Int = runtime.memory.proactiveCountRecent(24)

    fun lastAgo(runtime: Runtime, kind: String): Double {
        val lines = runtime.memory.recentProactiveLines(20)
        // coarse: newest log ts is not exposed, approximate by count of the last hour
        val lastHourCount = runtime.memory.proactiveRecentWithinMs(3600_000, kind)
        if (lastHourCount > 0) return 0.5
        return if (lines.isEmpty()) 999.0 else 4.0
    }

    /** LLM-written line grounded ONLY in real context; fallback line when no LLM. */
    fun composeLine(runtime: Runtime, kind: String): String {
        val ctx = buildContext(runtime)
        return runCatching {
            val sys = Persona.BASE.replace("JARVIS", runtime.current().personaName) +
                "\nYou speak FIRST (proactively). Exactly ONE short spoken line." +
                "\nUse ONLY the context provided. Never invent events, deadlines or data." +
                "\nProactive kind: $kind. Context: $ctx"
            val out = LlmAid.complete(runtime, sys, "Compose the line now.", strong = false)
            if (out.isBlank()) GreetingEngine.proactiveFallback(kind, runtime)
            else Persona.spoken(out, 300)
        }.getOrDefault(GreetingEngine.proactiveFallback(kind, runtime))
    }

    private fun buildContext(runtime: Runtime): String {
        val device = runCatching { runtime.tools.systemReportForPrompt() }.getOrDefault("")
        val facts = runtime.memory.facts(8).joinToString(" | ") { it.fact }
        val today = runtime.memory.recentProactiveLines(30).joinToString(" || ")
        return "device=$device goals=$facts recent_proactive=$today"
    }

    fun deliver(runtime: Runtime, kind: String, line: String) {
        runtime.memory.logProactive(kind, line, "notification")
        val ctx = runtime.appCtx
        val ch = NotificationChannel(CH_PROACTIVE, "Jarvis proactive",
            NotificationManager.IMPORTANCE_HIGH)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val open = PendingIntent.getActivity(ctx, 42,
            Intent(ctx, MainActivity::class.java).putExtra("jarvis_line", line),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_PROACTIVE)
            .setContentTitle("${runtime.current().personaName} · proactive")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) nm.notify(771, n)
        AssistantSession.pushTurn(com.brahma.jarvis.core.session.Turn("proactive", line))
    }
}

/** Typed access to the brain's single-shot completion (used by proactive + evolution). */
object LlmAid {
    fun complete(runtime: Runtime, system: String, user: String, strong: Boolean): String =
        runtime.brain.completeLlm(system, user, strong)
}

class ProactiveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val runtime = Runtime.peek(applicationContext) ?: return Result.success()
        val kinds = listOf("morning_brief", "evening_brief", "long_silence")
        val kind = pickKind(kinds)
        val gate = ProactiveEngine.buildGate(runtime, kind)
        if (!ProactiveGate.allow(gate)) return Result.success()
        val line = ProactiveEngine.composeLine(runtime, kind)
        if (line.isNotBlank()) ProactiveEngine.deliver(runtime, kind, line)
        return Result.success()
    }

    private fun pickKind(kinds: List<String>): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (h in 7..11) return "morning_brief"
        if (h in 17..22) return "evening_brief"
        return "long_silence"
    }
}

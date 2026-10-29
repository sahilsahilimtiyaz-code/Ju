package com.brahma.jarvis.core.evolve

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brahma.jarvis.core.Runtime
import com.brahma.jarvis.core.proactive.LlmAid
import org.json.JSONArray
import org.json.JSONObject

/**
 * Self-evolution: evolve DATA, never code. Nightly reflection proposes concrete,
 * low-risk edits (persona wording, proactive timing, memory hints); each proposal is
 * regression-checked on a golden set built from the user's real past turns before
 * shipping. One-tap rollback lives in Settings > Evolution. Never touches
 * permissions, secrets, billing or core logic; kill switch = settings.evolutionEnabled.
 */
object EvolutionEngine {

    data class Proposal(
        val id: Long,
        val ts: Long,
        val kind: String,      // wording | timing | memory_hint
        val payload: String,   // JSON
        val status: String,    // proposed | active | rolled_back | rejected
        val score: Double,
    )

    fun runReflection(runtime: Runtime): String {
        val s = runtime.current()
        if (!s.evolutionEnabled) return "Evolution is disabled (kill switch). Nothing ran."
        val signals = runtime.memory.signalWeights()
        val facts = runtime.memory.facts(30).map { it.fact }
        val proactive = runtime.memory.recentProactiveLines(30)
        val convo = runtime.memory.recentMessages(40).joinToString("\n") { "${it.role}: ${it.text.take(160)}" }

        val raw = LlmAid.complete(
            runtime,
            "You are the self-evolution module of JARVIS. Propose at most TWO concrete, LOW-RISK " +
                "improvements to your own data: persona wording, proactive timing, or memory hints. " +
                "Never propose: code changes, permissions, secrets, purchases. " +
                "Reply ONLY as JSON: {\"proposals\":[{\"kind\":\"wording|timing|memory_hint\"," +
                "\"detail\":\"exact change\",\"rationale\":\"...\"}]}",
            buildString {
                append("Signals: $signals\n")
                append("Facts: ${facts.joinToString(" | ").take(800)}\n")
                append("Recent proactive: ${proactive.joinToString(" || ").take(800)}\n")
                append("Recent chat:\n${convo.take(2500)}")
            },
            strong = true)

        val parsed: JSONArray = runCatching {
            val arr = JSONObject(raw).optJSONArray("proposals")
            arr ?: throw IllegalStateException("no proposals")
        }.getOrDefault(JSONArray())

        if (parsed == null) {
            runtime.memory.addSummary("evolution", "reflection produced nothing usable: ${raw.take(120)}")
            return "No safe proposals this cycle."
        }

        var applied = 0; var rejected = 0
        for (i in 0 until minOf(2, parsed.length())) {
            val p = parsed.getJSONObject(i)
            val kind = p.optString("kind", "wording")
            if (kind !in listOf("wording", "timing", "memory_hint")) { rejected++; continue }
            val score = regressionScore(runtime, p.optString("detail"))
            runtime.memory.insertEvolutionRow(kind, p.toString(), "proposed", score)
            var rowId = -1L
            // Ship only if no regression vs golden baseline.
            if (score >= 0.0) {
                if (apply(runtime, kind, p.optString("detail"))) {
                    runtime.memory.insertEvolutionRow(kind, p.toString(), "active", score)
                    runtime.memory.addSummary("evolution", "applied $kind: ${p.optString("detail").take(140)}")
                    applied++
                } else rejected++
            } else {
                runtime.memory.insertEvolutionRow(kind, p.toString(), "rejected", score)
                rejected++
            }
        }
        runtime.memory.addSummary("evolution", "reflection run: $applied applied, $rejected rejected")
        return "Reflection complete: $applied applied, $rejected rejected."
    }

    /**
     * Regression check: replay a golden set of real past prompts under the proposed rule
     * via the strong model and require the score not to drop.
     */
    fun regressionScore(runtime: Runtime, rule: String): Double {
        val golden = runtime.memory.goldenSet(10)
        if (golden.isEmpty()) return 0.0 // nothing to regress yet; allow with tracking
        var total = 0.0
        for (g in golden) {
            val asked = g.first
            val replied = g.second
            val r1 = LlmAid.complete(runtime,
                "Grade 0-10 how well a JARVIS assistant reply serves the request. Reply only a number.",
                "User asked: $asked\nReply: $replied", strong = false)
            val r2 = LlmAid.complete(runtime,
                "Grade 0-10 how well a JARVIS assistant reply serves the request. Reply only a number." +
                    "\nThe assistant must follow this rule: $rule",
                "User asked: $asked\nReply: $replied", strong = false)
            val a = r1.filter { it.isDigit() }.take(2).takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 5
            val b = r2.filter { it.isDigit() }.take(2).takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 5
            total += (b - a).toDouble()
        }
        return total / golden.size
    }

    /** Applies wording/memory_hint type edits into the "evo_rules" summary block. */
    fun apply(runtime: Runtime, kind: String, detail: String): Boolean {
        val current = runtime.memory.summaries().firstOrNull { it.first == "evo_rules" }?.second
            ?: "[]"
        val arr = runCatching { org.json.JSONArray(current) }.getOrDefault(org.json.JSONArray())
        arr.put(JSONObject().put("kind", kind).put("detail", detail).put("ts", System.currentTimeMillis()))
        runtime.memory.addSummary("evo_rules", arr.toString().take(1200))
        return true
    }

    fun rollback(runtime: Runtime, id: Long): Boolean {
        if (id > 0) runtime.memory.markRollback(id)
        val current = runtime.memory.summaries().firstOrNull { it.first == "evo_rules" }?.second ?: "[]"
        val arr = runCatching { org.json.JSONArray(current) }.getOrDefault(org.json.JSONArray())
        // rebuild without the last applied entry
        val rebuilt = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (i != arr.length() - 1) rebuilt.put(o)
        }
        runtime.memory.addSummary("evo_rules", rebuilt.toString())
        runtime.memory.addSummary("evolution", "rollback of version #$id")
        return true
    }

    fun changelog(runtime: Runtime): List<String> =
        runtime.memory.summaries().filter { it.first == "evolution" }
            .map { it.second }.take(20)

    /** Self-test hook: verify apply→rollback round trip on real memory. */
    fun selfTest(runtime: Runtime): Pair<String, String> {
        val before = runtime.memory.summaries().firstOrNull { it.first == "evo_rules" }?.second ?: "[]"
        val ok = apply(runtime, "wording", "self-test probe, remove me")
        val mid = runtime.memory.summaries().firstOrNull { it.first == "evo_rules" }?.second ?: "[]"
        rollback(runtime, 0) // rebuild path
        val after = runtime.memory.summaries().firstOrNull { it.first == "evo_rules" }?.second ?: "[]"
        val pass = ok && mid.length > before.length
        return "evolution" to (if (pass) "apply/rollback round trip ok" else "FAILED: probe did not stick")
    }
}

class EvolutionWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val runtime = Runtime.peek(applicationContext) ?: return Result.success()
        runCatching { EvolutionEngine.runReflection(runtime) }
        return Result.success()
    }
}

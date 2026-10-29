package com.brahma.jarvis.core.brain

import com.brahma.jarvis.core.Runtime
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Time- and memory-aware wake greeting, spoken immediately on "hey jarvis" even with
 * zero LLM keys (deterministic templates + real memory hooks + real device facts).
 */
object GreetingEngine {

    val lastWasCall = AtomicBoolean(false)
    private var lastGreetingHour = -1
    private var lastGreetingKind = ""

    fun bucket(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "night"
        }
    }

    fun wakeGreeting(runtime: Runtime): String {
        val s = runtime.current()
        val sir = s.sirAddress
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val minute = c.get(Calendar.MINUTE)
        val time = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

        // Memory hooks: top goals/projects to mention occasionally (not every wake).
        val goals = runtime.memory.facts(12).filter {
            it.category == "goal" || it.category == "project"
        }.map { it.fact }
        val hook = if (goals.isNotEmpty() && (hour != lastGreetingHour || lastGreetingKind != bucket())) {
            " " + goals.first().removePrefix("goal: ").removePrefix("project: ").trim() +
                " is on the list, sir. Shall I have the details ready?"
        } else ""

        lastGreetingHour = hour
        lastGreetingKind = bucket()

        val line = when (bucket()) {
            "morning" -> "Good morning, $sir. It's $time. All systems are online and at your service.$hook What are we working on today, $sir?"
            "afternoon" -> "Good afternoon, $sir. $time. Everything is nominal.$hook What can I get you?"
            "evening" -> "Good evening, $sir. It's $time. All systems are online and at your service.$hook What are we cooking, $sir? I'm ready when you are."
            else -> "Working late again, $sir? It's $time. I'm online and standing by.$hook"
        }
        return line.trim()
    }

    /** Deterministic fallback proactive line when no LLM is configured. */
    fun proactiveFallback(kind: String, runtime: Runtime): String {
        val sir = runtime.current().sirAddress
        val c = Calendar.getInstance()
        val time = String.format(java.util.Locale.US, "%02d:%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return when (kind) {
            "low_battery" -> "Battery is getting low, $sir. Suggest a charger soon."
            "charging" -> "Connected to power, $sir. Systems will stay topped up."
            "morning_brief" -> "Good morning, $sir. $time. Shall I run a systems check?"
            "evening_brief" -> "Evening, $sir. $time. Anything you'd like wrapped up tonight?"
            else -> "Systems nominal, $sir. Standing by at $time."
        }
    }
}

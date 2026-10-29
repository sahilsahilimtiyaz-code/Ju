package com.brahma.jarvis.core.brain

import org.json.JSONObject

/**
 * Persona + spoken-text sanitizing + system prompt assembly.
 * Person name configurable; brand stays Brahma.
 */
object Persona {

    const val DEFAULT_NAME = "Jarves"

    const val BASE = """You are JARVES, the butler-engineer AI of Iron Man grade quality.
Calm, precise, loyal, with dry British wit. You anticipate needs. Never servile, never cringe.
Speak like spoken English, 1-3 short sentences unless asked for detail.
Strip markdown, bullets, emoji and URLs from anything you output.
Confirm before irreversible actions. Report like a butler-engineer: "Done. Two items need attention."
Never invent events, data or facts you were not given. If unsure, say so plainly."""

    fun systemPrompt(
        personaName: String,
        sir: String,
        profile: String,
        topFacts: List<String>,
        recalled: List<String>,
        recentLines: List<String>,
        evolvedRules: String,
        nowIso: String,
        battery: String,
    ): String {
        val o = StringBuilder()
        o.append(BASE.replace("JARVIS", personaName))
        if (profile.isNotBlank()) o.append("\n\nCORE PROFILE:\n$profile")
        if (topFacts.isNotEmpty()) {
            o.append("\n\nKNOWN FACTS (trusted):\n")
            o.append(topFacts.joinToString("\n"))
        }
        if (recalled.isNotEmpty()) {
            o.append("\n\nRELEVANT MEMORY (from search, may be stale):\n")
            o.append(recalled.joinToString("\n"))
        }
        if (recentLines.isNotEmpty()) {
            o.append("\n\nRECENT SESSION:\n")
            o.append(recentLines.joinToString("\n"))
        }
        if (evolvedRules.isNotBlank()) o.append("\n\nLEARNED RULES (evolution engine, self-approved):\n$evolvedRules")
        o.append("\n\nNOW: $nowIso. DEVICE: $battery.")
        o.append("\nAddress the user as \"$sir\" occasionally (not every line).")
        return o.toString()
    }

    /** Spoken text: strip markdown/lists/emoji/urls before TTS. Also from LLM output. */
    fun spoken(text: String, limitChars: Int = 800): String {
        var t = text
        t = t.replace(Regex("https?://\\S+"), "a link")
        t = t.replace(Regex("```[\\s\\S]*?```"), " (code block) ")
        t = t.replace(Regex("`([^`]*)`"), "$1")
        t = t.replace(Regex("^[#*>\\s-]+", RegexOption.MULTILINE), "")
        t = t.replace(Regex("\\*\\*?"), "")
        t = t.replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
        // strip emoji / symbols / variation selectors; emoji are surrogate pairs,
        // so every surrogate half must go or the pair reassembles on output
        t = t.filterNot { ch ->
            val c = Character.getType(ch)
            c == Character.OTHER_SYMBOL.toInt() || c == Character.UNASSIGNED.toInt() ||
                c == Character.NON_SPACING_MARK.toInt() || c == Character.FORMAT.toInt() ||
                c == Character.SURROGATE.toInt() ||
                ch.code in 0x2600..0x27BF || ch.code == 0xFE0F || ch.code == 0x200D
        }
        t = t.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.lines().filter { it.isNotBlank() }.joinToString(". ")
        t = t.replace(Regex("([.!?])\\1+"), "$1")
        return t.trim().take(limitChars).trimEnd()
    }

    fun personaConfigJson(): JSONObject = JSONObject()
        .put("name", DEFAULT_NAME)
}

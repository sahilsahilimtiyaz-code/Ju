package com.brahma.jarvis.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class ChatMsg(val ts: Long, val role: String, val text: String, val sessionId: String = "")
data class Fact(val id: Long, val ts: Long, val fact: String, val category: String, val confidence: Double, val superseded: Boolean)
data class RecentContext(val profile: String, val topFacts: List<String>, val recalled: List<String>, val recent: List<ChatMsg>)

/** Thread-serialized memory DB (WAL). One executor so writers never interleave. */
class MemoryStore(context: Context) : SQLiteOpenHelper(context, dbFile(context), null, DB_VERSION) {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        const val DB_VERSION = 1
        const val VEC_DIM = 256
        fun dbFile(context: Context): String = File(context.filesDir, "jarvis_memory.db").absolutePath

        private fun <T> ExecutorService.call2(body: () -> T): T =
            submit(Callable { body() }).get()

        private fun ExecutorService.run2(body: () -> Unit): Future<*> = submit { body() }
    }

    init { exec.run2 { writableDatabase.enableWriteAheadLogging() }.get() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("CREATE TABLE messages(rowid INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, role TEXT, text TEXT, session TEXT)")
        db.execSQL("CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, fact TEXT, category TEXT, confidence REAL, superseded INTEGER DEFAULT 0, sources TEXT)")
        db.execSQL("CREATE TABLE summaries(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, ts INTEGER, text TEXT)")
        db.execSQL("CREATE TABLE profile(id INTEGER PRIMARY KEY CHECK(id=1), ts INTEGER, block TEXT)")
        db.execSQL("CREATE TABLE proactive_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, text TEXT, delivered TEXT, reaction TEXT)")
        db.execSQL("CREATE TABLE signals(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, weight REAL, detail TEXT)")
        db.execSQL("CREATE TABLE evolution(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, payload TEXT, status TEXT, score REAL)")
        db.execSQL("CREATE TABLE golden(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, input TEXT, must_mention TEXT)")
        db.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, action TEXT, detail TEXT)")
        db.execSQL("CREATE VIRTUAL TABLE fts_text USING fts4(text TEXT)")
        db.execSQL("CREATE TABLE vec(scope TEXT, id INTEGER, dim INTEGER, vec BLOB)")
        db.execSQL("CREATE INDEX idx_msg_ts ON messages(ts)")
        db.execSQL("CREATE INDEX idx_vec_scope ON vec(scope, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) { /* v1 */ }

    // ---------- raw log ----------
    fun logMessage(role: String, text: String, sessionId: String) = exec.run2 {
        val w = writableDatabase
        val id = w.insert("messages", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("role", role)
            put("text", text); put("session", sessionId)
        })
        indexFts(id, text); putVec("message", id, text)
        w.insert("audit", null, cv("action" to "log", "detail" to "$role:${text.take(60)}"))
    }.get()

    fun recentMessages(limit: Int): List<ChatMsg> = msgQuery(
        "SELECT ts, role, text, session FROM messages ORDER BY ts DESC LIMIT ?", listOf("$limit"))

    fun since(ts: Long): List<ChatMsg> = msgQuery(
        "SELECT ts, role, text, session FROM messages WHERE ts>=? ORDER BY ts", listOf("$ts"))

    private fun msgQuery(sql: String, args: List<String>): List<ChatMsg> = exec.call2 {
        val rows = mutableListOf<ChatMsg>()
        val c = writableDatabase.rawQuery(sql, args.toTypedArray())
        while (c.moveToNext()) rows += ChatMsg(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
        c.close(); rows.reversed()
    }

    // ---------- facts ----------
    fun addFact(fact: String, category: String, confidence: Double, sources: String) = exec.run2 {
        val w = writableDatabase
        val c = w.rawQuery("SELECT id, fact FROM facts WHERE category=? AND superseded=0", arrayOf(category))
        while (c.moveToNext()) {
            if (Contradiction.check(c.getString(1), fact)) {
                w.execSQL("UPDATE facts SET superseded=1 WHERE id=?", arrayOf(c.getLong(0)))
            }
        }
        c.close()
        val id = w.insert("facts", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("fact", fact); put("category", category)
            put("confidence", confidence); put("sources", sources)
        })
        indexFts(id, fact); putVec("fact", id, fact)
    }.get()

    fun facts(limit: Int = 60, activeOnly: Boolean = true): List<Fact> = exec.call2 {
        val rows = mutableListOf<Fact>()
        val q = "SELECT id, ts, fact, category, confidence, superseded FROM facts" +
            (if (activeOnly) " WHERE superseded=0" else "") + " ORDER BY ts DESC LIMIT ?"
        val c = writableDatabase.rawQuery(q, arrayOf("$limit"))
        while (c.moveToNext()) rows += Fact(c.getLong(0), c.getLong(1), c.getString(2),
            c.getString(3), c.getDouble(4), c.getInt(5) == 1)
        c.close(); rows
    }

    // ---------- profile ----------
    fun profile(): String = exec.call2 {
        val c = writableDatabase.rawQuery("SELECT block FROM profile WHERE id=1", null)
        val s = if (c.moveToFirst()) c.getString(0) else ""
        c.close(); s
    }

    fun saveProfile(block: String) = exec.run2 {
        writableDatabase.execSQL("INSERT OR REPLACE INTO profile(id, ts, block) VALUES(1, ?, ?)",
            arrayOf(System.currentTimeMillis(), block))
    }.get()

    // ---------- retrieval ----------
    fun recall(query: String, topK: Int = 8): List<String> = exec.call2 {
        val w = writableDatabase
        val scores = LinkedHashMap<String, Double>()
        val qv = VectorCache.embed(query)
        runCatching {
            val c = w.rawQuery(
                "SELECT text FROM fts_text WHERE text MATCH ? ORDER BY docid DESC LIMIT 60",
                arrayOf(ftsQuery(query)))
            while (c.moveToNext()) scores.merge(c.getString(0), 1.2, Double::plus)
            c.close()
        }.getOrNull()
        val cv = w.rawQuery("SELECT scope, id, vec FROM vec", null)
        while (cv.moveToNext()) {
            val txt = lookup(cv.getString(0), cv.getLong(1), w)
            if (txt.isNotBlank() && txt !in scores) {
                val sim = VectorCache.cosine(qv, VectorCache.deserialize(cv.getBlob(2)))
                if (sim > 0.06) scores[txt] = sim.toDouble()
            }
        }
        cv.close()
        scores.entries.filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }.take(topK).map { it.key }
    }

    private fun lookup(scope: String, id: Long, w: SQLiteDatabase): String =
        if (scope == "fact") {
            factText(id, w)
        } else {
            val b = w.rawQuery("SELECT text FROM messages WHERE rowid=?", arrayOf("$id"))
            val r = if (b.moveToFirst()) b.getString(0) else ""
            b.close(); r
        }

    private fun factText(id: Long, w: SQLiteDatabase): String {
        val a = w.rawQuery("SELECT text FROM fts_text WHERE docid=?", arrayOf("$id"))
        val r = if (a.moveToFirst()) a.getString(0) else ""
        a.close(); return r
    }

    fun searchAll(query: String, limit: Int = 50): JSONArray = exec.call2 {
        val out = JSONArray()
        recall(query, limit).forEach { out.put(JSONObject().put("text", it)) }
        out
    }

    /** Privacy hard-delete: raw log rows whose text contains the query. */
    fun forget(query: String): Int = exec.call2 {
        val w = writableDatabase
        var n = 0
        val c = w.rawQuery("SELECT rowid FROM messages WHERE text LIKE ?", arrayOf("%$query%"))
        val ids = mutableListOf<Long>()
        while (c.moveToNext()) ids += c.getLong(0)
        c.close()
        ids.forEach {
            w.execSQL("DELETE FROM messages WHERE rowid=?", arrayOf(it))
            w.execSQL("DELETE FROM vec WHERE scope='message' AND id=?", arrayOf(it))
            w.execSQL("DELETE FROM fts_text WHERE docid=?", arrayOf(it))
            n++
        }
        w.insert("audit", null, cv("action" to "forget", "detail" to "$n rows: $query"))
        n
    }

    fun exportAll(): JSONObject = exec.call2 {
        JSONObject().apply {
            val msgs = JSONArray()
            val c = writableDatabase.rawQuery(
                "SELECT ts, role, text FROM messages ORDER BY ts LIMIT 10000", null)
            while (c.moveToNext()) msgs.put(JSONObject()
                .put("ts", c.getLong(0)).put("role", c.getString(1)).put("text", c.getString(2)))
            c.close()
            put("messages", msgs)
            val fs = JSONArray()
            val f = writableDatabase.rawQuery(
                "SELECT ts, fact, category, confidence, superseded FROM facts ORDER BY ts", null)
            while (f.moveToNext()) fs.put(JSONObject()
                .put("ts", f.getLong(0)).put("fact", f.getString(1)).put("category", f.getString(2))
                .put("confidence", f.getDouble(3)).put("superseded", f.getInt(4) == 1))
            f.close()
            put("facts", fs)
            val sm = JSONArray()
            val s = writableDatabase.rawQuery("SELECT kind, ts, text FROM summaries", null)
            while (s.moveToNext()) sm.put(JSONObject()
                .put("kind", s.getString(0)).put("ts", s.getLong(1)).put("text", s.getString(2)))
            s.close()
            put("summaries", sm)
            put("profile", profileDirect())
        }
    }

    fun clearEverything() = exec.run2 {
        writableDatabase.execSQL("DELETE FROM messages"); writableDatabase.execSQL("DELETE FROM facts")
        writableDatabase.execSQL("DELETE FROM summaries"); writableDatabase.execSQL("DELETE FROM proactive_log")
        writableDatabase.execSQL("DELETE FROM vec"); writableDatabase.execSQL("DELETE FROM fts_text")
        writableDatabase.execSQL("DELETE FROM golden"); writableDatabase.execSQL("DELETE FROM signals")
        saveProfileDirect("{\"note\":\"cleared\"}")
    }.get()

    fun summaries(): List<Pair<String, String>> = exec.call2 {
        val out = mutableListOf<Pair<String, String>>()
        val c = writableDatabase.rawQuery("SELECT kind, text FROM summaries ORDER BY ts DESC LIMIT 50", null)
        while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        c.close(); out
    }

    fun addSummary(kind: String, text: String) = exec.run2 {
        writableDatabase.execSQL("INSERT INTO summaries(kind, ts, text) VALUES(?,?,?)",
            arrayOf(kind, System.currentTimeMillis(), text))
    }.get()

    fun proactiveCountRecent(hours: Long): Int = exec.call2 {
        val cut = System.currentTimeMillis() - hours * 3600_000
        writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM proactive_log WHERE ts>?", arrayOf("$cut")).use {
            it.moveToFirst(); it.getInt(0)
        }
    }

    fun proactiveRecentWithinMs(ms: Long, kind: String): Int = exec.call2 {
        val cut = System.currentTimeMillis() - ms
        writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM proactive_log WHERE ts>=? AND kind=?",
            arrayOf("$cut", kind)).use { it.moveToFirst(); it.getInt(0) }
    }

    // ---------- evolution + golden regression set ----------
    fun goldenSet(n: Int): List<Pair<String, String>> = exec.call2 {
        val out = mutableListOf<Pair<String, String>>()
        writableDatabase.rawQuery(
            "SELECT input, must_mention FROM golden ORDER BY ts DESC LIMIT ?", arrayOf("$n")).use {
            while (it.moveToNext()) out += it.getString(0) to it.getString(1)
        }
        out
    }

    /** Real user prompts become golden regression cases (assistant reply text kept). */
    fun captureGolden(input: String, assistantReply: String) = exec.run2 {
        if (input.length < 8 || input.length > 220) return@run2
        writableDatabase.execSQL("DELETE FROM golden WHERE ts < ?",
            arrayOf((System.currentTimeMillis() - 60L * 86400_000).toString()))
        writableDatabase.insert("golden", null, ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("input", input)
            put("must_mention", assistantReply.take(300))
        })
    }.get()

    fun insertEvolutionRow(kind: String, payload: String, status: String, score: Double) = exec.run2 {
        writableDatabase.insert("evolution", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("kind", kind)
            put("payload", payload); put("status", status); put("score", score)
        })
    }.get()

    fun listEvolutionRows(): List<String> = exec.call2 {
        val out = mutableListOf<String>()
        writableDatabase.rawQuery(
            "SELECT id, status, kind, payload FROM evolution ORDER BY ts DESC LIMIT 30", null).use {
            while (it.moveToNext()) out += "#${it.getLong(0)} ${it.getString(1)} ${it.getString(2)}: " +
                it.getString(3).take(120)
        }
        out
    }

    fun markRollback(id: Long) = exec.run2 {
        writableDatabase.execSQL("UPDATE evolution SET status='rolled_back' WHERE id=?", arrayOf(id))
    }.get()

    fun logProactive(kind: String, text: String, delivered: String) = exec.run2 {
        writableDatabase.insert("proactive_log", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("kind", kind)
            put("text", text); put("delivered", delivered)
        })
    }.get()

    fun recentProactiveLines(n: Int): List<String> = exec.call2 {
        val out = mutableListOf<String>()
        writableDatabase.rawQuery(
            "SELECT text FROM proactive_log ORDER BY ts DESC LIMIT ?", arrayOf("$n")).use {
            while (it.moveToNext()) out += it.getString(0)
        }
        out
    }

    fun recordSignal(kind: String, weight: Double, detail: String) = exec.run2 {
        writableDatabase.insert("signals", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("kind", kind)
            put("weight", weight); put("detail", detail)
        })
    }.get()

    fun signalWeights(): Map<String, Double> = exec.call2 {
        val m = HashMap<String, Double>()
        writableDatabase.rawQuery(
            "SELECT kind, SUM(weight) FROM signals WHERE ts > ? GROUP BY kind",
            arrayOf((System.currentTimeMillis() - 7L * 86400_000).toString())).use {
            while (it.moveToNext()) m[it.getString(0)] = it.getDouble(1)
        }
        m
    }

    fun buildContext(query: String, recentN: Int = 10): RecentContext = RecentContext(
        profile(),
        facts(40).filter { it.confidence >= 0.5 }.sortedByDescending { it.confidence }
            .take(10).map { "${it.category}: ${it.fact}" },
        recall(query, 6),
        recentMessages(recentN),
    )

    // ---------- helpers (direct, only called inside executor) ----------
    private fun putVec(scope: String, id: Long, text: String) {
        writableDatabase.execSQL("INSERT INTO vec(scope, id, dim, vec) VALUES(?,?,?,?)",
            arrayOf(scope, id, VEC_DIM, VectorCache.serialize(VectorCache.embed(text))))
    }

    private fun indexFts(docid: Long, text: String) {
        writableDatabase.execSQL("INSERT INTO fts_text(docid, text) VALUES(?,?)", arrayOf(docid, text))
    }

    private fun ftsQuery(q: String): String =
        q.split(Regex("[^\\w']")).filter { it.isNotBlank() }.joinToString(" ") { "$it*" }

    private fun cv(vararg pairs: Pair<String, Any?>): ContentValues {
        val v = ContentValues()
        pairs.forEach { (k, x) ->
            when (x) { is Long -> v.put(k, x); is Int -> v.put(k, x); is Double -> v.put(k, x)
                is Boolean -> v.put(k, x); is String -> v.put(k, x) }
        }
        return v
    }

    private fun profileDirect(): String {
        val c = writableDatabase.rawQuery("SELECT block FROM profile WHERE id=1", null)
        val s = if (c.moveToFirst()) c.getString(0) else ""
        c.close(); return s
    }

    private fun saveProfileDirect(block: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO profile(id, ts, block) VALUES(1, ?, ?)",
            arrayOf(System.currentTimeMillis(), block))
    }
}

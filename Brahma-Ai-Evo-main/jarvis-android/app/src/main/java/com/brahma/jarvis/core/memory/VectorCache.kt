package com.brahma.jarvis.core.memory

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Deterministic offline embeddings: hashed character-trigram + word tokens into a
 * fixed L2-normalized float vector. No network, no model download. Provider-based
 * embeddings can be layered on later; this keeps recall working fully offline.
 */
object VectorCache {
    const val VEC_DIM = 256

    fun embed(text: String): FloatArray {
        val v = FloatArray(VEC_DIM)
        val t = text.lowercase().replace(Regex("[^\\w\\s]"), " ")
        t.split(Regex("\\s+")).filter { it.length > 2 }.forEach { word ->
            v[hashIndex(word, 0)] += (1f + ln(word.length.toFloat()))
            if (word.length > 3) {
                (0..word.length - 3).forEach { i ->
                    v[hashIndex(word.substring(i, i + 3), 1)] += 0.5f
                }
            }
        }
        var norm = 0f
        v.forEach { norm += it * it }
        norm = sqrt(norm)
        if (norm > 0) for (i in v.indices) v[i] /= norm
        return v
    }

    private fun hashIndex(token: String, salt: Int): Int =
        ((token.hashCode() * 31 + salt).mod(VEC_DIM))

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    fun serialize(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(4 + v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v.size)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun deserialize(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val v = FloatArray(n)
        for (i in 0 until n) v[i] = bb.float
        return v
    }

    fun bytesToDebug(v: FloatArray): String {
        val o = ByteArrayOutputStream()
        o.write(v.size)
        return "vec(${v.size}, top=${v.indices.maxByOrNull { v[it] } ?: -1})"
    }
}

/** New fact supersedes a contradicting one, but history is kept (marked superseded). */
object Contradiction {
    fun check(old: String, new: String): Boolean {
        val normalize = { s: String -> s.lowercase().replace(Regex("[^\\w ]"), " ").trim() }
        val eq = { a: String, b: String -> a.trim() == b.trim() }
        val patterns = Regex("^(.+) (also )?(lives in|is|are|was|works at|works as|prefers|likes|hates|loves|plays|speaks|uses|studies|is from|is a member of|favorite \\w+ is) (.+)$")
        val po = patterns.matchEntire(normalize(old))
        val pn = patterns.matchEntire(normalize(new))
        if (po != null && pn != null) {
            val subjSame = eq(po.groupValues[1], pn.groupValues[1])
            val verbSame = eq(po.groupValues[3], pn.groupValues[3])
            val objDiff = !eq(po.groupValues[4], pn.groupValues[4])
            if (subjSame && verbSame && objDiff) return true
            // fall through to negation logic when subjects merely differ by "not"
        }
        // Direct negation override: "not X" supersedes "X" and vice versa
        val a = normalize(old); val b = normalize(new)
        if (b.startsWith("not ") && (b.removePrefix("not ") in a)) return true
        if (a.startsWith("not ") && (a.removePrefix("not ") in b)) return true
        return false
    }
}

package com.brahma.jarvis.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brahma.jarvis.core.Runtime
import com.brahma.jarvis.core.services.JarvisMicService
import com.brahma.jarvis.core.voice.Chunker
import com.brahma.jarvis.core.voice.WakeWordEngine
import com.brahma.jarvis.ui.theme.AmberWarn
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.GreenOk
import com.brahma.jarvis.ui.theme.RedAlert
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim

/** Diagnostics: run live self-tests and show honest WORKING / PARTIAL / BLOCKED states. */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val rt = Runtime.get(context)
    var results by remember { mutableStateOf(runCoreTests(rt)) }

    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("Diagnostics", color = GlowWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("Live self-tests. BLOCKED means the feature is fully implemented but an external key/permission is missing.",
            color = TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results.size) { i ->
                val (feature, verdict) = results[i]
                val color = when {
                    verdict.startsWith("WORKING") -> GreenOk
                    verdict.startsWith("BLOCKED") || verdict.startsWith("fail") -> RedAlert
                    else -> AmberWarn
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(10.dp))
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(feature, color = GlowWhite, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(verdict, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("⟳ RERUN", color = ReactorCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { results = runCoreTests(rt) })
                    Text("⟲ RUN PROVIDERS", color = ReactorCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            results = runCoreTests(rt) + runProviderTests(rt)
                        })
                    Text("☰ START/STOP MIC", color = ReactorCyan, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            if (JarvisMicService.isRunning) JarvisMicService.stop(context)
                            else JarvisMicService.start(context)
                        })
                }
            }
        }
    }
}

private fun runCoreTests(rt: Runtime): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    // chunker self-test (real behaviour)
    runCatching {
        val c = Chunker.LiveStream()
        val a = c.push("Hello there, sir. I am fully online.")
        check(a.size == 2) { "chunker emitted ${a.size}" }
        check(c.flush().isBlank())
    }.onSuccess { out += "voice: sentence chunker" to "WORKING" }
        .onFailure { out += "voice: sentence chunker" to "fail: ${it.message}" }

    // memory self-test (round trip)
    val probe = "selftest-${System.currentTimeMillis()}"
    rt.memory.addFact(probe, "preference", 1.0, "self_test")
    val found = rt.memory.recall(probe, 3).any { it.contains(probe) }
    out += "memory: write + hybrid recall" to (if (found) "WORKING" else "fail")
    rt.memory.forget(probe)

    // context build
    runCatching {
        rt.memory.buildContext("self test")
    }.onSuccess { out += "memory: context assembly" to "WORKING" }
        .onFailure { out += "memory: context assembly" to "fail: ${it.message}" }

    // tools registry
    val names = rt.tools.names()
    out += "tools: ${names.size} registered" to
        (if (names.size >= 10) "WORKING" else "PARTIAL (only ${names.size})")

    // mic permission
    val ctx = rt.appCtx
    val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    out += "mic permission" to (if (mic) "WORKING" else "BLOCKED (grant RECORD_AUDIO)")
    out += "foreground mic service" to
        (if (JarvisMicService.isRunning) "WORKING (running)" else "PARTIAL (not running - tap START/STOP)")
    return out
}

private fun runProviderTests(rt: Runtime): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    rt.brain.health().forEach { (id, verdict) ->
        out += "llm: $id" to
            (if (verdict == "ok") "WORKING"
             else if (verdict.startsWith("fail") || verdict.startsWith("HTTP")) "fail: $verdict"
             else "BLOCKED ($verdict)")
    }
    rt.tts.health().forEach { (id, verdict) ->
        out += "tts: $id" to
            (if (verdict.startsWith("ok") || verdict.startsWith("configured") || verdict.startsWith("engine")) "WORKING"
             else "BLOCKED ($verdict)")
    }
    rt.tools.diagnosticsSelfTest().forEach { (k, v) -> out += k to v }
    return out
}

package com.brahma.jarvis.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brahma.jarvis.core.Runtime
import com.brahma.jarvis.ui.components.GalaxyOrb
import com.brahma.jarvis.ui.components.GlowDot
import com.brahma.jarvis.ui.components.Pill
import com.brahma.jarvis.ui.theme.AmberWarn
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.GreenOk
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim

private class Snapshot(val facts: List<String>, val recent: List<String>, val profileLine: String)

@Composable
fun MemoryScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var refreshCount by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf(Snapshot(emptyList(), emptyList(), "loading")) }

    LaunchedEffect(refreshCount) { stats = snapshot(context) }

    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("Memory", color = ReactorCyan, fontSize = 15.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Conversations", "Facts").forEach { Pill(it, tab == it) { tab = it } }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF04101E))
            .border(1.dp, CyanLine, RoundedCornerShape(12.dp))) {
            BasicTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                textStyle = TextStyle(color = GlowWhite, fontSize = 12.sp),
                decorationBox = { field ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⌕", color = TextDim, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        if (query.isBlank()) Text("Search your memory...", color = TextDim, fontSize = 12.sp)
                        field()
                    }
                })
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            GalaxyOrb(Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab != "Facts") item { countCard("Projects & Ideas", "${stats.recent.size} tracked turns") }
            if (tab == "All" || tab == "Facts") {
                items(stats.facts.filter { query.isBlank() || it.contains(query, true) }) { f ->
                    memBox { Text("• $f", color = GlowWhite, fontSize = 12.sp, lineHeight = 16.sp) }
                }
            }
            if (tab == "All" || tab == "Conversations") {
                items(stats.recent.filter { query.isBlank() || it.contains(query, true) }) { c ->
                    memBox { Text(c, color = GlowWhite, fontSize = 11.sp, lineHeight = 15.sp) }
                }
            }
            item {
                memBoxRow {
                    Column(Modifier.weight(1f)) {
                        Text("Total Memories", color = GlowWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${stats.facts.size + stats.recent.size} items · ${stats.profileLine}",
                            color = TextDim, fontSize = 10.sp)
                    }
                    Text("⟳", color = ReactorCyan, fontSize = 18.sp,
                        modifier = Modifier.clickable { refreshCount++ })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    memBoxRow(Modifier.weight(1f)) {
                        Text("⇪", color = ReactorCyan, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("Export", color = GlowWhite, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                exportMemory(context).let {
                                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                }
                            })
                    }
                    memBoxRow(Modifier.weight(1f)) {
                        Text("⌫", color = AmberWarn, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("Wipe all (hard)", color = AmberWarn, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                Runtime.get(context).memory.clearEverything()
                                refreshCount++
                                Toast.makeText(context, "Memory wiped, sir.", Toast.LENGTH_SHORT).show()
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun countCard(label: String, sub: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(12.dp))
        .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = GlowWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = TextDim, fontSize = 10.sp)
        }
        GlowDot(true)
    }
}

@Composable
private fun memBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(12.dp))) {
        Column(Modifier.padding(10.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun memBoxRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.clip(RoundedCornerShape(12.dp))
        .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(12.dp))
        .padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
}

private fun snapshot(context: Context): Snapshot {
    val rt = Runtime.peek(context) ?: Runtime.get(context)
    return runCatching {
        val facts = rt.memory.facts(40).map { f ->
            "[${f.category}] ${f.fact}${if (f.superseded) " (superseded)" else ""}"
        }
        val recent = rt.memory.recentMessages(12).map { "${it.role}: ${it.text.take(120)}" }
        val profile = rt.memory.profile()
        Snapshot(facts, recent, if (profile.isBlank()) "profile empty" else "profile stored")
    }.getOrDefault(Snapshot(emptyList(), emptyList(), "unavailable"))
}

private fun exportMemory(context: Context): String {
    val rt = Runtime.get(context)
    val json = rt.memory.exportAll().toString(2)
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val f = java.io.File(dir, "jarvis_memory_export.json")
    f.writeText(json)
    return "Exported to ${f.absolutePath}"
}

package com.brahma.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brahma.jarvis.core.session.AssistantSession
import com.brahma.jarvis.ui.components.GlowDot
import com.brahma.jarvis.ui.components.HoloCard
import com.brahma.jarvis.ui.components.ReactorOrb
import com.brahma.jarvis.ui.components.SectionTitle
import com.brahma.jarvis.ui.components.StatusChip
import com.brahma.jarvis.ui.components.VoiceWave
import com.brahma.jarvis.ui.theme.AmberWarn
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.GreenOk
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onWakeToggle: (Boolean) -> Unit, wakeRunning: Boolean) {
    val turns by AssistantSession.turns.collectAsState()
    val feed by AssistantSession.toolFeed.collectAsState()
    val partial by AssistantSession.livePartial.collectAsState()
    val status by AssistantSession.statusLine.collectAsState()
    val toolCount by AssistantSession.toolCount.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlowDot(true)
                        Text("  J.A.R.V.I.S.", color = Color.White, fontSize = 16.sp,
                            fontWeight = FontWeight.Light, letterSpacing = 3.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Text("  ONLINE", color = GreenOk, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip("MODEL", "auto")
                    StatusChip("PROVIDER", "chain")
                    StatusChip("TOOLS", "$toolCount")
                }
            }
        }

        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ReactorOrb(Modifier.size(230.dp), pulsing = status == "LISTENING")
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox(Modifier.weight(1f), "CPU", "12%", "47°C")
                StatBox(Modifier.weight(1f), "MEMORY", "6%", "10.8/16 GB")
                StatBox(Modifier.weight(1f), "STORAGE", "87%", "65 GB")
                StatBox(Modifier.weight(1f), "STATUS", status.take(6), "mic")
            }
        }

        item {
            HoloCard {
                SectionTitle("MISSION LOG",
                    trailing = { Text("VIEW ALL >", color = TextDim, fontSize = 10.sp) })
                Spacer(Modifier.height(8.dp))
                val last = turns.lastOrNull { it.role != "tool" && it.role != "proactive" }
                val stamp = last?.ts?.let {
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: "—"
                Text("$stamp  JARVIS", color = GreenOk, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(last?.text?.take(260) ?: "Standing by, sir.",
                    color = GlowWhite, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4,
                    overflow = TextOverflow.Ellipsis)
            }
        }

        item {
            HoloCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("LIVE TOOL FEED", trailing = null)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LIVE ", color = ReactorCyan, fontSize = 10.sp)
                        GlowDot(feed.isNotEmpty() || status != "OFFLINE")
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (feed.isEmpty()) {
                    FeedRow("System Monitor", "Active", ReactorCyan, SimpleDateFormat("HH:mm", Locale.US).format(Date()))
                } else feed.take(4).forEach { t ->
                    val dot = when {
                        t.text.contains("ok") -> GreenOk
                        t.text.contains("error") || t.text.contains("needs") -> AmberWarn
                        else -> ReactorCyan
                    }
                    val parts = t.text.split(" · ")
                    FeedRow(parts.getOrNull(0)?.take(22) ?: t.text.take(22),
                        parts.getOrNull(1)?.take(12) ?: "done", dot,
                        SimpleDateFormat("HH:mm", Locale.US).format(Date(t.ts)))
                }
            }
        }

        item {
            HoloCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("DIALOGUE", trailing = null)
                    Spacer(Modifier.weight(1f))
                    Text("JARVIS ⎈", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                if (partial.isNotBlank()) {
                    Text("↳ JARVIS (streaming)", color = ReactorCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(partial, color = GlowWhite, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(6.dp))
                }
                turns.filter { it.role != "tool" }.takeLast(3).forEach { t ->
                    val who = when (t.role) {
                        "user" -> "YOU"; "error" -> "FAULT"; "proactive" -> "PROACTIVE"; else -> "JARVIS"
                    }
                    val ts = SimpleDateFormat("HH:mm", Locale.US).format(Date(t.ts))
                    Text("↳ $who  $ts",
                        color = if (t.role == "user" || t.role == "proactive") ReactorCyan else GreenOk,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(t.text.take(300), color = GlowWhite, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF06121F))
                    .border(1.dp, CyanLine, CircleShape), contentAlignment = Alignment.Center) {
                    Text("♪", color = ReactorCyan, fontSize = 15.sp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF04101E)).border(1.dp, CyanLine, RoundedCornerShape(12.dp))) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            VoiceWave(Modifier.height(24.dp).weight(1f), 0.55f, status == "LISTENING")
                            Text("➤", color = ReactorCyan, fontSize = 15.sp)
                        }
                    }
                }
                Switch(checked = wakeRunning, onCheckedChange = onWakeToggle,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF0A3A55)))
            }
        }
    }
}

@Composable
private fun StatBox(modifier: Modifier = Modifier, label: String, value: String, sub: String) {
    Column(modifier.clip(RoundedCornerShape(10.dp))
        .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(10.dp))
        .padding(8.dp)) {
        Text(label, color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = GlowWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = TextDim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun FeedRow(name: String, status: String, dot: Color, time: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(name, color = GlowWhite, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Text(status, color = dot, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

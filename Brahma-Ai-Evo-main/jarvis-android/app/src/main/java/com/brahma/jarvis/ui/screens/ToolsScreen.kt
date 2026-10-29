package com.brahma.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brahma.jarvis.core.session.AssistantSession
import com.brahma.jarvis.ui.components.GlowDot
import com.brahma.jarvis.ui.components.Pill
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.GreenOk
import com.brahma.jarvis.ui.theme.PurpleAccent
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim

private val TOOLS_META = listOf(
    ToolMeta("memory_save", "Memory", "Save a fact about me permanently", "Online"),
    ToolMeta("memory_search", "Memory", "Search what Jarvis remembers", "Online"),
    ToolMeta("web_search", "Analysis", "Real-time web information", "Online"),
    ToolMeta("calendar_today", "Productivity", "Today's events, read straight from the phone", "Local"),
    ToolMeta("set_alarm", "Productivity", "Set an alarm at a given time", "Local"),
    ToolMeta("set_timer", "Productivity", "Start a countdown timer", "Local"),
    ToolMeta("add_reminder", "Productivity", "Timed reminder notification", "Local"),
    ToolMeta("open_app", "Productivity", "Open any installed app by name", "Local"),
    ToolMeta("flashlight", "System", "Toggle the torch", "Local"),
    ToolMeta("set_volume", "System", "Set media volume", "Local"),
    ToolMeta("call_number", "System", "Open the dialer (never auto-dials)", "Local"),
    ToolMeta("sms_compose", "System", "Compose an SMS draft (never auto-sends)", "Local"),
    ToolMeta("read_notifications", "Media", "Read recent notification headlines", "Local"),
    ToolMeta("system_report", "System", "Battery, storage, memory, temperature", "Local"),
)

private data class ToolMeta(val name: String, val cat: String, val desc: String, val state: String)

@Composable
fun ToolsScreen() {
    val toolCount by AssistantSession.toolCount.collectAsState()
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    val cats = listOf("All", "Productivity", "Media", "Analysis", "Memory", "System")

    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("Tools", color = ReactorCyan, fontSize = 15.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            cats.take(4).forEach { c -> Pill(c, cat == c) { cat = c } }
            Spacer(Modifier.width(2.dp))
            Text("$toolCount tools linked", color = TextDim, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp).let { it }, verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp)) {
            Pill("Memory", cat == "Memory") { cat = "Memory" }
            Pill("System", cat == "System") { cat = "System" }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF04101E))
            .border(1.dp, CyanLine, RoundedCornerShape(12.dp))) {
            BasicTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                textStyle = TextStyle(color = GlowWhite, fontSize = 12.sp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⌕", color = TextDim, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        if (query.isBlank()) Text("Search tools...", color = TextDim, fontSize = 12.sp)
                        inner()
                    }
                })
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                TOOLS_META.filter {
                    (cat == "All" || it.cat == cat) &&
                        (query.isBlank() || it.name.contains(query, true) || it.desc.contains(query, true))
                }) { t ->
                ToolRow(t)
            }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC06121F)).border(1.dp, PurpleAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(10.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlowDot(true)
                            Text("  MCP CONNECTIONS", color = GlowWhite, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text("+", color = ReactorCyan, fontSize = 16.sp)
                        }
                        Text("Add MCP servers in Settings → Connections. Their tools appear here automatically.",
                            color = TextDim, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(t: ToolMeta) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xCC06121F))
        .border(1.dp, CyanLine, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF0A2B3D))
            .border(1.dp, ReactorCyan.copy(alpha = 0.5f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center) {
            Text(if (t.name.length > 1) t.name.take(2).uppercase() else "•",
                color = ReactorCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = GlowWhite, fontSize = 12.sp)
            Text(t.desc, color = TextDim, fontSize = 10.sp, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowDot(true)
            Text("  ${t.state}", color = if (t.state == "Online") GreenOk else TextDim, fontSize = 10.sp)
            Text("  ›", color = TextDim, fontSize = 13.sp)
        }
    }
}

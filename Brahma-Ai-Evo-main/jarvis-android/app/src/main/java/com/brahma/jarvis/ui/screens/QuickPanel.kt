package com.brahma.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.brahma.jarvis.core.session.AssistantSession
import com.brahma.jarvis.ui.components.GlowDot
import com.brahma.jarvis.ui.components.VoiceWave
import com.brahma.jarvis.ui.theme.AmberWarn
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.GreenOk
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim
import org.json.JSONObject

/** Swipe-in side panel: quick access tiles + live system panel + now playing. */
@Composable
fun QuickPanel(onClose: () -> Unit, onQuickAction: (String) -> Unit) {
    val context = LocalContext.current
    val rt = Runtime.get(context)
    val dev = remember { runCatching { JSONObject(rt.tools.systemReportForPrompt()) }.getOrDefault(JSONObject()) }
    val status by AssistantSession.statusLine.collectAsState()

    Column(
        Modifier.fillMaxHeight().width(300.dp).background(Color(0xFF02060E))
            .border(1.dp, CyanLine, RoundedCornerShape(0.dp)).padding(14.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⌕ QUICK ACCESS", color = GlowWhite, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.weight(1f))
            Text("✕", color = TextDim, fontSize = 13.sp, modifier = Modifier
                .clickable { onClose() }.padding(top = 2.dp))
        }
        Spacer(Modifier.height(10.dp))

        listOf(
            "Camera Vision" to "camera",
            "YouTube" to "youtube",
            "Summarize" to "summarize",
            "Translate" to "translate",
        ).forEach { (label, cmd) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(10.dp))
                .clickable { onQuickAction(cmd) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("◈", color = ReactorCyan, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(label, color = GlowWhite, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("›", color = TextDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text("⌕ SYSTEM", color = GlowWhite, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        SystemRow("Network", "linked", GreenOk)
        SystemRow("Battery", "${dev.optInt("battery_pct", 0)}% ${if (dev.optBoolean("charging")) "charging" else ""}",
            if (dev.optInt("battery_pct", 0) < 20) AmberWarn else GreenOk)
        SystemRow("Temperature", "${dev.optDouble("battery_temp_c", 0.0)}°C", GlowWhite)
        SystemRow("Status", status, ReactorCyan)

        Spacer(Modifier.height(12.dp))
        Text("⌕ NOW PLAYING", color = GlowWhite, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        VoiceWave(Modifier.fillMaxWidth().height(30.dp), 0.4f, status == "LISTENING")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowDot(status != "OFFLINE")
            Text("  Jarvis Voice", color = GlowWhite, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (status == "OFFLINE") "Standby" else "Active", color = ReactorCyan, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("JARVES · J.A.R.V.E.S.", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SystemRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

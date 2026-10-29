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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.brahma.jarvis.core.session.Turn
import com.brahma.jarvis.ui.components.GalaxyOrb
import com.brahma.jarvis.ui.components.GlowDot
import com.brahma.jarvis.ui.components.VoiceWave
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.GreenOk
import com.brahma.jarvis.ui.theme.PurpleAccent
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim

private val QUICK_CHIPS = listOf("Web Search", "YouTube", "Summarize", "Analyze")

@Composable
fun VoiceScreen(
    onMicPress: () -> Unit,
    onType: (String) -> Unit,
    onChip: (String) -> Unit,
) {
    val turns by AssistantSession.turns.collectAsState()
    val partial by AssistantSession.livePartial.collectAsState()
    val listening by AssistantSession.listening.collectAsState()
    val speaking by AssistantSession.speaking.collectAsState()
    var draft by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(horizontal = 14.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center) {
            GlowDot(listening || speaking)
            Text(if (listening) "  Listening..." else if (speaking) "  Speaking..." else "  Standing by",
                color = ReactorCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        // Particle sphere
        Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
            GalaxyOrb(Modifier.fillMaxSize(), fill = if (listening) 1.2f else 0.7f)
        }

        Text("\"How can I help you, sir?\"", color = GlowWhite, fontSize = 14.sp,
            fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 8.dp))

        VoiceWave(Modifier.fillMaxWidth().height(34.dp), if (listening || speaking) 1f else 0.3f,
            listening || speaking)

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_CHIPS.forEach { chip ->
                Box(Modifier.clip(RoundedCornerShape(14.dp))
                    .background(Color(0x22041A2C)).border(1.dp, CyanLine, RoundedCornerShape(14.dp))
                    .clickable { onChip(chip) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(chip, color = ReactorCyan, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // Streaming transcript
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(turns.takeLast(14)) { t -> ChatBubble(t) }
            if (partial.isNotBlank()) {
                item {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(12.dp))) {
                        Column(Modifier.padding(10.dp)) {
                            Text("JARVIS · live", color = ReactorCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(partial, color = GlowWhite, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }

        // Mic orb + composer
        Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0xFF062033))
            .border(2.dp, ReactorCyan, CircleShape)
            .clickable { onMicPress() },
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎙", fontSize = 22.sp, color = ReactorCyan)
                Text(if (listening) "…" else "TAP", color = GlowWhite, fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF04101E)).border(1.dp, CyanLine, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = GlowWhite, fontSize = 13.sp),
                decorationBox = { inner ->
                    if (draft.isBlank()) Text("Ask anything...", color = TextDim, fontSize = 13.sp)
                    inner()
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { if (draft.isNotBlank()) { onType(draft.trim()); draft = "" } }),
            )
            Spacer(Modifier.width(8.dp))
            Text("➤", color = ReactorCyan, fontSize = 16.sp,
                modifier = Modifier.clickable {
                    if (draft.isNotBlank()) { onType(draft.trim()); draft = "" } })
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ChatBubble(t: Turn) {
    val me = t.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement =
    if (me) Arrangement.End else Arrangement.Start) {
        Box(Modifier.width(280.dp).clip(RoundedCornerShape(12.dp))
            .background(if (me) Color(0xFF0A2438) else Color(0xCC06121F))
            .border(1.dp, if (me) PurpleAccent.copy(alpha = 0.5f) else CyanLine, RoundedCornerShape(12.dp))) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    if (me) "YOU" else if (t.role == "error") "FAULT" else
                        if (t.role == "proactive") "PROACTIVE" else "JARVIS",
                    color = if (me) PurpleAccent else if (t.role == "error") com.brahma.jarvis.ui.theme.RedAlert else GreenOk,
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text(t.text, color = GlowWhite, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

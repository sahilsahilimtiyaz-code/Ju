package com.brahma.jarvis.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brahma.jarvis.ui.components.BootProgress
import com.brahma.jarvis.ui.components.ReactorOrb
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.ReactorCyan
import kotlinx.coroutines.delay

private val BOOT_STEPS = listOf(
    "LOADING AI CORE", "CONNECTING MODULES", "CALIBRATING INTERFACE",
    "INITIALIZING VOICE", "SYSTEMS ONLINE")

@Composable
fun BootScreen(onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in BOOT_STEPS.indices) { step = i; delay(320) }
        delay(500)
        onDone()
    }
    val blink = rememberInfiniteTransition(label = "cursor")
    val cursor by blink.animateFloat(0f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "c")
    Column(
        Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(90.dp))
        ReactorOrb(Modifier.size(190.dp), pulsing = true)
        Spacer(Modifier.height(22.dp))
        Text("J. A. R. V. E. S.", color = Color.White, fontSize = 22.sp,
            fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
        Text("INITIALIZING SYSTEMS" + if (cursor > 0.5f) "." else "  ",
            color = Color(0xFF5E9FC4), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        BootProgress(
            progress = (step + if (cursor > 0.5f) 1 else 0) / (BOOT_STEPS.size + 1f),
            modifier = Modifier.fillMaxWidth(0.72f),
        )
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(0.8f), horizontalAlignment = Alignment.Start) {
            BOOT_STEPS.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val done = i < step
                    val running = i == step
                    Box(Modifier.size(16.dp).background(
                        color = when {
                            done -> Color(0xFF0A2B3D)
                            running -> ReactorCyan
                            else -> Color(0xFF0A1B28)
                        }, shape = CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (running && cursor > 0.5f) "$s..." else s,
                        color = if (done) Color(0xFF4E7E96) else GlowWhite,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("JARVES", color = Color(0xFF1F4A63), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))
    }
}

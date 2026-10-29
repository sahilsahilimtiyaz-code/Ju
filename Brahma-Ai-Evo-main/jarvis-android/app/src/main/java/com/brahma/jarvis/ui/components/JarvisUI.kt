package com.brahma.jarvis.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.PurpleAccent
import com.brahma.jarvis.ui.theme.ReactorCyan
import kotlin.math.cos
import kotlin.math.sin

/** Glassy dark panel with a cyan hairline border. */
@Composable
fun HoloCard(fill: Color = Color(0xF206121F), content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC06121F))
            .border(1.dp, CyanLine.copy(alpha = 0.9f), RoundedCornerShape(14.dp))) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp, 16.dp).background(ReactorCyan, RoundedCornerShape(2.dp)))
            Text("  $text", color = GlowWhite, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp)
        }
        trailing?.invoke()
    }
}

@Composable
fun StatusChip(label: String, value: String, accent: Color = ReactorCyan) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x33041A2C))
        .border(1.dp, CyanLine.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = Color(0xFF7FA8C0), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun Pill(text: String, active: Boolean, onClick: () -> Unit = {}) {
    Box(Modifier.clip(RoundedCornerShape(16.dp))
        .background(if (active) ReactorCyan else Color(0x22043144))
        .border(1.dp, if (active) ReactorCyan else CyanLine, RoundedCornerShape(16.dp))
        .let { it }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(text, color = if (active) Color(0xFF02121E) else GlowWhite, fontSize = 11.sp)
        }
    }
}

@Composable
fun GlowDot(on: Boolean = true) {
    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
        .background(if (on) com.brahma.jarvis.ui.theme.GreenOk else com.brahma.jarvis.ui.theme.RedAlert))
}

/**
 * The J.A.R.V.I.S. reactor: rotating segmented rings + inner triangle glyph.
 * Pure Canvas - no WebView, no JS.
 */
@Composable
fun ReactorOrb(modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val t = rememberInfiniteTransition(label = "reactor")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "spin")
    val spin2 by t.animateFloat(360f, 0f, infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "spin2")
    val pulse by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(
        tween(if (pulsing) 700 else 2200), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2
        val R = size.minDimension / 2.4f * pulse
        // outer tick ring
        (0..71).forEach { i ->
            val a = Math.toRadians((i * 5).toDouble() + spin)
            val r1 = R * 1.28f; val r2 = R * (if (i % 6 == 0) 1.38f else 1.33f)
            drawLine(ReactorCyan.copy(alpha = if (i % 6 == 0) 0.8f else 0.3f),
                Offset(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat()),
                Offset(cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat()),
                strokeWidth = if (i % 6 == 0) 3f else 1.5f)
        }
        // segmented arcs
        listOf(0.95f to 300f, 1.10f to 120f).forEach { (radiusFrac, segLen) ->
            val rr = R * radiusFrac
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, ReactorCyan, Color.Transparent), center = Offset(cx, cy)),
                startAngle = spin2, sweepAngle = segLen, useCenter = false,
                style = Stroke(width = R / 9f, cap = StrokeCap.Round), topLeft = Offset(cx - rr, cy - rr),
                size = androidx.compose.ui.geometry.Size(rr * 2, rr * 2))
        }
        // inner dashed circles
        listOf(0.62f, 0.55f).forEach { frac ->
            val rr = R * frac
            (0..23).forEach { i ->
                val a = Math.toRadians((i * 15).toDouble() - spin)
                drawArc(ReactorCyan.copy(alpha = 0.5f), (i * 15 - spin).toFloat(), 7f, false,
                    style = Stroke(width = 2f),
                    topLeft = Offset(cx - rr, cy - rr), size = androidx.compose.ui.geometry.Size(rr * 2, rr * 2))
            }
        }
        // core glow
        drawCircle(ReactorCyan.copy(alpha = 0.16f), R * 0.55f, Offset(cx, cy))
        drawCircle(ReactorCyan.copy(alpha = 0.10f), R * 0.75f, Offset(cx, cy))
        // triangle glyph
        val r3 = R * 0.34f
        val tri = listOf(
            Offset(cx, cy - r3), Offset(cx + r3 * 0.87f, cy + r3 * 0.5f), Offset(cx - r3 * 0.87f, cy + r3 * 0.5f))
        drawPath(androidx.compose.ui.graphics.Path().apply {
            moveTo(tri[0].x, tri[0].y); lineTo(tri[1].x, tri[1].y); lineTo(tri[2].x, tri[2].y); close()
        }, ReactorCyan.copy(alpha = 0.85f), style = Stroke(width = R / 16f))
        drawCircle(GlowWhite.copy(alpha = 0.9f), R * 0.07f, Offset(cx, cy - r3 * 0.15f))
    }
}

/** Vision-wave style audio meter. */
@Composable
fun VoiceWave(modifier: Modifier, level: Float, active: Boolean) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "phase")
    Canvas(modifier) {
        val mid = size.height / 2
        val n = 60
        for (i in 0 until n) {
            val x = i * size.width / n
            val flow = if (active) level else level * 0.25f
            val h = size.height * 0.42f * flow *
                (0.55f + 0.45f * sin(i * 0.5f + phase * 6.28f).toFloat())
            drawLine(
                Brush.verticalGradient(listOf(ReactorCyan, PurpleAccent, ReactorCyan), endY = size.height),
                Offset(x, mid - h), Offset(x, mid + h), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
}

/** Particle-sphere "galaxy" used by the voice + memory visualisations. */
@Composable
fun GalaxyOrb(modifier: Modifier, fill: Float = 1f) {
    val t = rememberInfiniteTransition(label = "galaxy")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "spin")
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2; val R = size.minDimension / 2.3f
        val dots = 260
        for (i in 0 until dots) {
            val fi = i.toFloat()
            val y = ((i % 37) - 18) / 18f
            val ring = 0.28f + 0.72f * ((i % 53) / 53f)
            val a = Math.toRadians((fi * 137.5) % 360 / 360 * 360.0 + spin)
            val px = cx + (R * ring * cos(a).toFloat() * kotlin.math.sqrt(1f - y * y))
            val py = cy + R * y * ring * 0.82f
            drawCircle(
                if (i % 7 == 0) GlowWhite.copy(alpha = 0.75f * fill) else ReactorCyan.copy(alpha = 0.42f * fill),
                if (i % 11 == 0) 3.2f else 2.0f, Offset(px, py))
        }
        // equatorial sweep band
        drawOval(
            Brush.horizontalGradient(listOf(Color.Transparent, ReactorCyan.copy(alpha = 0.35f), Color.Transparent)),
            Offset(cx - R, cy - 6f), size = androidx.compose.ui.geometry.Size(R * 2, 12f))
    }
}

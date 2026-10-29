package com.brahma.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.brahma.jarvis.core.Runtime
import com.brahma.jarvis.core.session.AssistantSession
import com.brahma.jarvis.core.services.JarvisMicService
import com.brahma.jarvis.ui.components.ReactorOrb
import com.brahma.jarvis.ui.screens.BootScreen
import com.brahma.jarvis.ui.screens.HomeScreen
import com.brahma.jarvis.ui.screens.MemoryScreen
import com.brahma.jarvis.ui.screens.QuickPanel
import com.brahma.jarvis.ui.screens.SettingsPage
import com.brahma.jarvis.ui.screens.SettingsScreen
import com.brahma.jarvis.ui.screens.ToolsScreen
import com.brahma.jarvis.ui.screens.VoiceScreen
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim
import com.brahma.jarvis.ui.screens.SettingsScreen as SettingsRoute

class MainActivity : ComponentActivity() {

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rt = Runtime.get(this)
        if (Build.VERSION.SDK_INT >= 33) {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
            if (need.isNotEmpty()) perms.launch(need.toTypedArray())
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }

        if (intent.getStringExtra("jarvis_line") != null) {
            AssistantSession.statusLine.value = "ONLINE"
        }

        setContent { JarvisAppUi() }
    }
}

@Composable
fun JarvisAppUi() {
    var booted by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("home") }
    var settingsPage by remember { mutableStateOf(SettingsPage.MAIN) }
    var micRunning by remember { mutableStateOf(JarvisMicService.isRunning) }
    var quickOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rt = Runtime.get(context)

    LaunchedEffect(Unit) {
        AssistantSession.statusLine.collect { }
    }

    if (!booted) {
        BootScreen(onDone = { booted = true })
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF02060E))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "home" -> HomeScreen(onWakeToggle = { on ->
                        micRunning = on
                        if (on) JarvisMicService.start(context) else JarvisMicService.stop(context)
                    }, wakeRunning = micRunning)
                    "voice" -> VoiceScreen(
                        onMicPress = {
                            // Manual push: straight into capture through the service,
                            // or answer in-app if the service is off.
                            if (!JarvisMicService.isRunning) {
                                JarvisMicService.start(context)
                                micRunning = true
                            }
                            AssistantSession.listening.value = true
                        },
                        onType = { text ->
                            AssistantSession.statusLine.value = "THINKING"
                            rt.brain.ask(text) { AssistantSession.statusLine.value = "MIC LIVE" }
                        },
                        onChip = { chip ->
                            val q = when (chip) {
                                "Web Search" -> "Search the web for: what's new today?"
                                "YouTube" -> "Open YouTube"
                                "Summarize" -> "Summarize my recent conversation for me, sir."
                                else -> "Analyze: what needs my attention first?"
                            }
                            rt.brain.ask(q) {}
                        })
                    "tools" -> ToolsScreen()
                    "memory" -> MemoryScreen()
                    "settings" -> SettingsRoute(onPage = { settingsPage = it }, page = settingsPage)
                }
            }
            BottomNav(tab) { t -> tab = t }
        }

        if (quickOpen) {
            Box(Modifier.fillMaxSize().background(Color(0x8802060E))
                .clickable { quickOpen = false })
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).padding(top = 10.dp)) {
                    QuickPanel(onClose = { quickOpen = false }, onQuickAction = { cmd ->
                        quickOpen = false
                        tab = "voice"
                        val q = when (cmd) {
                            "camera" -> "Analyze what my camera sees best, sir."
                            "youtube" -> "Open YouTube"
                            "summarize" -> "Summarize what we discussed recently, sir."
                            else -> "Translate my last message to plain English."
                        }
                        rt.brain.ask(q) {}
                    })
                }
            }
        }

        // left edge swipe-in trigger
        Box(Modifier.size(width = 14.dp, height = 320.dp).padding(top = 90.dp)
            .clickable { quickOpen = true })
    }
}

@Composable
private fun BottomNav(current: String, onTab: (String) -> Unit) {
    val items = listOf("home" to "HOME", "tools" to "TOOLS", "react" to "REACTOR",
        "memory" to "MEMORY", "settings" to "SETTINGS")
    Column(Modifier.fillMaxWidth().background(Color(0xFF02060E)).padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically) {
            items.forEach { (id, label) ->
                val active = current == id
                when (id) {
                    "react" -> Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF062033))
                        .border(2.dp, ReactorCyan, RoundedCornerShape(26.dp))
                        .clickable { onTab("voice") },
                        contentAlignment = Alignment.Center) {
                        ReactorOrb(Modifier.size(38.dp))
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onTab(id) }.padding(horizontal = 10.dp)) {
                        Text(
                            when (id) {
                                "home" -> "⌂"; "tools" -> "⚒"; "memory" -> "⬡"; else -> "⚙"
                            },
                            color = if (active) ReactorCyan else TextDim, fontSize = 17.sp)
                        Text(label, color = if (active) ReactorCyan else TextDim, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

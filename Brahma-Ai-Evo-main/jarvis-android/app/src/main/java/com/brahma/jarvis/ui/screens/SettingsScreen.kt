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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
import com.brahma.jarvis.core.evolve.EvolutionEngine
import com.brahma.jarvis.core.settings.Providers
import com.brahma.jarvis.ui.components.GlowDot
import com.brahma.jarvis.ui.theme.AmberWarn
import com.brahma.jarvis.ui.theme.CyanLine
import com.brahma.jarvis.ui.theme.GlowWhite
import com.brahma.jarvis.ui.theme.RedAlert
import com.brahma.jarvis.ui.theme.ReactorCyan
import com.brahma.jarvis.ui.theme.TextDim
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

enum class SettingsPage { MAIN, LLM, VOICE, CONNECTIONS, EVOLUTION, DIAG }

@Composable
fun SettingsScreen(onPage: (SettingsPage) -> Unit, page: SettingsPage) {
    when (page) {
        SettingsPage.MAIN -> MainSettings(onPage)
        SettingsPage.LLM -> LlmSettings()
        SettingsPage.VOICE -> VoiceSettings()
        SettingsPage.CONNECTIONS -> ConnectionsSettings()
        SettingsPage.EVOLUTION -> EvolutionSettings()
        SettingsPage.DIAG -> DiagnosticsScreen()
    }
}

/** Generic settings text field wired to a named JarvisSettings field. */
@Composable
private fun SettingField(label: String, initial: String, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val value = remember(label) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF04101E))
            .border(1.dp, CyanLine, RoundedCornerShape(8.dp))) {
            BasicTextField(value = value.value, onValueChange = { value.value = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                textStyle = TextStyle(color = GlowWhite, fontSize = 11.sp))
        }
        Text("SAVE", color = ReactorCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable {
                runCatching { onSave(value.value) }
                    .onSuccess { Toast.makeText(context, "Saved.", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show() }
            }.padding(top = 2.dp))
    }
}

/** API key field: writes only into the encrypted KeyVault. */
@Composable
private fun KeyField(provider: String) {
    val context = LocalContext.current
    val rt = Runtime.get(context)
    val value = remember(provider) { mutableStateOf(rt.keys.get(provider)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(provider, color = GlowWhite, fontSize = 11.sp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF04101E))
            .border(1.dp, CyanLine, RoundedCornerShape(8.dp))) {
            BasicTextField(value = value.value, onValueChange = { value.value = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                textStyle = TextStyle(color = GlowWhite, fontSize = 11.sp))
        }
        Text("SET KEY", color = ReactorCyan, fontSize = 10.sp,
            modifier = Modifier.clickable {
                rt.keys.set(provider, value.value)
                Toast.makeText(context, "Key stored encrypted.", Toast.LENGTH_SHORT).show()
            })
    }
}

@Composable
fun MainSettings(onPage: (SettingsPage) -> Unit) {
    val rows = listOf(
        Triple("AI Model", "Providers, keys, fast/strong routing", SettingsPage.LLM),
        Triple("Voice Settings", "Wake word, STT, TTS providers, voices", SettingsPage.VOICE),
        Triple("Connections", "MCP servers + API keys + remote brain", SettingsPage.CONNECTIONS),
        Triple("Evolution", "Self-evolution log, rollback, kill switch", SettingsPage.EVOLUTION),
        Triple("Diagnostics", "Live self-tests: mic, wake, STT, TTS, LLM, memory", SettingsPage.DIAG),
    )
    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("Settings", color = ReactorCyan, fontSize = 15.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.size) { i ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC06121F)).border(1.dp, CyanLine, RoundedCornerShape(12.dp))
                    .clickable { onPage(rows[i].third) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A2B3D)), contentAlignment = Alignment.Center) {
                        Text(rows[i].first.take(2).uppercase(), color = ReactorCyan, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rows[i].first, color = GlowWhite, fontSize = 13.sp)
                        Text(rows[i].second, color = TextDim, fontSize = 10.sp)
                    }
                    Text("›", color = TextDim, fontSize = 15.sp)
                }
            }
            item {
                Text("About · Jarves v3.0 · J.A.R.V.E.S. core (native)",
                    color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun LlmSettings() {
    val context = LocalContext.current
    val rt = Runtime.get(context)
    var snapshot by remember { mutableStateOf(rt.current()) }
    LaunchedEffect(Unit) { snapshot = rt.current() }
    fun save(k: String, v: String) {
        rt.settings.updateBlocking { s ->
            when (k) {
                "openAiBaseUrl" -> s.copy(openAiBaseUrl = v)
                "openAiModelFast" -> s.copy(openAiModelFast = v)
                "openAiModelStrong" -> s.copy(openAiModelStrong = v)
                "anthropicBaseUrl" -> s.copy(anthropicBaseUrl = v)
                "anthropicModelStrong" -> s.copy(anthropicModelStrong = v)
                "llmChain" -> s.copy(llmChain = v)
                "remoteBrainUrl" -> s.copy(remoteBrainUrl = v)
                "remoteBrainToken" -> s.copy(remoteBrainToken = v)
                else -> s
            }
        }
        snapshot = rt.current()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("‹ Settings", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("AI Model · providers and routing", color = GlowWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SettingField("Base URL (OpenAI-compatible: OpenRouter/Groq/DeepSeek...)", snapshot.openAiBaseUrl) { save("openAiBaseUrl", it) } }
            item { SettingField("Fast model (small talk)", snapshot.openAiModelFast) { save("openAiModelFast", it) } }
            item { SettingField("Strong model (reasoning)", snapshot.openAiModelStrong) { save("openAiModelStrong", it) } }
            item { SettingField("Anthropic base URL", snapshot.anthropicBaseUrl) { save("anthropicBaseUrl", it) } }
            item { SettingField("Anthropic model", snapshot.anthropicModelStrong) { save("anthropicModelStrong", it) } }
            item { SettingField("Fallback chain (order matters: openai,anthropic)", snapshot.llmChain) { save("llmChain", it) } }
            item { SettingField("Remote brain base URL (empty = on-device brain)", snapshot.remoteBrainUrl) { save("remoteBrainUrl", it) } }
            item { SettingField("Remote brain token", snapshot.remoteBrainToken) { save("remoteBrainToken", it) } }
            item {
                Column {
                    Text("API keys (encrypted in Android Keystore storage)", color = TextDim, fontSize = 10.sp)
                    KeyField("openAi"); KeyField("anthropic"); KeyField("customLlm")
                }
            }
        }
    }
}

@Composable
private fun VoiceSettings() {
    val context = LocalContext.current
    val rt = Runtime.get(context)
    var snapshot by remember { mutableStateOf(rt.current()) }
    val wake = remember {
        com.brahma.jarvis.core.voice.WakeWordEngine(context,
            { rt.current().wakePhrases.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() } },
            { 50 }, {})
    }
    var wakeProgress by remember { mutableStateOf(wake.selfTest().second) }
    fun save(k: String, v: String) {
        rt.settings.updateBlocking { s ->
            when (k) {
                "wakePhrases" -> s.copy(wakePhrases = v)
                "ttsFishRefVoice" -> s.copy(ttsFishRefVoice = v)
                "ttsElevenVoiceId" -> s.copy(ttsElevenVoiceId = v)
                "ttsGenericBaseUrl" -> s.copy(ttsGenericBaseUrl = v)
                "ttsGenericBodyTemplate" -> s.copy(ttsGenericBodyTemplate = v)
                "ttsGenericAudioFormat" -> s.copy(ttsGenericAudioFormat = v)
                else -> s
            }
        }
        snapshot = rt.current()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("‹ Settings", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("Voice · wake word, STT, TTS", color = GlowWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SettingField("Wake phrases (comma-separated; spoken after wake)", snapshot.wakePhrases) { save("wakePhrases", it) } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wake-word model (Vosk, offline)", color = TextDim, fontSize = 10.sp)
                        Text(wakeProgress, color = GlowWhite, fontSize = 11.sp)
                    }
                    Text("DOWNLOAD", color = ReactorCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            wake.downloadModel({ p -> wakeProgress = "Downloading $p%" },
                                { ok, why -> wakeProgress = if (ok) "model ready" else "failed: $why" })
                        })
                }
            }
            item {
                Column {
                    Text("TTS provider (fallback chain ends in Android TTS)", color = TextDim, fontSize = 10.sp)
                    Providers.TTS.forEachIndexed { i, name ->
                        val active = snapshot.ttsPrimary == i
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC06121F))
                            .border(1.dp, if (active) ReactorCyan else CyanLine, RoundedCornerShape(8.dp))
                            .clickable { rt.settings.updateBlocking { s -> s.copy(ttsPrimary = i) }; snapshot = rt.current() }
                            .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (active) "◉" else "○", color = ReactorCyan, fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = GlowWhite, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            item { SettingField("Fish Audio reference voice id", snapshot.ttsFishRefVoice) { save("ttsFishRefVoice", it) } }
            item { SettingField("ElevenLabs voice id", snapshot.ttsElevenVoiceId) { save("ttsElevenVoiceId", it) } }
            item { SettingField("Custom TTS base URL", snapshot.ttsGenericBaseUrl) { save("ttsGenericBaseUrl", it) } }
            item { SettingField("Custom TTS body template ({{text}}/{{voice}}/{{key}})", snapshot.ttsGenericBodyTemplate) { save("ttsGenericBodyTemplate", it) } }
            item { SettingField("Custom TTS audio format (pcm/wav/mp3)", snapshot.ttsGenericAudioFormat) { save("ttsGenericAudioFormat", it) } }
            item {
                Column {
                    Text("STT provider", color = TextDim, fontSize = 10.sp)
                    Providers.STT.forEachIndexed { i, name ->
                        val active = snapshot.sttPrimary == i
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC06121F))
                            .border(1.dp, if (active) ReactorCyan else CyanLine, RoundedCornerShape(8.dp))
                            .clickable { rt.settings.updateBlocking { s -> s.copy(sttPrimary = i) }; snapshot = rt.current() }
                            .padding(10.dp)) {
                            Text(if (active) "◉" else "○", color = ReactorCyan, fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = GlowWhite, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            item {
                Column { KeyField("fishAudio"); KeyField("elevenlabs"); KeyField("deepgram") }
            }
        }
    }
}

@Composable
private fun ConnectionsSettings() {
    val context = LocalContext.current
    val rt = Runtime.get(context)
    fun reload() = rt.tools.mcpServers().let { a ->
        (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }
    }
    var servers by remember { mutableStateOf(reload()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("‹ Settings", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("Connections · MCP servers", color = GlowWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(servers.size) { i ->
                val s = servers[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xCC06121F))
                    .border(1.dp, CyanLine, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    GlowDot(true)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.optString("name"), color = GlowWhite, fontSize = 12.sp)
                        Text(s.optString("url"), color = TextDim, fontSize = 10.sp, maxLines = 1)
                    }
                    Text("✕", color = RedAlert, fontSize = 13.sp, modifier = Modifier.clickable {
                        rt.tools.removeMcp(s.optString("name"))
                        servers = reload()
                    })
                }
            }
            item {
                Column {
                    Text("Add an MCP server (any apps/tools you expose via MCP)",
                        color = TextDim, fontSize = 10.sp)
                    SettingField("Name", "") { name = it }
                    SettingField("URL", "") { url = it }
                    Text("+ ADD", color = ReactorCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            if (url.startsWith("http")) {
                                rt.tools.connectMcp(name.ifBlank { "server-${servers.size + 1}" }, url)
                                rt.tools.refreshMcpTools()
                                servers = reload(); name = ""; url = ""
                                Toast.makeText(context, "Server linked; tools will scan in.", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, "URL must start with http(s)", Toast.LENGTH_SHORT).show()
                        })
                }
            }
            item {
                Column {
                    Text("API keys (encrypted)", color = TextDim, fontSize = 10.sp)
                    KeyField("openAi"); KeyField("anthropic"); KeyField("fishAudio")
                    KeyField("elevenlabs"); KeyField("deepgram"); KeyField("customLlm")
                }
            }
        }
    }
}

@Composable
private fun EvolutionSettings() {
    val context = LocalContext.current
    val rt = Runtime.get(context)
    var log by remember { mutableStateOf(rt.memory.listEvolutionRows()) }
    var enabled by remember { mutableStateOf(rt.current().evolutionEnabled) }
    Column(Modifier.fillMaxSize().background(Color(0xFF02060E)).padding(12.dp)) {
        Text("Evolution · nightly proposals", color = GlowWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kill switch", color = GlowWhite, fontSize = 12.sp)
                Text(if (enabled) "evolution runs nightly, regression-gated" else "disabled by you",
                    color = TextDim, fontSize = 10.sp)
            }
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { v ->
                enabled = v
                rt.settings.updateBlocking { s -> s.copy(evolutionEnabled = v) }
            })
        }
        Spacer(Modifier.height(8.dp))
        Text("RUN REFLECTION NOW", color = ReactorCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable {
                val line = EvolutionEngine.runReflection(rt)
                log = rt.memory.listEvolutionRows()
                Toast.makeText(context, line, Toast.LENGTH_SHORT).show()
            })
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(log.size) { i ->
                Text(log[i], color = GlowWhite, fontSize = 10.sp, lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace)
            }
            item {
                Text("↺ Roll back last applied rule (1-tap)", color = AmberWarn, fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        EvolutionEngine.rollback(rt, 0)
                        log = rt.memory.listEvolutionRows()
                        Toast.makeText(context, "Rolled back.", Toast.LENGTH_SHORT).show()
                    })
            }
        }
    }
}

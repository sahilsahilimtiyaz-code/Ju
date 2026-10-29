package com.brahma.jarvis.core.tools

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Executed-in-Kotlin device actions, permission-checked; audit-logged by ToolRegistry. */
class DeviceTools(private val ctx: Context) {

    fun registerAll(reg: ToolRegistry) {
        reg.add(ToolDef("set_alarm", "Set an alarm at given hour/minute.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("hour", JSONObject().put("type", "integer"))
                .put("minute", JSONObject().put("type", "integer"))
                .put("label", JSONObject().put("type", "string"))), false)
        ) { a ->
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour", 7))
                putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
                putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis alarm"))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            if (i.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult("Alarm set for ${a.optInt("hour", 7)}:${"%02d".format(a.optInt("minute", 0))}.", "ok")
            } else ToolResult("No clock app available.", "error")
        }

        reg.add(ToolDef("set_timer", "Start a countdown timer for N seconds.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("seconds", JSONObject().put("type", "integer"))
                .put("label", JSONObject().put("type", "string"))), false)
        ) { a ->
            val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60).coerceIn(1, 86_400))
                putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis timer"))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            if (i.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult("Timer started for ${a.optInt("seconds", 60)} seconds.", "ok")
            } else ToolResult("No clock app available.", "error")
        }

        reg.add(ToolDef("add_reminder", "Reminder notification at epoch millis (or in N minutes).",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string"))
                .put("in_minutes", JSONObject().put("type", "integer"))
                .put("at_epoch_ms", JSONObject().put("type", "integer"))), false)
        ) { a ->
            val when_ = if (a.has("at_epoch_ms")) a.getLong("at_epoch_ms")
            else System.currentTimeMillis() + a.optInt("in_minutes", 15) * 60_000L
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(ctx, (when_ % 100000).toInt(),
                Intent("com.brahma.jarvis.REMINDER").putExtra("text", a.optString("text"))
                    .setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when_, pi)
                ToolResult("Reminder scheduled for ${SimpleDateFormat("HH:mm", Locale.US).format(Date(when_))}.", "ok")
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when_, pi)
                ToolResult("Reminder set (inexact; exact-alarm permission missing).", "ok")
            }
        }

        reg.add(ToolDef("open_app", "Open an installed app by partial name (e.g. whatsapp).",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string"))), false)
        ) { a ->
            val q = a.optString("query", "").lowercase()
            val pm = ctx.packageManager
            val found = pm.getInstalledApplications(0).firstOrNull { app ->
                (pm.getApplicationLabel(app) as String).lowercase().contains(q)
            }
            if (found != null) {
                val intent = pm.getLaunchIntentForPackage(found.packageName)
                if (intent != null) {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ToolResult("Opening ${pm.getApplicationLabel(found)}.", "ok")
                } else ToolResult("No launchable activity for that app.", "error")
            } else ToolResult("App not found: $q", "error")
        }

        reg.add(ToolDef("flashlight", "Toggle the phone torch. on=true/false.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("on", JSONObject().put("type", "boolean"))), false)
        ) { a ->
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val torchId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (torchId == null) ToolResult("No flash unit on this phone.", "error")
            else {
                cm.setTorchMode(torchId, a.optBoolean("on", true))
                ToolResult("Torch ${if (a.optBoolean("on", true)) "on" else "off"}.", "ok")
            }
        }

        reg.add(ToolDef("set_volume", "Set media volume 0..100.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("level", JSONObject().put("type", "integer"))), false)
        ) { a ->
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                (a.optInt("level", 50).coerceIn(0, 100) * max / 100), 0)
            ToolResult("Media volume set to ${a.optInt("level", 50)}%.", "ok")
        }

        reg.add(ToolDef("call_number", "Dial a phone number (opens the dialer).",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("number", JSONObject().put("type", "string"))), true)
        ) { a ->
            val n = a.optString("number", "")
            if (n.isBlank()) ToolResult("No number given.", "error")
            else {
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult("Dialer open for $n.", "ok")
            }
        }

        reg.add(ToolDef("sms_compose", "Compose (not send) an SMS to a number.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("number", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))), true)
        ) { a ->
            ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${a.optString("number") }"))
                .putExtra("sms_body", a.optString("text"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult("SMS composer open. Not sent until you press send.", "ok")
        }

        reg.add(ToolDef("calendar_today", "List today's calendar events.", emptySchema(), false)
        ) { _ ->
            val noPerm = (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED)
            if (noPerm) ToolResult("Calendar permission not granted.", "needs-permission")
            else {
            val now = System.currentTimeMillis()
            val end = now + 24 * 3600_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(now.toString()).appendPath(end.toString()).build()
            val out = StringBuilder()
            ctx.contentResolver.query(uri, arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.EVENT_LOCATION), null, null,
                CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext() && out.length < 1500) {
                    val t = SimpleDateFormat("HH:mm", Locale.US)
                    out.append(t.format(Date(c.getLong(1)))).append(' ').append(c.getString(0))
                    if (!c.isNull(2)) out.append(" @").append(c.getString(2))
                    out.append('\n')
                }
            }
            ToolResult(out.toString().ifBlank { "No events today." }, "ok")
            }
        }

        reg.add(ToolDef("system_report", "Battery, storage, memory, temperature, alarms.",
            emptySchema(), false)
        ) { _ -> ToolResult(systemReportForPrompt(), "ok") }

        reg.add(ToolDef("web_search", "Open a web search in the browser.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string"))), false)
        ) { a ->
            val q = Uri.encode(a.optString("query", ""))
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=$q"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult("Browser open with search results.", "ok")
        }

        reg.add(ToolDef("memory_save", "Save a fact about me into long-term memory.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("fact", JSONObject().put("type", "string"))
                .put("category", JSONObject().put("type", "string"))), false)
        ) { a ->
            ToolRegistryHooks.memoryHook?.invoke(a.optString("fact"),
                a.optString("category", "preference"))
                ?: ToolResult("memory not linked", "error")
        }

        reg.add(ToolDef("memory_search", "Search long-term memory.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string"))), false)
        ) { a ->
            val hits = ToolRegistryHooks.memorySearchHook?.invoke(a.optString("query", ""))
            ToolResult(if (hits.isNullOrBlank()) "Nothing found." else hits, "ok")
        }

        reg.add(ToolDef("read_notifications", "Read recent notification headlines (needs notification-listener link).",
            emptySchema(), false)
        ) { _ ->
            val lines = NotificationHeadlineService.snapshot()
            ToolResult(if (lines.isEmpty()) "Notification reading is not linked yet." else lines.take(8).joinToString("\n"), "ok")
        }
    }

    private fun emptySchema() = JSONObject().put("type", "object").put("properties", JSONObject())

    /** Real device stats via the sticky ACTION_BATTERY_CHANGED broadcast (API-safe). */
    fun batterySnapshot(): Triple<Int, Boolean, Double> {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        return Triple(pct, charging, temp)
    }

    fun systemReportForPrompt(): String {
        val (pct, charging, temp) = batterySnapshot()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stat = Environment.getDataDirectory() // storage
        val sf = StatFs(stat.path)
        val gb = { v: Long -> "%.1f".format(v / (1024.0 * 1024.0 * 1024.0)) }
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memInfo)
        val a = JSONObject()
            .put("battery_pct", pct).put("charging", charging).put("battery_temp_c", temp)
            .put("storage_avail_gb", gb(sf.availableBytes)).put("storage_total_gb", gb(sf.totalBytes))
            .put("mem_avail_mb", memInfo.availMem / (1024 * 1024))
            .put("audio_volume_pct", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
        return a.toString()
    }

    fun snapshotForUi(): JSONObject = runCatching { JSONObject(systemReportForPrompt()) }
        .getOrDefault(JSONObject())

    fun selfTest(): List<Pair<String, String>> {
        val (pct, _, temp) = batterySnapshot()
        val clockOk = System.currentTimeMillis() > 1_600_000_000_000
        val snd = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return listOf(
            "device" to "battery=${pct}% temp=${temp}°C",
            "clockSync" to if (clockOk) "ok" else "bad",
            "audio" to (if (snd.mode != AudioManager.MODE_NORMAL) "in-call(${snd.mode})" else "normal"),
        )
    }
}

object ToolRegistryHooks {
    var memoryHook: ((fact: String, category: String) -> ToolResult)? = null
    var memorySearchHook: ((query: String) -> String)? = null
}

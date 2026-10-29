package com.brahma.jarvis.core.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brahma.jarvis.core.Runtime

/**
 * On Android 14+ a mic foreground service cannot start from the background, so after
 * reboot we post a "Tap to wake Jarvis" notification instead of auto-starting the mic.
 */
class BootNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        JarvisBootNotice.post(context)
    }
}

/** Exact-alarm reminders scheduled by the add_reminder tool. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Reminder, sir."
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val ch = android.app.NotificationChannel("jarvis_reminders", "Jarvis reminders",
            android.app.NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(ch)
        val open = android.app.PendingIntent.getActivity(
            context, 5, Intent(context, com.brahma.jarvis.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(context, "jarvis_reminders")
            .setContentTitle("Jarves reminder")
            .setContentText(text)
            .setSmallIcon(com.brahma.jarvis.R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(990, n) }
        Runtime.peek(context)?.memory?.recordSignal("reminder_fired", 1.0, text.take(80))
    }
}

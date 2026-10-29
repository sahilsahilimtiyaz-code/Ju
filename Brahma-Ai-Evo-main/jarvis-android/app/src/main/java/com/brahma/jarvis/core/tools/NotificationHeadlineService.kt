package com.brahma.jarvis.core.tools

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.brahma.jarvis.core.session.AssistantSession

/**
 * Read-out source: captures recent headline notifications so the LLM tool
 * `read_notifications` can report what is on screen (opt-in via listener
 * permission, graceful no-op otherwise).
 */
class NotificationHeadlineService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank()) return
        synchronized(lock) {
            headlines.addLast(System.currentTimeMillis().toString() + "|" + sbn.packageName + "|" + title + "|" + text.take(160))
            while (headlines.size > 15) headlines.removeFirst()
        }
    }

    override fun onListenerConnected() {
        AssistantSession.statusLine.value = "NOTIFICATIONS LINKED"
    }

    companion object {
        private val headlines = ArrayDeque<String>()
        private val lock = Any()

        fun snapshot(): List<String> = synchronized(lock) { headlines.toList() }
    }
}

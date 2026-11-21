package com.aura.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraNotificationListener : NotificationListenerService() {

    data class NotifItem(
        val appName: String,
        val sender: String,
        val text: String,
        val time: String
    )

    companion object {
        private val recentNotifications = ArrayDeque<NotifItem>()

        fun getRecentSummary(): String {
            if (recentNotifications.isEmpty()) {
                return "Belum ada notifikasi baru yang tercatat."
            }
            return recentNotifications.take(5).joinToString(". ") {
                "Dari ${it.appName}, ${it.sender}: ${it.text}"
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val friendlyApp = when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("kai") -> "KAI Access"
            pkg.contains("traveloka") -> "Traveloka"
            pkg.contains("gojek") -> "Gojek"
            pkg.contains("youtube") -> "YouTube"
            pkg.contains("messaging") || pkg.contains("mms") -> "SMS"
            else -> pkg.substringAfterLast('.')
        }

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        synchronized(recentNotifications) {
            recentNotifications.addFirst(NotifItem(friendlyApp, title, text, timeStr))
            while (recentNotifications.size > 20) {
                recentNotifications.removeLast()
            }
        }
    }
}

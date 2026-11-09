package ir.dastyar.bridge

import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * خواندن نوتیفیکیشن‌ها — کاربر باید یک‌بار در تنظیمات اندروید
 * (Settings › Notification access) به این اپ اجازه بدهد.
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private val LOCK = Any()
        private val RECENT = ArrayDeque<Map<String, String>>()
        private const val MAX = 30

        @Volatile
        var connected = false
            private set

        fun recent(limit: Int): List<Map<String, String>> = synchronized(LOCK) {
            RECENT.takeLast(minOf(limit, MAX)).toList()
        }
    }

    private val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.US)

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        // اتصال دوباره خودکار
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationListener::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.notification == null) return
        if (sbn.packageName == packageName) return // اعلان خود پل را ثبت نکن

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

        val app = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val item = mapOf(
            "app" to app,
            "title" to title.take(120),
            "text" to text.take(300),
            "time" to fmt.format(Date(sbn.postTime)),
        )
        synchronized(LOCK) {
            RECENT.addLast(item)
            while (RECENT.size > MAX) RECENT.removeFirst()
        }
    }
}

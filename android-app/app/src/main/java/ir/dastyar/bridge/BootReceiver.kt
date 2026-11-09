package ir.dastyar.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** بعد از ری‌استارت گوشی، سرویس پل خودکار بالا بیاید */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.isPaired(context)) {
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        }
    }
}

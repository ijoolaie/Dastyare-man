package ir.dastyar.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

class RayaBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private val holdHandler = Handler(Looper.getMainLooper())
    private var pttStarted = false
    private val startPttRunnable = Runnable { startPushToTalk() }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val quick = intent.getBooleanExtra("quick_capture_active", false)
            val ptt = intent.getBooleanExtra("ptt_active", false)
            val finalizing = intent.getBooleanExtra("ptt_finalizing", false)
            if (!intent.hasExtra("quick_capture_active") && !intent.hasExtra("ptt_active") && !intent.hasExtra("ptt_finalizing")) return
            bubble?.text = when { ptt -> "\u25CF"; finalizing || quick -> "\u2026"; else -> "R" }
            bubble?.alpha = if (ptt || finalizing || quick) 0.72f else 1f
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("raya_bubble", "Raya floating button", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 91, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "raya_bubble")
            .setSmallIcon(R.drawable.ic_app).setContentTitle("Raya is ready")
            .setContentText("Tap the floating R button to speak a command").setContentIntent(open).build()
        startForeground(91, notification)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter(VoiceAssistantService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(stateReceiver, filter)
        showBubble()
    }

    private fun showBubble() {
        if (!android.provider.Settings.canDrawOverlays(this) || bubble != null) return
        val density = resources.displayMetrics.density
        val size = (44 * density).toInt()
        val view = TextView(this).apply {
            text = "R"
            textSize = 19f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = 12f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0D9488.toInt())
                setStroke((2 * density).toInt(), 0xFF99F6E4.toInt())
            }
            contentDescription = "R: tap to speak; hold to talk and release to send"
        }
        val lp = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (240 * density).toInt()
        }
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y; dragged = false; pttStarted = false
                    holdHandler.removeCallbacks(startPttRunnable)
                    holdHandler.postDelayed(startPttRunnable, 220L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        dragged = true
                        holdHandler.removeCallbacks(startPttRunnable)
                    }
                    if (dragged) {
                        lp.x = (startX - dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    holdHandler.removeCallbacks(startPttRunnable)
                    if (!dragged) {
                        if (pttStarted) endPushToTalk() else startQuickCapture()
                    }
                    pttStarted = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    holdHandler.removeCallbacks(startPttRunnable)
                    if (pttStarted) endPushToTalk()
                    pttStarted = false
                    true
                }
                else -> true
            }
        }
        try { windowManager.addView(view, lp); bubble = view }
        catch (_: Exception) { Toast.makeText(this, "\u0627\u062c\u0627\u0632\u0647 \u0646\u0645\u0627\u06cc\u0634 \u0631\u0648\u06cc \u0628\u0631\u0646\u0627\u0645\u0647\u200c\u0647\u0627 \u0631\u0627 \u0641\u0639\u0627\u0644 \u06a9\u0646", Toast.LENGTH_LONG).show(); stopSelf() }
    }

    private fun startPushToTalk() {
        if (dragged || pttStarted) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required. Open Raya to grant it.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        pttStarted = true
        bubble?.alpha = 0.72f
        ContextCompat.startForegroundService(this,
            Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_PTT_START))
    }

    private fun endPushToTalk() {
        ContextCompat.startForegroundService(this,
            Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_PTT_END))
    }
    private fun startQuickCapture() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "\u0627\u0628\u062a\u062f\u0627 \u0645\u062c\u0648\u0632 \u0645\u06cc\u06a9\u0631\u0648\u0641\u0648\u0646 \u0631\u0627 \u062f\u0631 \u0628\u0631\u0646\u0627\u0645\u0647 \u0641\u0639\u0627\u0644 \u06a9\u0646", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        bubble?.alpha = 0.72f
        ContextCompat.startForegroundService(this,
            Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_QUICK_CAPTURE))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        try { bubble?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        bubble = null
        super.onDestroy()
    }
}

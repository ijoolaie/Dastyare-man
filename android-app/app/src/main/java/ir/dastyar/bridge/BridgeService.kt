package ir.dastyar.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * سرویس همیشه‌فعال پل — با Foreground Service بقا در پس‌زمینه تضمین می‌شود.
 * پروتکل: register (معرفی) → poll (long-polling تا ۲۵ ثانیه) → اجرا → result
 */
class BridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "bridge_foreground"
        const val NOTIF_ID = 42
        private const val POLL_WAIT_S = 25
        private const val TAG = "RayaBridge"

        /** توانایی‌هایی که اپ به سرور معرفی می‌کند — با ANDROID_CAPS سمت سرور هماهنگ است */
        val CAPS = listOf(
            "sysinfo", "battery", "vibrate", "notify", "clipboard_write", "clipboard_read",
            "open_url", "open_app", "apps", "media", "volume", "flashlight",
            "geolocation", "sms_send", "contacts", "call",
        )
    }

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Bridge service created; paired=${Prefs.isPaired(this)}")
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = Thread { bridgeLoop() }.apply {
                name = "bridge-loop"
                start()
            }
            Log.i(TAG, "Bridge polling worker started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Log.i(TAG, "Bridge service destroyed")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "پل دستیار", NotificationManager.IMPORTANCE_LOW))
        }
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setContentTitle("دستیار من فعال است")
            .setContentText("اتصال به دستیار برقرار است")
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .setContentIntent(tap)
        startForeground(NOTIF_ID, b.build())
    }

    // ── شبکه ─────────────────────────────────────────────────────

    private class HttpResult(val code: Int, val body: String)

    private fun httpPost(base: String, path: String, body: JSONObject, readTimeoutMs: Int = 30_000): HttpResult? {
        return try {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            val result = HttpResult(conn.responseCode, text)
            Log.d(TAG, "POST $path HTTP ${result.code}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun pollOnce(server: String, token: String): JSONObject? {
        val url = URL("$server/api/devices/poll?token=${URLEncoder.encode(token, "UTF-8")}&wait=$POLL_WAIT_S")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = (POLL_WAIT_S + 20) * 1000
        if (conn.responseCode != 200) {
            val err = try {
                conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            throw Exception("poll HTTP ${conn.responseCode}: ${err?.take(200) ?: ""}")
        }
        val data = JSONObject(conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
        val command = data.optJSONObject("command")
        if (command != null) Log.i(TAG, "Command received id=${command.optString("id")} action=${command.optString("action")}")
        return command
    }

    // ── حلقه اصلی ────────────────────────────────────────────────

    private fun bridgeLoop() {
        var backoff = 2_000L
        while (running) {
            val server = Prefs.serverUrl(this)
            val token = Prefs.token(this)
            if (server.isEmpty() || token.isEmpty()) {
                running = false
                return
            }
            try {
                // معرفی دستگاه — نام را نمی‌فرستیم تا نامی که کاربر در اپ وب انتخاب کرده حفظ شود
                val regBody = JSONObject()
                    .put("token", token)
                    .put("os", "Android ${Build.VERSION.RELEASE} · اپ اختصاصی")
                    .put("hostname", Build.MODEL)
                    .put("capabilities", JSONArray(CAPS))
                val reg = httpPost(server, "/api/devices/register", regBody)
                    ?: throw Exception("سرور در دسترس نیست")
                val regJson = try { JSONObject(reg.body) } catch (e: Exception) { JSONObject() }
                if (reg.code != 200 || !regJson.optBoolean("ok", false)) {
                    throw Exception("ثبت‌نام رد شد: ${regJson.optString("error", "HTTP ${reg.code}")}")
                }
                Log.i(TAG, "Device registered; capabilities=${CAPS.joinToString(",")}")
                backoff = 2_000L

                // حلقه دریافت دستورها
                while (running) {
                    val cmd = pollOnce(server, token) ?: continue
                    val id = cmd.optString("id")
                    val action = cmd.optString("action")
                    val args = cmd.optJSONObject("args") ?: JSONObject()
                    val (ok, result) = Commands.execute(this, action, args)
                    Log.i(TAG, "Command completed id=$id action=$action ok=$ok resultChars=${result.length}")
                    val resBody = JSONObject()
                        .put("token", token)
                        .put("id", id)
                        .put("ok", ok)
                        .put("result", result)
                    val posted = httpPost(server, "/api/devices/result", resBody)
                    if (posted == null || posted.code !in 200..299) {
                        Log.e(TAG, "Command result delivery failed id=$id http=${posted?.code ?: "network"}")
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                Log.e(TAG, "Bridge loop error: ${e.javaClass.simpleName}: ${e.message}")
                SystemClock.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }
}
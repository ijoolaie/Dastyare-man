package ir.dastyar.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Keeps the paired Android device connected to the standalone Dastyar API. */
class BridgeService : Service() {
    companion object {
        const val CHANNEL_ID = "bridge_foreground"
        const val NOTIF_ID = 42
        private const val POLL_WAIT_S = 25
        private const val TAG = "RayaBridge"
        val CAPS = listOf(
            "sysinfo", "battery", "vibrate", "notify", "clipboard_write", "clipboard_read",
            "open_url", "open_app", "apps", "media", "volume", "flashlight",
            "geolocation", "sms_send", "contacts", "call", "notifications"
        )
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private val stateLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Bridge service created; paired=${Prefs.isPaired(this)}")
        startForegroundCompat("Starting device bridge…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(stateLock) {
            if (!Prefs.isPaired(this)) {
                Log.w(TAG, "Bridge start ignored: device is not paired")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (worker?.isAlive != true) {
                running = true
                worker = Thread({ bridgeLoop() }, "bridge-loop").also { it.start() }
                Log.i(TAG, "Bridge polling worker started")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        Log.i(TAG, "Bridge service destroyed")
        super.onDestroy()
    }

    private fun startForegroundCompat(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dastyar device bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("Dastyar device bridge")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .setContentIntent(tap)
        startForeground(NOTIF_ID, builder.build())
    }

    private fun updateStatus(status: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("Dastyar device bridge")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .setContentIntent(tap)
        nm.notify(NOTIF_ID, builder.build())
    }

    private class HttpResult(val code: Int, val body: String)

    private fun httpPost(base: String, path: String, body: JSONObject, readTimeoutMs: Int = 30_000): HttpResult {
        val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            Log.d(TAG, "POST $path HTTP $code")
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun pollOnce(server: String, token: String): JSONObject? {
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val conn = (URL("${server.trimEnd('/')}/api/devices/poll?token=$encodedToken&wait=$POLL_WAIT_S")
            .openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = (POLL_WAIT_S + 20) * 1000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                throw IllegalStateException("poll HTTP $code: ${err?.take(200).orEmpty()}")
            }
            val payload = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val command = JSONObject(payload).optJSONObject("command")
            if (command != null) {
                Log.i(TAG, "Command received id=${command.optString("id")} action=${command.optString("action")}")
            }
            return command
        } finally {
            conn.disconnect()
        }
    }

    private fun deliverResult(server: String, token: String, id: String, ok: Boolean, result: String): Boolean {
        val body = JSONObject()
            .put("token", token)
            .put("id", id)
            .put("ok", ok)
            .put("result", result)
        var delayMs = 1_000L
        repeat(4) { attempt ->
            if (!running) return false
            try {
                val response = httpPost(server, "/api/devices/result", body)
                if (response.code in 200..299) {
                    Log.i(TAG, "Command result delivered id=$id attempt=${attempt + 1}")
                    return true
                }
                Log.w(TAG, "Result delivery returned HTTP ${response.code} id=$id attempt=${attempt + 1}")
            } catch (e: Exception) {
                Log.w(TAG, "Result delivery failed id=$id attempt=${attempt + 1}: ${e.javaClass.simpleName}")
            }
            if (attempt < 3 && running) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return false }
                delayMs = (delayMs * 2).coerceAtMost(8_000L)
            }
        }
        Log.e(TAG, "Result delivery exhausted retries id=$id")
        return false
    }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun bridgeLoop() {
        var backoff = 2_000L
        while (running) {
            val server = Prefs.serverUrl(this).trim().trimEnd('/')
            val token = Prefs.token(this).trim()
            if (server.isEmpty() || token.isEmpty()) {
                Log.w(TAG, "Bridge stopped: pairing credentials are missing")
                running = false
                stopSelf()
                return
            }
            try {
                if (!hasInternet()) {
                    updateStatus("Waiting for an internet connection…")
                    Thread.sleep(5_000L)
                    continue
                }
                updateStatus("Connecting to server…")
                val registration = JSONObject()
                    .put("token", token)
                    .put("os", "Android ${Build.VERSION.RELEASE}")
                    .put("hostname", Build.MODEL)
                    .put("capabilities", JSONArray(CAPS))
                val reg = httpPost(server, "/api/devices/register", registration)
                val regJson = try { JSONObject(reg.body) } catch (_: Exception) { JSONObject() }
                if (reg.code !in 200..299 || !regJson.optBoolean("ok", false)) {
                    throw IllegalStateException("registration rejected: ${regJson.optString("error", "HTTP ${reg.code}")}")
                }
                Log.i(TAG, "Device registered; capabilities=${CAPS.joinToString(",")}")
                updateStatus("Connected · waiting for commands")
                backoff = 2_000L

                while (running) {
                    val cmd = pollOnce(server, token) ?: continue
                    val id = cmd.optString("id").trim()
                    val action = cmd.optString("action").trim()
                    if (id.isEmpty() || action.isEmpty()) {
                        Log.e(TAG, "Ignored malformed command: missing id or action")
                        continue
                    }
                    updateStatus("Executing: $action")
                    val args = cmd.optJSONObject("args") ?: JSONObject()
                    val (ok, result) = Commands.execute(this, action, args)
                    Log.i(TAG, "Command completed id=$id action=$action ok=$ok resultChars=${result.length}")
                    deliverResult(server, token, id, ok, result)
                    updateStatus("Connected · waiting for commands")
                }
            } catch (e: InterruptedException) {
                if (!running) return
                Log.w(TAG, "Bridge worker interrupted")
            } catch (e: Exception) {
                if (!running) return
                Log.e(TAG, "Bridge loop error: ${e.javaClass.simpleName}: ${e.message}")
                updateStatus("Connection problem · retrying shortly")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { if (!running) return }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }
}

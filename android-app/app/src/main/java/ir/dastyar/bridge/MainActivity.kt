package ir.dastyar.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * اکتیویتی اصلی — دو حالت:
 * ۱) جفت‌سازی: وارد کردن آدرس سرور + توکن (از تب «دستگاه‌ها» اپ وب)
 * ۲) اپ: نمایش همان اپ وب داخل WebView + اجرای سرویس پل در پس‌زمینه
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** آدرس پیش‌فرض اپ «دستیار من» — در صفحه جفت‌سازی قابل ویرایش است */
        const val DEFAULT_SERVER_URL =
            "https://preview-chat-cec4d141-d1d4-4c9b-8a88-8642856002be.space-z.ai"
        const val ACTION_REQUEST_PERMISSION = "ir.dastyar.bridge.REQUEST_PERMISSION"
        const val EXTRA_PERMISSION = "permission"
        private const val PERMISSION_PREFS = "permission_prompts"
        private const val PENDING_PERMISSION = "pending_permission"
    }

    private lateinit var pairingView: ScrollView
    private lateinit var webView: WebView
    private lateinit var etServer: EditText
    private lateinit var etToken: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnSettings: ImageButton

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val permission = intent?.getStringExtra(EXTRA_PERMISSION) ?: return
            requestPermissionIfNeeded(permission)
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingView = findViewById(R.id.pairing)
        webView = findViewById(R.id.webview)
        etServer = findViewById(R.id.etServer)
        etToken = findViewById(R.id.etToken)
        btnConnect = findViewById(R.id.btnConnect)
        btnSettings = findViewById(R.id.btnSettings)

        etServer.setText(Prefs.serverUrl(this).ifEmpty { DEFAULT_SERVER_URL })
        btnConnect.setOnClickListener { connect() }
        btnSettings.setOnClickListener { openSettings() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        if (Prefs.isPaired(this)) showApp() else showPairing()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_REQUEST_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val pending = prefs.getString(PENDING_PERMISSION, null) ?: return
        prefs.edit().remove(PENDING_PERMISSION).apply()
        requestPermissionIfNeeded(pending)
    }

    private fun requestPermissionIfNeeded(permission: String) {
        getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE).edit().remove(PENDING_PERMISSION).apply()
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            toast("این مجوز از قبل فعال است")
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), 1002)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1003) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Prefs.setVoiceEnabled(this, true)
                ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java))
                toast("دستیار صوتی رایا فعال شد؛ بگو رایا")
            } else {
                Prefs.setVoiceEnabled(this, false)
                toast("برای فعال شدن بیدارباش رایا، مجوز میکروفون لازم است")
            }
        } else if (requestCode == 1002) {
            toast(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "مجوز فعال شد؛ عملیات را دوباره اجرا کن" else "مجوز داده نشد؛ این قابلیت در دسترس نیست")
        }
    }

    private fun showPairing() {
        pairingView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        btnSettings.visibility = View.GONE
    }

    private fun showApp() {
        pairingView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        btnSettings.visibility = View.VISIBLE
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        enableVoiceAssistantIfAllowed()
        webView.loadUrl(Prefs.serverUrl(this))
    }

    private fun enableVoiceAssistantIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Prefs.setVoiceEnabled(this, true)
            ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java))
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1003)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ── جفت‌سازی ─────────────────────────────────────────────────

    private fun connect() {
        val server = etServer.text.toString().trim().trimEnd('/')
        val token = etToken.text.toString().trim()
        if (!server.startsWith("http")) {
            toast("آدرس سرور باید با http شروع شود")
            return
        }
        if (token.isEmpty()) {
            toast("توکن را از تب «دستگاه‌ها» کپی کن")
            return
        }
        btnConnect.isEnabled = false
        btnConnect.text = "در حال اتصال…"

        Thread {
            var ok = false
            var err = ""
            try {
                val body = JSONObject()
                    .put("token", token)
                    .put("os", "Android ${Build.VERSION.RELEASE} · اپ اختصاصی")
                    .put("hostname", Build.MODEL)
                    .put("capabilities", JSONArray(BridgeService.CAPS))
                val conn = URL("$server/api/devices/register").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                val resp = JSONObject(text)
                ok = conn.responseCode in 200..299 && resp.optBoolean("ok", false)
                if (!ok) err = resp.optString("error", "HTTP ${conn.responseCode}")
            } catch (e: Exception) {
                err = e.message ?: "خطای شبکه"
            }
            runOnUiThread {
                btnConnect.isEnabled = true
                btnConnect.text = getString(R.string.btn_connect)
                if (ok) {
                    Prefs.save(this, server, token)
                    toast("متصل شد ✅")
                    showApp()
                } else {
                    toast("اتصال ناموفق: $err")
                }
            }
        }.start()
    }

    // Notification-listener access is intentionally omitted in the temporary sideload build.

    // ── منوی تنظیمات ─────────────────────────────────────────────

    private fun openSettings() {
        val options = arrayOf(
            "تغییر اتصال (سرور یا توکن دیگر)",
            "غیرفعال‌کردن بهینه‌سازی باتری",
        )
        AlertDialog.Builder(this)
            .setTitle("تنظیمات پل")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        stopService(Intent(this, BridgeService::class.java))
                        Prefs.clear(this)
                        recreate()
                    }
                    1 -> try {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        toast("این گزینه در این دستگاه موجود نیست")
                    }
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}

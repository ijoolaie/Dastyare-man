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
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var btnListening: Button
    private lateinit var btnBack: ImageButton

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VoiceAssistantService.ACTION_STATE_CHANGED) { updateListeningButton(); return }
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
        btnListening = findViewById(R.id.btnListening)
        btnBack = findViewById(R.id.btnBack)

        etServer.setText(Prefs.serverUrl(this).ifEmpty { DEFAULT_SERVER_URL })
        btnConnect.setOnClickListener { connect() }
        btnSettings.setOnClickListener { openSettings() }
        btnListening.setOnClickListener { toggleListening() }
        btnBack.setOnClickListener { navigateBack() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Edge compatibility token for web apps that reject Android WebView's default Chrome-only identity.
        // The rendering engine remains the device's Chromium-based Android WebView.
        val defaultUserAgent = WebSettings.getDefaultUserAgent(this)
        val chromeVersion = Regex("Chrome/([0-9.]+)").find(defaultUserAgent)?.groupValues?.get(1)
        if (chromeVersion != null && !defaultUserAgent.contains("EdgA/")) {
            // Keep Chrome/ so Chromium feature detection remains truthful, while exposing Edge-for-Android identity.
            webView.settings.userAgentString = "$defaultUserAgent EdgA/$chromeVersion"
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        if (Prefs.isPaired(this)) showApp() else showPairing()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_REQUEST_PERMISSION).apply { addAction(VoiceAssistantService.ACTION_STATE_CHANGED) }
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
        updateListeningButton()
        ensureFloatingButton()
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
                updateListeningButton()
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
        btnListening.visibility = View.GONE
        btnBack.visibility = View.GONE
    }

    private fun showApp() {
        pairingView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        btnSettings.visibility = View.VISIBLE
        btnListening.visibility = View.GONE
        btnBack.visibility = View.VISIBLE
        updateListeningButton()
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        enableVoiceAssistantIfAllowed()
        updateListeningButton()
        webView.loadUrl(Prefs.serverUrl(this))
        ensureFloatingButton()
    }

    private fun ensureFloatingButton() {
        if (!Prefs.isPaired(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (!getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE).getBoolean("overlay_prompted", false)) {
                getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE).edit().putBoolean("overlay_prompted", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("\u062f\u06a9\u0645\u0647 \u0634\u0646\u0627\u0648\u0631 \u0631\u0627\u06cc\u0627")
                    .setMessage("\u0628\u0631\u0627\u06cc \u0646\u0645\u0627\u06cc\u0634 \u062f\u06a9\u0645\u0647 \u062f\u0627\u06cc\u0631\u0647\u200c\u0627\u06cc \u0631\u0627\u06cc\u0627 \u0631\u0648\u06cc \u0647\u0645\u0647 \u0635\u0641\u062d\u0647\u200c\u0647\u0627\u06cc \u06af\u0648\u0634\u06cc\u060c \u0627\u062c\u0627\u0632\u0647 \u0646\u0645\u0627\u06cc\u0634 \u0631\u0648\u06cc \u0628\u0631\u0646\u0627\u0645\u0647\u200c\u0647\u0627\u06cc \u062f\u06cc\u06af\u0631 \u0631\u0627 \u0641\u0639\u0627\u0644 \u06a9\u0646.")
                    .setNegativeButton("\u0628\u0639\u062f\u0627\u064b", null)
                    .setPositiveButton("\u0641\u0639\u0627\u0644\u200c\u0633\u0627\u0632\u06cc") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { toast("\u062a\u0646\u0638\u06cc\u0645 \u0645\u062c\u0648\u0632 \u0646\u0645\u0627\u06cc\u0634 \u0634\u0646\u0627\u0648\u0631 \u062f\u0631 \u062f\u0633\u062a\u0631\u0633 \u0646\u06cc\u0633\u062a") }
                    }.show()
            }
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, RayaBubbleService::class.java))
    }

    private fun navigateBack() {
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack()
            else webView.evaluateJavascript("if(window.history.length>1){window.history.back();}", null)
        }
    }

    private fun updateListeningButton() {
        if (!::btnListening.isInitialized) return
        val enabled = Prefs.isVoiceEnabled(this)
        val manual = ActivationMode.fromId(Prefs.activationMode(this)) == ActivationMode.MANUAL
        val capturing = enabled && (!manual || Prefs.isManualCaptureActive(this))
        btnListening.text = if (capturing) getString(R.string.listening_stop) else getString(R.string.listening_start)
        btnListening.contentDescription = btnListening.text
    }

    private fun toggleListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1003)
            return
        }
        val manual = ActivationMode.fromId(Prefs.activationMode(this)) == ActivationMode.MANUAL
        val enabled = Prefs.isVoiceEnabled(this)
        val action = when {
            !enabled && manual -> VoiceAssistantService.ACTION_START_CAPTURE
            !enabled -> null
            manual && Prefs.isManualCaptureActive(this) -> VoiceAssistantService.ACTION_STOP_CAPTURE
            manual -> VoiceAssistantService.ACTION_START_CAPTURE
            else -> VoiceAssistantService.ACTION_STOP
        }
        if (action == VoiceAssistantService.ACTION_STOP) {
            ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java).setAction(action))
        } else {
            if (action == null) Prefs.setVoiceEnabled(this, true)
            val intent = Intent(this, VoiceAssistantService::class.java)
            if (action != null) intent.action = action
            ContextCompat.startForegroundService(this, intent)
        }
        btnListening.postDelayed({ updateListeningButton() }, 350)
    }
    private fun enableVoiceAssistantIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (Prefs.isVoiceEnabled(this)) {
                ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java))
            }
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
                    Prefs.setVoiceEnabled(this, true)
                    toast("متصل شد ✅")
                    showApp()
                } else {
                    toast("اتصال ناموفق: $err")
                }
            }
        }.start()
    }

    // Notification-listener access is user-enabled from the app settings menu.

    // ── منوی تنظیمات ─────────────────────────────────────────────

    private fun openSettings() {
        val options = arrayOf(
            "\u0627\u0646\u062a\u062e\u0627\u0628 \u0645\u062f\u0644 \u062a\u0634\u062e\u06cc\u0635 \u06af\u0641\u062a\u0627\u0631",
            "\u0627\u0646\u062a\u062e\u0627\u0628 \u0635\u062f\u0627 \u0648 \u0645\u0648\u062a\u0648\u0631 \u06af\u0641\u062a\u0627\u0631 \u0631\u0627\u06cc\u0627",
            "\u0647\u0648\u06cc\u062a \u0648 \u062d\u0627\u0641\u0638\u0647 \u0631\u0627\u06cc\u0627",
            "\u062a\u0627\u0631\u06cc\u062e\u0686\u0647 \u0645\u06a9\u0627\u0644\u0645\u0627\u062a \u0635\u0648\u062a\u06cc",
            "\u0628\u0647\u06cc\u0646\u0647\u200c\u0633\u0627\u0632\u06cc \u0628\u0627\u062a\u0631\u06cc",
            "\u0641\u0639\u0627\u0644\u200c\u0633\u0627\u0632\u06cc \u06a9\u0646\u062a\u0631\u0644 \u062e\u0627\u0645\u0648\u0634/\u0631\u0648\u0634\u0646 \u0635\u0641\u062d\u0647",
            "\u062f\u0633\u062a\u0631\u0633\u06cc \u0627\u0639\u0644\u0627\u0646\u200c\u0647\u0627",
            "\u062a\u063a\u06cc\u06cc\u0631 \u0627\u062a\u0635\u0627\u0644 \u06cc\u0627 \u0642\u0637\u0639 \u0627\u062a\u0635\u0627\u0644",
            "\u062d\u0627\u0644\u062a \u0641\u0639\u0627\u0644\u200c\u0633\u0627\u0632\u06cc \u0634\u0646\u0648\u062f",
            "\u0645\u062c\u0648\u0632 \u062f\u06a9\u0645\u0647 \u0634\u0646\u0627\u0648\u0631 \u0631\u0627\u06cc\u0627"
        )
        AlertDialog.Builder(this)
            .setTitle("\u062a\u0646\u0638\u06cc\u0645\u0627\u062a \u062f\u0633\u062a\u06cc\u0627\u0631 \u0645\u0646")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openVoiceModelSettings()
                    1 -> openTtsSettings()
                    2 -> openMemorySettings()
                    3 -> openVoiceHistory()
                    4 -> openBatterySettings()
                    5 -> openScreenControlSettings()
                    6 -> openNotificationAccessSettings()
                    8 -> openActivationModeSettings()
                    9 -> openFloatingButtonSettings()
                    7 -> AlertDialog.Builder(this)
                        .setTitle("\u0642\u0637\u0639 \u0627\u062a\u0635\u0627\u0644 \u062f\u0633\u062a\u06af\u0627\u0647\u061f")
                        .setMessage("\u0628\u0627 \u0642\u0637\u0639 \u0627\u062a\u0635\u0627\u0644\u060c \u0646\u0634\u0627\u0646\u06cc \u0633\u0631\u0648\u0631 \u0648 \u062a\u0648\u06a9\u0646 \u0630\u062e\u06cc\u0631\u0647\u200c\u0634\u062f\u0647 \u067e\u0627\u06a9 \u0645\u06cc\u200c\u0634\u0648\u062f \u0648 \u0628\u0631\u0627\u06cc \u0627\u062a\u0635\u0627\u0644 \u062f\u0648\u0628\u0627\u0631\u0647 \u0628\u0627\u06cc\u062f \u062f\u0633\u062a\u06af\u0627\u0647 \u0631\u0627 \u062c\u0641\u062a\u200c\u0633\u0627\u0632\u06cc \u06a9\u0646\u06cc.")
                        .setNegativeButton("\u0627\u0646\u0635\u0631\u0627\u0641", null)
                        .setPositiveButton("\u0642\u0637\u0639 \u0627\u062a\u0635\u0627\u0644") { _, _ ->
                            stopService(Intent(this, VoiceAssistantService::class.java))
                            stopService(Intent(this, BridgeService::class.java))
                            Prefs.clear(this)
                            recreate()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun openFloatingButtonSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { toast("\u062a\u0646\u0638\u06cc\u0645 \u0645\u062c\u0648\u0632 \u0646\u0645\u0627\u06cc\u0634 \u0634\u0646\u0627\u0648\u0631 \u062f\u0631 \u062f\u0633\u062a\u0631\u0633 \u0646\u06cc\u0633\u062a") }
        } else {
            ContextCompat.startForegroundService(this, Intent(this, RayaBubbleService::class.java))
            toast("\u062f\u06a9\u0645\u0647 \u0634\u0646\u0627\u0648\u0631 \u0631\u0627\u06cc\u0627 \u0641\u0639\u0627\u0644 \u0627\u0633\u062a")
        }
    }

    private fun openScreenControlSettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("\u062f\u0631 \u0641\u0647\u0631\u0633\u062a \u0633\u0631\u0648\u06cc\u0633\u200c\u0647\u0627\u06cc \u0646\u0635\u0628\u200c\u0634\u062f\u0647\u060c Dastyar screen control \u0631\u0627 \u067e\u06cc\u062f\u0627 \u0648 \u0641\u0639\u0627\u0644 \u06a9\u0646.")
        } catch (_: Exception) {
            toast("\u0628\u0627\u0632\u06a9\u0631\u062f\u0646 \u062a\u0646\u0638\u06cc\u0645\u0627\u062a \u062f\u0633\u062a\u0631\u0633\u200c\u067e\u0630\u06cc\u0631\u06cc \u0645\u0645\u06a9\u0646 \u0646\u0634\u062f\u061b \u0627\u0632 \u062a\u0646\u0638\u06cc\u0645\u0627\u062a \u06af\u0648\u0634\u06cc \u0648\u0627\u0631\u062f Accessibility \u0634\u0648.")
        }
    }
    private fun openActivationModeSettings() {
        val modes = ActivationMode.values()
        val labels = arrayOf(
            "\u0647\u0645\u06cc\u0634\u0647 \u06af\u0648\u0634 \u0628\u062f\u0647 (\u0628\u062f\u0648\u0646 \u06a9\u0644\u0645\u0647 \u0628\u06cc\u062f\u0627\u0631\u0628\u0627\u0634)",
            "\u0628\u0627 \u06af\u0641\u062a\u0646 \u0631\u0627\u06cc\u0627 \u0641\u0639\u0627\u0644 \u0634\u0648",
            "\u0641\u0642\u0637 \u0628\u0627 \u0641\u0634\u0631\u062f\u0646 \u062f\u06a9\u0645\u0647 \u0634\u0631\u0648\u0639 \u0634\u0646\u0648\u062f"
        )
        val selected = modes.indexOf(ActivationMode.fromId(Prefs.activationMode(this))).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("\u062d\u0627\u0644\u062a \u0641\u0639\u0627\u0644\u200c\u0633\u0627\u0632\u06cc \u062f\u0633\u062a\u06cc\u0627\u0631 \u0635\u0648\u062a\u06cc")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val mode = modes[which]
                Prefs.setActivationMode(this, mode.id)
                dialog.dismiss()
                restartVoiceService()
                val note = if (mode == ActivationMode.MANUAL) "\u0628\u0631\u0627\u06cc \u0634\u0631\u0648\u0639 \u0634\u0646\u0648\u062f، \u062f\u06a9\u0645\u0647 \u0634\u0631\u0648\u0639 \u0631\u0627 \u062f\u0631 \u0627\u0639\u0644\u0627\u0646 \u0631\u0627\u06cc\u0627 \u0628\u0632\u0646." else "\u062d\u0627\u0644\u062a \u062c\u062f\u06cc\u062f \u0630\u062e\u06cc\u0631\u0647 \u0634\u062f."
                toast(note)
            }
            .setNegativeButton("\u0627\u0646\u0635\u0631\u0627\u0641", null)
            .show()
    }

    private fun openVoiceModelSettings() {
        val models = VoiceModel.values()
        val labels = models.map { it.title }.toTypedArray()
        val selected = models.indexOf(VoiceModel.fromId(Prefs.voiceModel(this))).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("مدل تشخیص گفتار")
            .setTitle("مدل تشخیص گفتار را انتخاب کن")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val model = models[which]
                Prefs.setVoiceModel(this, model.id)
                dialog.dismiss()
                restartVoiceService()
                toast("مدل انتخابی ذخیره شد: ${model.title}")
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun openTtsSettings() {
        val voices = TtsVoice.values()
        val labels = voices.map { it.title }.toTypedArray()
        var selected = voices.indexOf(TtsVoice.fromId(Prefs.ttsVoice(this))).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("صدای رایا را انتخاب کن")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("ذخیره") { _, _ ->
                val voice = voices[selected]
                Prefs.setTtsVoice(this, voice.id)
                restartVoiceService()
                toast("صدای رایا ذخیره شد: ${voice.title}")
            }
            .setNeutralButton("پخش نمونه") { _, _ ->
                val voice = voices[selected]
                val test = Intent(this, VoiceAssistantService::class.java)
                    .setAction(VoiceAssistantService.ACTION_TEST_TTS)
                    .putExtra(VoiceAssistantService.EXTRA_TTS_VOICE, voice.id)
                ContextCompat.startForegroundService(this, test)
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun restartVoiceService() {
        if (!Prefs.isVoiceEnabled(this) || !Prefs.isPaired(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        stopService(Intent(this, VoiceAssistantService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java))
    }

    private fun openMemorySettings() {
        val store = RayaMemoryStore(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 4)
        }
        fun field(label: String, key: String, fallback: String, minLines: Int = 1): EditText {
            val edit = EditText(this).apply {
                hint = label
                setText(store.getProfile(key, fallback))
                setSingleLine(minLines == 1)
                if (minLines > 1) setMinLines(minLines)
            }
            layout.addView(edit)
            return edit
        }
        val assistant = field("نام دستیار", "assistant_name", "رایا")
        val app = field("نام برنامه", "app_name", "دستیار من (Dastyare-man)")
        val creator = field("نام سازنده / توسعه‌دهنده", "creator", "ijoolaie")
        val purpose = field("هدف برنامه", "purpose", "دستیار شخصی اندرویدی برای مکالمه فارسی و اجرای فرمان‌های مجاز", 3)
        val notes = field("حافظه تکمیلی و نکات پایدار", "memory_notes", "", 4)
        AlertDialog.Builder(this)
            .setTitle("حافظه و هویت رایا")
            .setMessage("این اطلاعات به‌صورت محلی روی گوشی ذخیره می‌شوند و در درخواست‌های مکالمه برای معرفی هویت رایا استفاده می‌شوند.")
            .setView(layout)
            .setPositiveButton("ذخیره") { _, _ ->
                store.setProfile("assistant_name", assistant.text.toString())
                store.setProfile("app_name", app.text.toString())
                store.setProfile("creator", creator.text.toString())
                store.setProfile("purpose", purpose.text.toString())
                store.setProfile("memory_notes", notes.text.toString())
                toast("حافظه هویتی ذخیره شد")
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun openVoiceHistory() {
        val store = RayaMemoryStore(this)
        val conversations = store.conversations(50)
        if (conversations.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Voice history")
                .setMessage("No saved voice conversations yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = conversations.map { item ->
            val preview = item.preview.replace(Regex("\\s+"), " ").take(64).ifBlank { "Conversation" }
            val time = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.updatedAt))
            "$time  |  ${item.turnCount} messages\n$preview"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Voice conversations")
            .setItems(labels) { _, which ->
                val selected = conversations[which]
                val turns = store.turnsForConversation(selected.id)
                val content = turns.joinToString("\n\n") { turn ->
                    val role = if (turn.role == "user") "You" else "Raya"
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(turn.createdAt))
                    "$role | $time\n${turn.content}"
                }
                val text = TextView(this).apply {
                    setPadding(32, 24, 32, 24)
                    textSize = 14f
                    this.text = content
                }
                AlertDialog.Builder(this)
                    .setTitle("Conversation details")
                    .setView(ScrollView(this).apply { addView(text) })
                    .setPositiveButton("Close", null)
                    .show()
            }
            .setNeutralButton("Clear all") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Delete all saved voice history? This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        store.clearHistory()
                        toast("Voice history cleared")
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            toast("Unable to open notification access settings")
        }
    }
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast("تنظیمات بهینه‌سازی باتری در این دستگاه در دسترس نیست")
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE) { navigateBack(); return }
        moveTaskToBack(true)
    }
}

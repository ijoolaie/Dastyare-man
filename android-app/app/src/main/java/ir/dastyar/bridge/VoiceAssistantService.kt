package ir.dastyar.bridge

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.tts.*
import androidx.core.app.NotificationCompat
import org.json.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "ir.dastyar.bridge.voice.STOP"
        private const val CHANNEL = "raya_voice"
        private const val NOTIFICATION_ID = 43
        private const val SESSION_MS = 45_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var modelLoader: Thread? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var busy = false
    private var activeUntil = 0L
    private var conversationId: String? = null
    @Volatile private var destroyed = false

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {
            val text = try { JSONObject(hypothesis ?: "{}").optString("text") } catch (_: Exception) { "" }
            handleRecognized(text)
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            handler.post { if (!destroyed) updateNotification("خطای میکروفون: " + (exception?.message ?: "نامشخص")) }
        }
        override fun onTimeout() {}
    }

    override fun onCreate() {
        super.onCreate()
        foreground()
        tts = TextToSpeech(this, this)
        loadModelAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setVoiceEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (speechService == null && modelLoader == null && !destroyed) loadModelAsync()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            updateNotification("موتور تبدیل متن به گفتار در دسترس نیست")
            return
        }
        var language = tts?.setLanguage(Locale("fa", "IR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (language < TextToSpeech.LANG_AVAILABLE)
            language = tts?.setLanguage(Locale("fa")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        ttsReady = language >= TextToSpeech.LANG_AVAILABLE
        if (ttsReady) {
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { handler.post { resumeListening() } }
                override fun onDone(id: String?) { handler.post { resumeListening() } }
            })
            pendingSpeech?.let { pendingSpeech = null; speak(it) }
        } else updateNotification("صدای فارسی نصب نیست؛ زبان فارسی را در تنظیمات Text-to-Speech نصب کن")
    }

    private fun loadModelAsync() {
        if (modelLoader != null || speechService != null || destroyed) return
        updateNotification("آماده‌سازی تشخیص گفتار فارسی…")
        modelLoader = Thread {
            var loadedModel: Model? = null
            var loadedRecognizer: Recognizer? = null
            var loadedSpeech: SpeechService? = null
            try {
                val dir = VoskModelInstaller.ensureModel(this,
                    { percent -> handler.post { if (!destroyed) updateNotification("دریافت مدل فارسی: $percent٪") } },
                    { destroyed || Thread.currentThread().isInterrupted })
                if (destroyed) return@Thread
                handler.post { updateNotification("بارگذاری مدل فارسی…") }
                val createdModel = Model(dir.absolutePath)
                loadedModel = createdModel
                val createdRecognizer = Recognizer(createdModel, 16_000.0f)
                loadedRecognizer = createdRecognizer
                val createdSpeech = SpeechService(createdRecognizer, 16_000.0f)
                loadedSpeech = createdSpeech
                handler.post {
                    if (destroyed) {
                        try { loadedSpeech?.stop(); loadedSpeech?.shutdown(); loadedRecognizer?.close(); loadedModel?.close() } catch (_: Exception) {}
                        return@post
                    }
                    model = loadedModel
                    recognizer = loadedRecognizer
                    speechService = loadedSpeech
                    modelLoader = null
                    speechService?.startListening(voskListener)
                    updateNotification("رایا آماده شنیدن است؛ بگو «رایا»")
                }
            } catch (e: Exception) {
                try { loadedSpeech?.stop(); loadedSpeech?.shutdown(); loadedRecognizer?.close(); loadedModel?.close() } catch (_: Exception) {}
                handler.post {
                    modelLoader = null
                    if (!destroyed) updateNotification("آماده‌سازی مدل فارسی ناموفق بود؛ برنامه را دوباره باز کن. " + (e.message ?: ""))
                }
            }
        }.apply { name = "raya-vosk-model-loader"; start() }
    }

    private fun normalize(s: String) = s.replace('ي', 'ی').replace('ك', 'ک').replace("ـ", "")
        .replace(Regex("[\\u064B-\\u065F]"), "").replace(Regex("[،؛.!؟?]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun handleRecognized(raw: String) {
        if (destroyed || busy) return
        val text = normalize(raw)
        if (text.isBlank()) return
        val wake = text.indexOf("رایا")
        if (wake >= 0) {
            activeUntil = SystemClock.elapsedRealtime() + SESSION_MS
            val command = text.substring(wake + 4).trim().trimStart('،', ',', ' ')
            if (command.isBlank()) speak("جانم، گوش می‌دهم.") else sendToChat(command)
        } else if (SystemClock.elapsedRealtime() < activeUntil) {
            activeUntil = SystemClock.elapsedRealtime() + SESSION_MS
            sendToChat(text)
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) { pendingSpeech = text; updateNotification("پاسخ آماده است؛ صدای فارسی در حال آماده‌سازی"); return }
        if (text.isBlank()) { resumeListening(); return }
        busy = true
        speechService?.setPause(true)
        updateNotification("رایا در حال پاسخ‌گویی است")
        val clean = text.replace(Regex("https?://\\S+"), " ").replace(Regex("[#*_>|]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(3500)
        if (tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()) == TextToSpeech.ERROR)
            resumeListening()
    }

    private fun sendToChat(message: String) {
        if (busy) return
        busy = true
        speechService?.setPause(true)
        updateNotification("در حال دریافت پاسخ از رایا…")
        Thread {
            var answer = ""
            var failure: String? = null
            try {
                val body = JSONObject().put("conversationId", conversationId ?: JSONObject.NULL)
                    .put("message", message).put("attachmentIds", JSONArray())
                val conn = (URL(Prefs.serverUrl(this) + "/api/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                if (conn.responseCode !in 200..299) throw Exception("خطای سرور " + conn.responseCode)
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val ev = try { if (line.isBlank()) null else JSONObject(line) } catch (_: Exception) { null }
                        if (ev != null) when (ev.optString("type")) {
                            "status" -> ev.optString("conversationId").takeIf { it.isNotBlank() }?.let { conversationId = it }
                            "delta" -> answer += ev.optString("text")
                            "done" -> {
                                ev.optString("conversationId").takeIf { it.isNotBlank() }?.let { conversationId = it }
                                ev.optJSONObject("assistantMessage")?.optString("content")?.takeIf { it.isNotBlank() }?.let { answer = it }
                            }
                            "error" -> throw Exception(ev.optString("message", "خطا در پاسخ رایا"))
                        }
                    }
                }
                if (answer.isBlank()) throw Exception("پاسخی از سرور دریافت نشد")
            } catch (e: Exception) { failure = e.message ?: "ارتباط برقرار نشد" }
            handler.post {
                busy = false
                if (destroyed) return@post
                if (failure != null) { activeUntil = 0L; speak("متأسفم. " + failure) }
                else { activeUntil = SystemClock.elapsedRealtime() + SESSION_MS; speak(answer) }
            }
        }.apply { name = "raya-chat-request"; start() }
    }

    private fun resumeListening() {
        busy = false
        speechService?.setPause(false)
        if (!destroyed) updateNotification("رایا آماده شنیدن است؛ بگو «رایا»")
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "مکالمه صوتی رایا", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app).setContentTitle("دستیار صوتی رایا")
            .setContentText("آماده‌سازی شنود فارسی…").setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "توقف شنود", stop).build())
    }

    private fun updateNotification(text: String) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app)
                .setContentTitle("دستیار صوتی رایا").setContentText(text).setOngoing(true)
                .setContentIntent(open).addAction(android.R.drawable.ic_media_pause, "توقف شنود", stop).build())
    }

    override fun onDestroy() {
        destroyed = true
        try { modelLoader?.interrupt() } catch (_: Exception) {}
        try { speechService?.stop(); speechService?.shutdown(); recognizer?.close(); model?.close() } catch (_: Exception) {}
        speechService = null; recognizer = null; model = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }
}
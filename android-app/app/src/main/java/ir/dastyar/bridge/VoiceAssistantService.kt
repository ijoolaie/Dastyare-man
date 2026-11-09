package ir.dastyar.bridge

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.speech.tts.*
import androidx.core.app.NotificationCompat
import org.json.*
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
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var busy = false
    private var activeUntil = 0L
    private var conversationId: String? = null
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        foreground()
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
            scheduleListen(800)
        } else updateNotification("سرویس تشخیص گفتار در دسترس نیست")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setVoiceEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val result = tts?.setLanguage(Locale("fa", "IR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                tts?.setLanguage(Locale("fa"))
            tts?.setSpeechRate(1f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { handler.post { resumeListening() } }
                override fun onDone(id: String?) { handler.post { resumeListening() } }
            })
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { listening = true; updateNotification("برای بیدار کردن بگو «رایا»") }
        override fun onBeginningOfSpeech() { updateNotification("صدایت را می‌شنوم…") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { listening = false }
        override fun onError(error: Int) {
            listening = false
            if (!destroyed && !busy) scheduleListen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 650)
        }
        override fun onResults(results: Bundle?) {
            listening = false
            handleRecognized(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun scheduleListen(delay: Long) {
        handler.removeCallbacks(listenRunnable)
        if (!destroyed && !busy) handler.postDelayed(listenRunnable, delay)
    }
    private val listenRunnable = Runnable { startListening() }

    private fun startListening() {
        if (destroyed || busy || listening || recognizer == null) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) { listening = false; scheduleListen(1800) }
    }

    private fun normalize(s: String) = s.replace('ي','ی').replace('ك','ک').replace("ـ","")
        .replace(Regex("[\\u064B-\\u065F]"), "").replace(Regex("[،؛.!؟?]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun handleRecognized(raw: String) {
        if (destroyed || busy) return
        val text = normalize(raw)
        if (text.isBlank()) { scheduleListen(350); return }
        val wake = text.indexOf("رایا")
        if (wake >= 0) {
            activeUntil = SystemClock.elapsedRealtime() + SESSION_MS
            val command = text.substring(wake + 4).trim().trimStart('،', ',', ' ')
            if (command.isBlank()) speak("جانم، گوش می‌دهم.") else sendToChat(command)
        } else if (SystemClock.elapsedRealtime() < activeUntil) {
            activeUntil = SystemClock.elapsedRealtime() + SESSION_MS
            sendToChat(text)
        } else scheduleListen(350)
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) { scheduleListen(500); return }
        busy = true
        updateNotification("رایا در حال پاسخ‌گویی است")
        val clean = text.replace(Regex("\\x60\\x60\\x60[\\s\\S]*?\\x60\\x60\\x60"), " ")
            .replace(Regex("https?://\\S+"), " ").replace(Regex("[#*_>|]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(3500)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun sendToChat(message: String) {
        if (busy) return
        busy = true
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
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

    private fun resumeListening() { busy = false; if (!destroyed) scheduleListen(450) }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "مکالمه صوتی رایا", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app).setContentTitle("رایا آماده شنیدن است")
            .setContentText("برای شروع مکالمه بگو «رایا»").setOngoing(true).setContentIntent(open)
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
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }
}
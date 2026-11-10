package ir.dastyar.bridge

import android.app.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.content.Intent
import android.os.*
import android.util.Log
import android.speech.tts.*
import androidx.core.app.NotificationCompat
import org.json.*
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "ir.dastyar.bridge.voice.STOP"
        const val ACTION_TEST_TTS = "ir.dastyar.bridge.voice.TEST_TTS"
        const val EXTRA_TTS_VOICE = "tts_voice"
        private const val CHANNEL = "raya_voice"
        private const val NOTIFICATION_ID = 43
        private const val SESSION_MS = 45_000L
        private const val TAG = "RayaVoice"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var modelLoader: Thread? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var offlineTts: OfflineTts? = null
    @Volatile private var offlineTtsLoading = false
    private var pendingSpeech: String? = null
    private var edgePlayer: MediaPlayer? = null
    private var edgeAudioFile: File? = null
    private var toneGenerator: ToneGenerator? = null
    private var listeningCueState: Boolean? = null
    @Volatile private var busy = false
    private var activeUntil = 0L
    // Backend conversation ID and local history ID are deliberately separate.
    private var conversationId: String? = null
    private var voiceHistoryId: String = ""
    private var offlineRecognizer: OfflineRecognizer? = null
    @Volatile private var sherpaRunning = false
    @Volatile private var sherpaProcessing = false
    private var audioRecord: AudioRecord? = null
    private var sherpaThread: Thread? = null
    @Volatile private var destroyed = false

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {
            val text = try { JSONObject(hypothesis ?: "{}").optString("text") } catch (_: Exception) { "" }
            Log.d(TAG, "Vosk final result chars=${text.length}")
            handler.post { handleRecognized(text) }
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk recognition error: ${exception?.javaClass?.simpleName ?: "unknown"}")
            handler.post { if (!destroyed) { updateNotification("خطای تشخیص صدا؛ تلاش مجدد..."); handler.postDelayed({ restartVoskListening() }, 1200) } }
        }
        override fun onTimeout() { Log.w(TAG, "Vosk recognition timeout; restarting"); handler.post { restartVoskListening() } }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Voice service created")
        foreground()
        tts = TextToSpeech(this, this)
        val memory = RayaMemoryStore(this)
        conversationId = (memory.getProfile("voice_server_conversation_id").ifBlank { memory.getProfile("voice_conversation_id") }).takeIf { it.isNotBlank() }
        voiceHistoryId = memory.getProfile("voice_local_history_id")
        if (voiceHistoryId.isBlank()) {
            voiceHistoryId = UUID.randomUUID().toString()
            memory.setProfile("voice_local_history_id", voiceHistoryId)
        }
        loadModelAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setListeningCue(false)
            Prefs.setVoiceEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST_TTS) {
            speak("سلام، من رایا هستم. این یک نمونه صدای آزمایشی است.", intent.getStringExtra(EXTRA_TTS_VOICE))
        }
        if (speechService == null && offlineRecognizer == null && modelLoader == null && !destroyed) loadModelAsync()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        Log.i(TAG, "TTS engine init status=$status")
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS engine initialization failed; using offline Persian voice")
            loadOfflineTtsAsync()
            return
        }
        var language = tts?.setLanguage(Locale("fa", "IR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        Log.i(TAG, "TTS fa-IR language availability=$language")
        if (language < TextToSpeech.LANG_AVAILABLE) {
            language = tts?.setLanguage(Locale("fa")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            Log.i(TAG, "TTS fa language availability=$language")
        }
        ttsReady = language >= TextToSpeech.LANG_AVAILABLE
        Log.i(TAG, "Persian TTS ready=$ttsReady code=$language")
        if (ttsReady) {
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { handler.post { resumeListening() } }
                override fun onDone(id: String?) { handler.post { resumeListening() } }
            })
            pendingSpeech?.let { pendingSpeech = null; speak(it) }
        } else {
            updateNotification("آماده‌سازی صدای فارسی آفلاین")
            loadOfflineTtsAsync()
        }
    }

    private fun loadModelAsync() {
        if (modelLoader != null || speechService != null || offlineRecognizer != null || destroyed) return
        val selected = VoiceModel.fromId(Prefs.voiceModel(this))
        Log.i(TAG, "Loading ASR model id=${selected.id}")
        updateNotification("در حال آماده‌سازی مدل ${selected.title}")
        modelLoader = Thread {
            var loadedModel: Model? = null
            var loadedRecognizer: Recognizer? = null
            var loadedSpeech: SpeechService? = null
            var loadedOffline: OfflineRecognizer? = null
            try {
                if (selected == VoiceModel.VOSK) {
                    val dir = VoskModelInstaller.ensureModel(this,
                        { percent -> handler.post { if (!destroyed) updateNotification("دریافت مدل فارسی: $percent٪") } },
                        { destroyed || Thread.currentThread().isInterrupted })
                    if (destroyed) return@Thread
                    loadedModel = Model(dir.absolutePath)
                    loadedRecognizer = Recognizer(loadedModel, 16_000.0f)
                    loadedSpeech = SpeechService(loadedRecognizer, 16_000.0f)
                } else {
                    loadedOffline = VoiceModelInstaller.createSherpaRecognizer(this, selected, { status ->
                        handler.post { if (!destroyed) updateNotification(status) }
                    }, { destroyed || Thread.currentThread().isInterrupted })
                }
                handler.post {
                    if (destroyed) {
                        try { loadedSpeech?.stop(); loadedSpeech?.shutdown(); loadedRecognizer?.close(); loadedModel?.close(); loadedOffline?.release() } catch (_: Exception) {}
                        return@post
                    }
                    Log.i(TAG, "ASR model loaded id=${selected.id}")
                    model = loadedModel
                    recognizer = loadedRecognizer
                    speechService = loadedSpeech
                    offlineRecognizer = loadedOffline
                    modelLoader = null
                    if (selected == VoiceModel.VOSK) {
                        speechService?.startListening(voskListener)
                        setListeningCue(true)
                    } else {
                        startSherpaAudio()
                    }
                    updateNotification("مدل ${selected.title} آماده است؛ بیدارباش رایا فعال است")
                }
            } catch (e: Exception) {
                try { loadedSpeech?.stop(); loadedSpeech?.shutdown(); loadedRecognizer?.close(); loadedModel?.close(); loadedOffline?.release() } catch (_: Exception) {}
                handler.post {
                    modelLoader = null
                    if (!destroyed) updateNotification("آماده‌سازی مدل ناموفق بود: ${e.message ?: "خطای ناشناخته"}")
                }
            }
        }.apply { name = "raya-${selected.id}-model-loader"; start() }
    }

    private fun restartVoskListening() {
        if (destroyed || busy || VoiceModel.fromId(Prefs.voiceModel(this)) != VoiceModel.VOSK) return
        try {
            speechService?.startListening(voskListener)
            setListeningCue(true)
        } catch (_: Exception) {
            handler.postDelayed({ if (!destroyed && !busy) restartVoskListening() }, 1800)
        }
    }

    private fun startSherpaAudio() {
        if (destroyed || busy || sherpaRunning || offlineRecognizer == null) return
        val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { updateNotification("میکروفون این دستگاه در دسترس نیست"); return }
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 6_400))
        } catch (e: Exception) { updateNotification("ساخت ضبط‌کننده صدا ناموفق بود: ${e.message}"); return }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); updateNotification("میکروفون قابل راه‌اندازی نیست"); return
        }
        try { recorder.startRecording() } catch (e: Exception) {
            recorder.release(); updateNotification("شروع ضبط صدا ناموفق بود: ${e.message}"); return
        }
        audioRecord = recorder
        sherpaRunning = true
        setListeningCue(true)
        sherpaThread = Thread {
            val buffer = ShortArray(640)
            val utterance = ArrayList<Float>(16_000 * 6)
            var speaking = false
            var silenceSamples = 0
            while (sherpaRunning && !destroyed) {
                val count = try { recorder.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
                if (count <= 0) { if (sherpaRunning) Thread.sleep(80); continue }
                if (busy || sherpaProcessing) { utterance.clear(); speaking = false; silenceSamples = 0; continue }
                var energy = 0.0
                for (i in 0 until count) energy += buffer[i].toDouble() * buffer[i].toDouble()
                val rms = kotlin.math.sqrt(energy / count).toInt()
                if (rms >= 360) {
                    speaking = true
                    silenceSamples = 0
                    for (i in 0 until count) utterance.add(buffer[i] / 32768.0f)
                } else if (speaking) {
                    silenceSamples += count
                    for (i in 0 until count) utterance.add(buffer[i] / 32768.0f)
                }
                if (speaking && (silenceSamples >= 12_800 || utterance.size >= 128_000)) {
                    if (utterance.size >= 4_800 && !sherpaProcessing) {
                        val audio = FloatArray(utterance.size) { utterance[it] }
                        sherpaProcessing = true
                        Thread { transcribeSherpa(audio) }.apply { name = "raya-sherpa-decode"; start() }
                    }
                    utterance.clear(); speaking = false; silenceSamples = 0
                }
            }
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }.apply { name = "raya-sherpa-microphone"; start() }
        updateNotification("در حال شنیدن فارسی به‌صورت آفلاین")
    }

    private fun stopSherpaAudio() {
        sherpaRunning = false
        val recorder = audioRecord
        audioRecord = null
        try { recorder?.stop() } catch (_: Exception) {}
        val thread = sherpaThread
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(700) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        sherpaThread = null
    }
    private fun transcribeSherpa(samples: FloatArray) {
        var recognized = ""
        try {
            val active = offlineRecognizer ?: throw IllegalStateException("موتور تشخیص صدا آماده نیست")
            val stream = active.createStream()
            try {
                stream.acceptWaveform(samples, 16_000)
                active.decode(stream)
                recognized = active.getResult(stream).text
            } finally { stream.release() }
        } catch (e: Exception) {
            handler.post { if (!destroyed) updateNotification("خطای پردازش گفتار: ${e.message ?: "نامشخص"}") }
        }
        handler.post {
            sherpaProcessing = false
            Log.d(TAG, "Sherpa final result chars=${recognized.length}")
            if (!destroyed && !busy && recognized.isNotBlank()) handleRecognized(recognized)
        }
    }
    private fun normalize(s: String): String = s
        .replace('\u064A', '\u06CC')
        .replace('\u0643', '\u06A9')
        .replace('\u200C', ' ')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u060C\\u061B\\u061F,.!?;:]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun wakeWordMatch(text: String): IntRange? {
        val target = "\u0631\u0627\u06cc\u0627" // ????
        val tokens = Regex("[\\p{L}\\p{N}]+")
        for (match in tokens.findAll(text)) {
            val token = match.value
            if (token == target || token == target + "\u0647") return match.range
            // ASR can attach a short clitic or one extra character to the wake word.
            if (token.length in 4..6 && editDistanceAtMostOne(token, target)) return match.range
        }
        return null
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++ } else {
                if (++edits > 1) return false
                if (a.length > b.length) i++ else if (b.length > a.length) j++ else { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }

    private fun handleRecognized(raw: String) {
        if (destroyed || busy) return
        val text = normalize(raw)
        if (text.isBlank()) return
        val wake = wakeWordMatch(text)
        Log.d(TAG, "Speech segment chars=${text.length}; wakeDetected=${wake != null}; activeSession=${SystemClock.elapsedRealtime() < activeUntil}")
        if (wake != null) {
            // Each explicit wake starts a distinct local history conversation.
            voiceHistoryId = UUID.randomUUID().toString()
            RayaMemoryStore(this).setProfile("voice_local_history_id", voiceHistoryId)
            activeUntil = SystemClock.elapsedRealtime() + SESSION_MS
            val command = text.substring(wake.last + 1).trim()
            if (command.isBlank()) speak("بله؟ بگو چه کاری انجام بدهم.") else sendToChat(command)
        } else if (SystemClock.elapsedRealtime() < activeUntil) {
            activeUntil = SystemClock.elapsedRealtime() + SESSION_MS
            sendToChat(text)
        }
    }
    private fun loadOfflineTtsAsync() {
        if (offlineTts != null || offlineTtsLoading || destroyed) return
        offlineTtsLoading = true
        Thread {
            try {
                val dir = PersianTtsModel.ensureInstalled(this,
                    { percent -> handler.post { if (!destroyed) updateNotification("در حال دریافت مدل صدای فارسی: $percent٪") } },
                    { destroyed || Thread.currentThread().isInterrupted })
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, "fa_IR-gyro-medium.onnx").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath
                        ),
                        numThreads = 2,
                        debug = false
                    ),
                    maxNumSentences = 1
                )
                val engine = OfflineTts(config = config)
                handler.post {
                    offlineTtsLoading = false
                    if (destroyed) { engine.release(); return@post }
                    offlineTts = engine
                    Log.i(TAG, "Offline Persian TTS ready")
                    pendingSpeech?.let { pendingSpeech = null; playOfflineSpeech(it) }
                    updateNotification("صدای فارسی آفلاین آماده است")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Offline Persian TTS initialization failed: ${e.javaClass.simpleName}")
                handler.post {
                    offlineTtsLoading = false
                    if (!destroyed) {
                        updateNotification("مدل صدای فارسی در دسترس نیست؛ اینترنت و فضای خالی را بررسی کنید")
                        pendingSpeech = null
                        resumeListening()
                    }
                }
            }
        }.apply { name = "raya-persian-tts-loader"; start() }
    }

    private fun playOfflineSpeech(text: String) {
        setListeningCue(false)
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[#*_>|]"), " ").replace(Regex("\\s+"), " ").trim().take(3500)
        if (clean.isBlank()) { resumeListening(); return }
        busy = true
        speechService?.setPause(true)
        if (offlineRecognizer != null) stopSherpaAudio()
        updateNotification("رایا در حال پاسخ‌گویی صوتی است")
        Thread {
            var track: AudioTrack? = null
            try {
                val engine = offlineTts ?: throw IllegalStateException("Offline Persian TTS is not ready")
                val audio = engine.generateWithConfig(clean, GenerationConfig(sid = 0, speed = 1.0f, silenceScale = 0.2f))
                val samples = audio.samples
                val sampleRate = audio.sampleRate
                if (samples.isEmpty() || sampleRate <= 0) throw IllegalStateException("TTS returned empty audio")
                val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) throw IllegalStateException("Audio output is unavailable")
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuffer, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track.play()
                val pcm = ShortArray(4096)
                var offset = 0
                while (offset < samples.size && !destroyed) {
                    val count = minOf(pcm.size, samples.size - offset)
                    for (i in 0 until count) pcm[i] = (samples[offset + i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                    val written = track.write(pcm, 0, count, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw IllegalStateException("AudioTrack write failed: $written")
                    offset += written
                }
                val playbackDeadline = SystemClock.elapsedRealtime() + (samples.size * 1000L / sampleRate) + 5000L
                while (!destroyed && track.playbackHeadPosition.toLong() < samples.size && SystemClock.elapsedRealtime() < playbackDeadline) Thread.sleep(20)
                if (!destroyed) track.stop()
                Log.i(TAG, "Offline Persian TTS playback completed samples=${samples.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Offline Persian TTS playback failed: ${e.javaClass.simpleName}")
            } finally {
                try { track?.release() } catch (_: Exception) {}
                handler.post { if (!destroyed) resumeListening() }
            }
        }.apply { name = "raya-persian-tts-playback"; start() }
    }

    private fun speak(text: String, voiceOverride: String? = null) {
        if (text.isBlank()) { resumeListening(); return }
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[#*_>|]"), " ").replace(Regex("\\s+"), " ").trim().take(3500)
        if (clean.isBlank()) { resumeListening(); return }
        setListeningCue(false)
        when (TtsVoice.fromId(voiceOverride ?: Prefs.ttsVoice(this))) {
            TtsVoice.EDGE_FARID, TtsVoice.EDGE_DILARA -> {
                playEdgeSpeech(clean, voiceOverride ?: Prefs.ttsVoice(this))
                return
            }
            TtsVoice.PIPER -> {
                if (offlineTts != null) playOfflineSpeech(clean)
                else {
                    busy = true
                    speechService?.setPause(true)
                    if (offlineRecognizer != null) stopSherpaAudio()
                    pendingSpeech = clean
                    updateNotification("در حال آماده‌سازی صدای آفلاین")
                    loadOfflineTtsAsync()
                }
                return
            }
            TtsVoice.ANDROID -> {
                if (ttsReady) {
                    busy = true
                    speechService?.setPause(true)
                    if (offlineRecognizer != null) stopSherpaAudio()
                    updateNotification("رایا در حال پاسخ‌گویی است")
                    if (tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()) == TextToSpeech.ERROR)
                        fallbackToOffline(clean)
                } else if (offlineTts != null) {
                    playOfflineSpeech(clean)
                } else {
                    busy = true
                    pendingSpeech = clean
                    loadOfflineTtsAsync()
                    updateNotification("صدای فارسی گوشی موجود نیست؛ آماده‌سازی صدای آفلاین")
                }
                return
            }
        }
    }

    private fun playEdgeSpeech(text: String, voice: String) {
        busy = true
        speechService?.setPause(true)
        if (offlineRecognizer != null) stopSherpaAudio()
        updateNotification("در حال دریافت صدای طبیعی فارسی از Microsoft Edge")
        Thread {
            try {
                val audio = EdgeTtsClient.synthesize(text, voice)
                val file = File(cacheDir, "raya-edge-${UUID.randomUUID()}.mp3")
                file.writeBytes(audio)
                handler.post {
                    if (destroyed) { file.delete(); return@post }
                    try {
                        edgeAudioFile = file
                        val player = MediaPlayer()
                        edgePlayer = player
                        player.setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                        )
                        player.setDataSource(file.absolutePath)
                        player.setOnPreparedListener {
                            updateNotification("رایا در حال صحبت است")
                            it.start()
                        }
                        player.setOnCompletionListener {
                            try { it.release() } catch (_: Exception) {}
                            edgePlayer = null
                            file.delete()
                            edgeAudioFile = null
                            resumeListening()
                        }
                        player.setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "Edge audio playback failed what=$what extra=$extra")
                            fallbackToOffline(text)
                            true
                        }
                        player.prepareAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Edge audio player setup failed: ${e.javaClass.simpleName}")
                        fallbackToOffline(text)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Edge TTS unavailable; using offline fallback (${e.javaClass.simpleName})")
                handler.post { if (!destroyed) fallbackToOffline(text) }
            }
        }.apply { name = "raya-edge-tts"; start() }
    }

    private fun fallbackToOffline(text: String) {
        try { edgePlayer?.release() } catch (_: Exception) {}
        edgePlayer = null
        try { edgeAudioFile?.delete() } catch (_: Exception) {}
        edgeAudioFile = null
        updateNotification("صدای آنلاین در دسترس نیست؛ استفاده از صدای آفلاین")
        if (offlineTts != null) {
            playOfflineSpeech(text)
        } else {
            busy = true
            pendingSpeech = text
            speechService?.setPause(true)
            if (offlineRecognizer != null) stopSherpaAudio()
            loadOfflineTtsAsync()
        }
    }

    private fun setListeningCue(listening: Boolean) {
        if (listeningCueState == listening) return
        val wasListening = listeningCueState
        listeningCueState = listening
        if (wasListening == null && !listening) return
        if (destroyed && listening) return
        try {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 72)
                .also { toneGenerator = it }
            val tone = if (listening) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK
            generator.startTone(tone, if (listening) 115 else 180)
        } catch (e: Exception) {
            Log.w(TAG, "Listening cue unavailable: ${e.javaClass.simpleName}")
        }
    }
    private fun sendToChat(message: String) {
        if (busy) return
        setListeningCue(false)
        busy = true
        speechService?.setPause(true)
        if (offlineRecognizer != null) stopSherpaAudio()
        updateNotification("در حال ارسال درخواست به رایا")
        Thread {
            var answer = ""
            var failure: String? = null
            val memory = RayaMemoryStore(this)
            try {
                memory.addTurn(voiceHistoryId, "user", message)
                val previousTurns = if (conversationId == null) memory.recentTurns(7).dropLast(1).joinToString(" | ") { "${it.role}: ${it.content.take(500)}" } else ""
                val context = memory.personaContext() + if (previousTurns.isBlank()) "" else " Previous voice turns: $previousTurns"
                val contextualMessage = context + " Current user message: " + message
                val body = JSONObject().put("conversationId", conversationId ?: JSONObject.NULL)
                    .put("message", contextualMessage).put("attachmentIds", JSONArray())
                val conn = (URL(Prefs.serverUrl(this) + "/api/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream, application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                Log.i(TAG, "Chat HTTP status=${conn.responseCode}")
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val payload = line.trim().removePrefix("data:").trim()
                        if (payload.isBlank() || payload == "[DONE]") return@forEach
                        val ev = try { JSONObject(payload) } catch (_: Exception) { null }
                        if (ev != null) when (ev.optString("type")) {
                            "status" -> ev.optString("conversationId").takeIf { it.isNotBlank() }?.let {
                                conversationId = it; memory.setProfile("voice_server_conversation_id", it)
                            }
                            "delta" -> answer += ev.optString("text")
                            "done" -> {
                                ev.optString("conversationId").takeIf { it.isNotBlank() }?.let {
                                    conversationId = it; memory.setProfile("voice_server_conversation_id", it)
                                }
                                ev.optJSONObject("assistantMessage")?.optString("content")?.takeIf { it.isNotBlank() }?.let { answer = it }
                            }
                            "error" -> throw Exception(ev.optString("message", "پاسخی دریافت نشد"))
                        }
                    }
                }
                if (answer.isBlank()) throw Exception("پاسخ متنی از سرور دریافت نشد")
                memory.addTurn(voiceHistoryId, "assistant", answer)
            } catch (e: Exception) {
                Log.e(TAG, "Chat request failed: ${e.javaClass.simpleName}")
                failure = e.message ?: "ارتباط با سرور ناموفق بود"
                memory.addTurn(voiceHistoryId, "assistant", "Error: ${e.javaClass.simpleName}")
            }
            handler.post {
                busy = false
                if (destroyed) return@post
                if (failure != null) { activeUntil = 0L; speak("متأسفم. $failure") }
                else { activeUntil = SystemClock.elapsedRealtime() + SESSION_MS; speak(answer) }
            }
        }.apply { name = "raya-chat-request"; start() }
    }

    private fun resumeListening() {
        busy = false
        speechService?.setPause(false)
        if (!destroyed && offlineRecognizer != null) startSherpaAudio()
        else if (!destroyed && speechService != null) setListeningCue(true)
        if (!destroyed) updateNotification("در حال شنیدن؛ برای شروع بگو رایا")
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
        setListeningCue(false)
        try { edgePlayer?.release() } catch (_: Exception) {}
        edgePlayer = null
        try { edgeAudioFile?.delete() } catch (_: Exception) {}
        edgeAudioFile = null
        handler.postDelayed({
            try { toneGenerator?.release() } catch (_: Exception) {}
            toneGenerator = null
        }, 260)
        stopSherpaAudio()
        try { modelLoader?.interrupt() } catch (_: Exception) {}
        try { speechService?.stop(); speechService?.shutdown(); recognizer?.close(); model?.close() } catch (_: Exception) {}
        speechService = null; recognizer = null; model = null
        try { offlineRecognizer?.release() } catch (_: Exception) {}
        offlineRecognizer = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        try { offlineTts?.release() } catch (_: Exception) {}
        offlineTts = null
        super.onDestroy()
    }
}
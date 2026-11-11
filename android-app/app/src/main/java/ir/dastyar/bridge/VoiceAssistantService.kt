package ir.dastyar.bridge

import android.app.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
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
        const val ACTION_INTERRUPT = "ir.dastyar.bridge.voice.INTERRUPT"
        const val ACTION_TEST_TTS = "ir.dastyar.bridge.voice.TEST_TTS"
        const val ACTION_START_CAPTURE = "ir.dastyar.bridge.voice.START_CAPTURE"
        const val ACTION_QUICK_CAPTURE = "ir.dastyar.bridge.voice.QUICK_CAPTURE"
        const val ACTION_STOP_CAPTURE = "ir.dastyar.bridge.voice.STOP_CAPTURE"
        const val ACTION_STATE_CHANGED = "ir.dastyar.bridge.voice.STATE_CHANGED"
        const val EXTRA_TTS_VOICE = "tts_voice"
        private const val CHANNEL = "raya_voice"
        private const val NOTIFICATION_ID = 43
        private const val SESSION_MS = 45_000L
        private const val TAG = "RayaVoice"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val voskRecognizerLock = Any()
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
    @Volatile private var speechPlaybackActive = false
    @Volatile private var speechEpoch = 0L
    @Volatile private var lastBargeInAt = 0L
    @Volatile private var activeSpeechText = ""
    @Volatile private var activeOfflineTrack: AudioTrack? = null
    private var activeUntil = 0L
    // Backend conversation ID and local history ID are deliberately separate.
    private var conversationId: String? = null
    private var voiceHistoryId: String = ""
    private var offlineRecognizer: OfflineRecognizer? = null
    @Volatile private var sherpaRunning = false
    @Volatile private var sherpaProcessing = false
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var speechAudioFocusRequest: android.media.AudioFocusRequest? = null
    @Volatile private var voskAudioRunning = false
    private var voskAudioThread: Thread? = null
    private var sherpaThread: Thread? = null
    @Volatile private var destroyed = false
    @Volatile private var manualCaptureActive = false
    @Volatile private var quickCaptureActive = false
    @Volatile private var quickCaptureAwaitingReply = false

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
        manualCaptureActive = false
        Prefs.setManualCaptureActive(this, false)
        sendBroadcast(Intent(ACTION_STATE_CHANGED))
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
            Prefs.setManualCaptureActive(this, false)
            sendBroadcast(Intent(ACTION_STATE_CHANGED))
            setListeningCue(false)
            Prefs.setVoiceEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_INTERRUPT) {
            interruptSpeechPlayback()
            return START_STICKY
        }
        if (intent?.action == ACTION_QUICK_CAPTURE) {
            Prefs.setVoiceEnabled(this, true)
            quickCaptureActive = true
            quickCaptureAwaitingReply = false
            sendBroadcast(Intent(ACTION_STATE_CHANGED).putExtra("quick_capture_active", true).setPackage(packageName))
            if (recognizer == null && offlineRecognizer == null && modelLoader == null) loadModelAsync()
            startAudioForCurrentMode(forceManual = true)
            updateNotification("رایا در حال شنود فرمان است")
            return START_STICKY
        }
        if (intent?.action == ACTION_START_CAPTURE) {
            Prefs.setVoiceEnabled(this, true)
            manualCaptureActive = true
            Prefs.setManualCaptureActive(this, true)
            sendBroadcast(Intent(ACTION_STATE_CHANGED))
            if (recognizer == null && offlineRecognizer == null && modelLoader == null) loadModelAsync()
            startAudioForCurrentMode(forceManual = true)
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_CAPTURE) {
            stopAudioCapture()
            sendBroadcast(Intent(ACTION_STATE_CHANGED))
            updateNotification("\u0634\u0646\u0648\u062f \u062f\u0633\u062a\u06cc \u0645\u062a\u0648\u0642\u0641 \u0634\u062f")
            return START_STICKY
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
        try { tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) } catch (_: Exception) {}
        if (ttsReady) {
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { id?.let { utterance -> handler.post { utterance.substringAfter("raya-", "").substringBefore("-").toLongOrNull()?.let { resumeListening(it) } } } }
                override fun onDone(id: String?) { id?.let { utterance -> handler.post { utterance.substringAfter("raya-", "").substringBefore("-").toLongOrNull()?.let { resumeListening(it) } } } }
            })
            pendingSpeech?.let { pendingSpeech = null; speak(it) }
        } else {
            updateNotification("آماده‌سازی صدای فارسی آفلاین")
            loadOfflineTtsAsync()
        }
    }

    private fun loadModelAsync() {
        if (modelLoader != null || speechService != null || recognizer != null || offlineRecognizer != null || destroyed) return
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
                    loadedSpeech = null
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
                    startAudioForCurrentMode(forceManual = quickCaptureActive)
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

    /** Tracks the lower energy envelope so steady music is treated as background, not speech. */
    private inner class AdaptiveNoiseGate {
        private val samples = IntArray(50) // about two seconds at 40 ms per frame
        private var size = 0
        private var cursor = 0
        private var floor = 180.0
        private var frames = 0

        fun isSpeech(rms: Int): Boolean {
            samples[cursor] = rms
            cursor = (cursor + 1) % samples.size
            if (size < samples.size) size++
            frames++
            if (size >= 10 && frames % 5 == 0) {
                val sorted = samples.copyOf(size)
                sorted.sort()
                val ambient = sorted[((size - 1) * 15) / 100].toDouble()
                floor = if (ambient > floor) floor * 0.88 + ambient * 0.12
                        else floor * 0.94 + ambient * 0.06
            }
            val musicActive = isMusicPlaybackActive()
            val threshold = maxOf(if (musicActive) 180.0 else 150.0, floor * if (musicActive) 1.5 else 1.3).coerceAtMost(9000.0)
            if (frames % 50 == 0) Log.d(TAG, "Audio gate rms=$rms ambient=${floor.toInt()} threshold=${threshold.toInt()}")
            return rms >= threshold
        }
    }

    private fun isMusicPlaybackActive(): Boolean = try {
        getSystemService(AudioManager::class.java).isMusicActive
    } catch (_: Exception) {
        false
    }

    private fun isStopCommand(raw: String): Boolean {
        val text = normalize(raw)
        if (text.isBlank()) return false
        val phrases = listOf("\u062a\u0648\u0642\u0641", "\u0645\u062a\u0648\u0642\u0641 \u06a9\u0646", "\u0628\u0633 \u06a9\u0646", "\u06a9\u0627\u0641\u06cc\u0647", "\u0642\u0637\u0639 \u06a9\u0646", "\u0633\u0627\u06a9\u062a \u0634\u0648", "stop", "cancel", "pause")
        return phrases.any { text == it || text.endsWith(" $it") }
    }

    private fun isBargeInCandidate(raw: String): Boolean {
        val text = normalize(raw)
        if (text.isBlank()) return false
        val musicActive = isMusicPlaybackActive()
        val wake = wakeWordMatch(text, allowFuzzy = !musicActive)
        if (musicActive) return wake != null && wake.first == 0
        if (!isStopCommand(text)) {
            val spoken = normalize(activeSpeechText)
            if (spoken.isNotBlank() && (spoken.contains(text) || text.contains(spoken))) return false
        }
        if (wake != null || isStopCommand(text)) return true
        val words = text.split(Regex("\\s+")).filter { it.length > 1 }
        return words.size >= 2 && text.length >= 6
    }

    private fun handleBargeInRecognized(raw: String) {
        if (destroyed || !speechPlaybackActive) return
        val text = normalize(raw)
        if (!isBargeInCandidate(text)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBargeInAt < 900L) return
        lastBargeInAt = now
        val wake = wakeWordMatch(text, allowFuzzy = !isMusicPlaybackActive())
        val command = if (wake != null) text.substring(wake.last + 1).trim() else text
        Log.i(TAG, "Barge-in speech recognized chars=" + text.length)
        interruptSpeechPlayback()
        if (!isStopCommand(command) && command.isNotBlank()) {
            handler.postDelayed({ if (!destroyed && !busy) handleRecognized(text) }, 120)
        }
    }

    private fun startAudioForCurrentMode(forceManual: Boolean = false) {
        if (destroyed) return
        val mode = ActivationMode.fromId(Prefs.activationMode(this))
        Log.i(TAG, "Audio mode=$mode voiceEnabled=${Prefs.isVoiceEnabled(this)} manualCapture=$manualCaptureActive forceManual=$forceManual")
        if (mode == ActivationMode.MANUAL && !manualCaptureActive && !forceManual) {
            setListeningCue(false)
            updateNotification("\u062d\u0627\u0644\u062a \u062f\u0633\u062a\u06cc \u0622\u0645\u0627\u062f\u0647 \u0627\u0633\u062a\u061b \u0628\u0631\u0627\u06cc \u0634\u0646\u06cc\u062f\u0646 \u0641\u0631\u0645\u0627\u0646 \u062f\u06a9\u0645\u0647 \u0634\u0631\u0648\u0639 \u0631\u0627 \u0628\u0632\u0646\u06cc\u062f")
            return
        }
        if (mode == ActivationMode.MANUAL) {
            manualCaptureActive = true
            Prefs.setManualCaptureActive(this, true)
            sendBroadcast(Intent(ACTION_STATE_CHANGED))
        }
        if (recognizer != null) startVoskAudio(allowDuringSpeech = speechPlaybackActive)
        else if (offlineRecognizer != null) startSherpaAudio(allowDuringSpeech = speechPlaybackActive)
    }

    private fun stopAudioCapture() {
        manualCaptureActive = false
        Prefs.setManualCaptureActive(this, false)
        sendBroadcast(Intent(ACTION_STATE_CHANGED))
        if (offlineRecognizer != null) stopSherpaAudio()
        if (recognizer != null) {
            voskAudioRunning = false
            val recorder = audioRecord
            audioRecord = null
            try { recorder?.stop() } catch (_: Exception) {}
            val thread = voskAudioThread
            if (thread != null && thread !== Thread.currentThread()) {
                try { thread.join(900) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            voskAudioThread = null
            try { echoCanceler?.release() } catch (_: Exception) {}; echoCanceler = null
            try { noiseSuppressor?.release() } catch (_: Exception) {}; noiseSuppressor = null
            try { automaticGainControl?.release() } catch (_: Exception) {}; automaticGainControl = null
        }
        setListeningCue(false)
    }

    private fun startVoskAudio(allowDuringSpeech: Boolean = false) {
        if (destroyed || (busy && !allowDuringSpeech) || voskAudioRunning || recognizer == null) return
        val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { updateNotification("میکروفون در دسترس نیست"); return }
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 6_400))
        } catch (e: Exception) { updateNotification("راه‌اندازی میکروفون ناموفق بود: ${e.message}"); return }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); return }
        try {
            if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            // AGC disabled intentionally: it can amplify music and trigger false speech gates.
            Log.i(TAG, "Vosk audio effects: AEC=${echoCanceler?.enabled == true}, NS=${noiseSuppressor?.enabled == true}, AGC=${automaticGainControl?.enabled == true}")
        } catch (e: Exception) { Log.w(TAG, "Vosk audio preprocessing unavailable: ${e.javaClass.simpleName}") }
        try { recorder.startRecording() } catch (e: Exception) { recorder.release(); return }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "Vosk microphone did not enter RECORDING state")
            recorder.release()
            updateNotification("Microphone did not start recording")
            return
        }
        Log.i(TAG, "Vosk microphone recording session=${recorder.audioSessionId} source=MIC sampleRate=16000")
        audioRecord = recorder
        voskAudioRunning = true
        setListeningCue(!speechPlaybackActive)
        voskAudioThread = Thread {
            val buffer = ShortArray(640)
            val silence = ShortArray(640)
            val noiseGate = AdaptiveNoiseGate()
            var bargeInFrames = 0
            var consecutiveReadErrors = 0
            var peakRms = 0
            var meterFrames = 0
            while (voskAudioRunning && !destroyed) {
                val count = try { recorder.read(buffer, 0, buffer.size) } catch (e: Exception) { Log.e(TAG, "Vosk microphone read exception: ${e.javaClass.simpleName}"); -1 }
                if (count < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors == 1) Log.w(TAG, "Vosk microphone read error=$count")
                    if (consecutiveReadErrors >= 5) {
                        Log.e(TAG, "Vosk microphone lost; restarting capture")
                        voskAudioRunning = false
                        handler.postDelayed({ if (!destroyed && (!busy || speechPlaybackActive)) restartVoskListening() }, 400)
                        break
                    }
                    Thread.sleep(80)
                    continue
                }
                if (count == 0) continue
                consecutiveReadErrors = 0
                var energy = 0.0
                for (i in 0 until count) energy += buffer[i].toDouble() * buffer[i].toDouble()
                val rms = kotlin.math.sqrt(energy / count).toInt()
                peakRms = maxOf(peakRms, rms)
                meterFrames++
                if (meterFrames >= 50) {
                    Log.d(TAG, "Microphone meter peakRms=$peakRms lastRms=$rms source=MIC")
                    peakRms = 0
                    meterFrames = 0
                }
                if (busy) {
                    if (speechPlaybackActive) {
                        try {
                            val active = recognizer ?: continue
                            val speechFrame = noiseGate.isSpeech(rms)
                            val input = if (speechFrame) buffer else silence
                            val candidate = synchronized(voskRecognizerLock) {
                                val completed = active.acceptWaveForm(input, count)
                                val hypothesis = if (completed) active.result else active.partialResult
                                try {
                                    val json = JSONObject(hypothesis ?: "{}")
                                    json.optString(if (completed) "text" else "partial")
                                } catch (_: Exception) { "" }
                            }
                            if (isBargeInCandidate(candidate)) {
                                bargeInFrames++
                                if (bargeInFrames >= 3) {
                                    bargeInFrames = 0
                                    handler.post { handleBargeInRecognized(candidate) }
                                }
                            } else {
                                bargeInFrames = 0
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Vosk barge-in decode unavailable: " + e.javaClass.simpleName)
                            bargeInFrames = 0
                        }
                    } else {
                        bargeInFrames = 0
                    }
                    continue
                }
                bargeInFrames = 0
                val speechFrame = noiseGate.isSpeech(rms)

                try {
                    val active = recognizer ?: break
                    val recognized = synchronized(voskRecognizerLock) {
                        val completed = if (speechFrame) active.acceptWaveForm(buffer, count)
                        else active.acceptWaveForm(silence, count)
                        if (completed) try { JSONObject(active.result).optString("text") } catch (_: Exception) { "" }
                        else ""
                    }
                    if (recognized.isNotBlank()) {
                        handler.post { if (!destroyed && !busy) handleRecognized(recognized) }
                    }
                } catch (e: Exception) { Log.e(TAG, "Vosk audio decode failed: ${e.javaClass.simpleName}"); break }
            }
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }.apply { name = "raya-vosk-microphone"; start() }
        updateNotification("در حال گوش‌دادن به فرمان صوتی")
    }
    private fun restartVoskListening() {
        if (destroyed || busy || VoiceModel.fromId(Prefs.voiceModel(this)) != VoiceModel.VOSK) return
        try {
            startVoskAudio()
        } catch (_: Exception) {
            handler.postDelayed({ if (!destroyed && !busy) restartVoskListening() }, 1800)
        }
    }

    private fun startSherpaAudio(allowDuringSpeech: Boolean = false) {
        if (destroyed || (busy && !allowDuringSpeech) || sherpaRunning || offlineRecognizer == null) return
        val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { updateNotification("میکروفون این دستگاه در دسترس نیست"); return }
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 6_400))
        } catch (e: Exception) { updateNotification("ساخت ضبط‌کننده صدا ناموفق بود: ${e.message}"); return }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); updateNotification("میکروفون قابل راه‌اندازی نیست"); return
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            // AGC disabled intentionally: it can amplify music and trigger false speech gates.
            Log.i(TAG, "Sherpa audio effects: AEC=${echoCanceler?.enabled == true}, NS=${noiseSuppressor?.enabled == true}, AGC=${automaticGainControl?.enabled == true}")
        } catch (e: Exception) { Log.w(TAG, "Audio preprocessing unavailable: ${e.javaClass.simpleName}") }
        try { recorder.startRecording() } catch (e: Exception) {
            recorder.release(); updateNotification("شروع ضبط صدا ناموفق بود: ${e.message}"); return
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "Sherpa microphone did not enter RECORDING state")
            recorder.release()
            updateNotification("Microphone did not start recording")
            return
        }
        Log.i(TAG, "Sherpa microphone recording session=${recorder.audioSessionId} source=MIC sampleRate=16000")
        audioRecord = recorder
        sherpaRunning = true
        setListeningCue(!speechPlaybackActive)
        sherpaThread = Thread {
            val buffer = ShortArray(640)
            val utterance = ArrayList<Float>(16_000 * 6)
            var speaking = false
            var silenceSamples = 0
            val noiseGate = AdaptiveNoiseGate()
            var consecutiveReadErrors = 0
            var peakRms = 0
            var meterFrames = 0
            while (sherpaRunning && !destroyed) {
                val count = try { recorder.read(buffer, 0, buffer.size) } catch (e: Exception) { Log.e(TAG, "Sherpa microphone read exception: ${e.javaClass.simpleName}"); -1 }
                if (count < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors == 1) Log.w(TAG, "Sherpa microphone read error=$count")
                    if (consecutiveReadErrors >= 5) {
                        Log.e(TAG, "Sherpa microphone lost; restarting capture")
                        sherpaRunning = false
                        handler.postDelayed({ if (!destroyed && (!busy || speechPlaybackActive)) { stopSherpaAudio(); startSherpaAudio(allowDuringSpeech = speechPlaybackActive) } }, 400)
                        break
                    }
                    Thread.sleep(100)
                    continue
                }
                if (count == 0) continue
                consecutiveReadErrors = 0
                if (sherpaProcessing) { utterance.clear(); speaking = false; silenceSamples = 0; continue }
                var energy = 0.0
                for (i in 0 until count) energy += buffer[i].toDouble() * buffer[i].toDouble()
                val rms = kotlin.math.sqrt(energy / count).toInt()
                peakRms = maxOf(peakRms, rms)
                meterFrames++
                if (meterFrames >= 50) {
                    Log.d(TAG, "Microphone meter peakRms=$peakRms lastRms=$rms source=MIC")
                    peakRms = 0
                    meterFrames = 0
                }
                if (busy) {
                    if (speechPlaybackActive && !sherpaProcessing) {
                        if (noiseGate.isSpeech(rms)) {
                            speaking = true
                            silenceSamples = 0
                            for (i in 0 until count) utterance.add(buffer[i] / 32768.0f)
                        } else if (speaking) {
                            silenceSamples += count
                            for (i in 0 until count) utterance.add(buffer[i] / 32768.0f)
                        }
                        if (speaking && (silenceSamples >= 4_800 || utterance.size >= 64_000)) {
                            if (utterance.size >= 4_800) {
                                val audio = FloatArray(utterance.size) { utterance[it] }
                                sherpaProcessing = true
                                Thread { transcribeSherpa(audio, forBargeIn = true) }
                                    .apply { name = "raya-sherpa-barge-in"; start() }
                            }
                            utterance.clear(); speaking = false; silenceSamples = 0
                        }
                    } else {
                        utterance.clear(); speaking = false; silenceSamples = 0
                    }
                    continue
                }
                if (noiseGate.isSpeech(rms)) {
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
        try { echoCanceler?.release() } catch (_: Exception) {}; echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}; noiseSuppressor = null
        try { automaticGainControl?.release() } catch (_: Exception) {}; automaticGainControl = null
        val thread = sherpaThread
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(700) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        sherpaThread = null
    }
    private fun transcribeSherpa(samples: FloatArray, forBargeIn: Boolean = false) {
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
            if (forBargeIn) {
                if (!destroyed && speechPlaybackActive && recognized.isNotBlank()) handleBargeInRecognized(recognized)

            } else if (!destroyed && !busy && recognized.isNotBlank()) handleRecognized(recognized)
            else if (!destroyed && !busy) abandonSpeechAudioFocus()
        }
    }
    private fun normalize(s: String): String = s
        .replace('\u064A', '\u06CC')
        .replace('\u0649', '\u06CC')
        .replace('\u0643', '\u06A9')
        .replace("\u0640", "")
        .replace(Regex("[\\u200B-\\u200F\\uFEFF]"), " ")
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u060C\\u061B\\u061F,.!?;:]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(java.util.Locale.ROOT)

    private fun wakeWordMatch(text: String, allowFuzzy: Boolean = true): IntRange? {
        val target = "\u0631\u0627\u06cc\u0627" // raya
        val aliases = setOf(target, target + "\u0647", target + "\u06cc", "raya", "raia")
        val tokenRegex = Regex("[\\p{L}\\p{N}]+")
        val matches = tokenRegex.findAll(text).toList()
        for (match in matches) {
            val token = match.value
            if (token in aliases) return match.range
            if (allowFuzzy && token.length in 4..6 && editDistanceAtMostOne(token, target)) return match.range
            if (allowFuzzy && token.length in 4..5 && (editDistanceAtMostOne(token, "raya") || editDistanceAtMostOne(token, "raia"))) return match.range
        }

        // Persian ASR can insert spaces inside a short wake word (e.g. "رای ا").
        // Keep offsets into the original transcript so command extraction remains correct.
        val compact = StringBuilder()
        val sourceOffsets = ArrayList<Int>()
        text.forEachIndexed { index, char ->
            if (!char.isWhitespace()) {
                compact.append(char)
                sourceOffsets.add(index)
            }
        }
        val compactText = compact.toString()
        val lengths = if (allowFuzzy) 3..5 else 4..4
        for (length in lengths) {
            if (compactText.length < length) continue
            for (start in 0..(compactText.length - length)) {
                val firstOffset = sourceOffsets[start]
                val lastOffset = sourceOffsets[start + length - 1]
                val startsAtBoundary = firstOffset == 0 || text[firstOffset - 1].isWhitespace()
                val endsAtBoundary = lastOffset == text.lastIndex || text[lastOffset + 1].isWhitespace()
                if (!startsAtBoundary || !endsAtBoundary) continue

                val candidate = compactText.substring(start, start + length)
                val exact = candidate in aliases
                val fuzzy = allowFuzzy && (editDistanceAtMostOne(candidate, target) ||
                    (candidate.length in 4..5 && (editDistanceAtMostOne(candidate, "raya") || editDistanceAtMostOne(candidate, "raia"))))
                if (exact || fuzzy) return firstOffset..lastOffset
            }
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
        if (quickCaptureActive) {
            quickCaptureActive = false
            quickCaptureAwaitingReply = true
            stopAudioCapture()
            sendBroadcast(Intent(ACTION_STATE_CHANGED).putExtra("quick_capture_active", false).setPackage(packageName))
            Log.i(TAG, "Floating Raya button captured one command")
            sendToChat(text)
            return
        }
        val mode = ActivationMode.fromId(Prefs.activationMode(this))
        if (mode == ActivationMode.MANUAL) {
            if (!manualCaptureActive) return
            stopAudioCapture()
            sendToChat(text)
            return
        }
        if (mode == ActivationMode.ALWAYS) {
            sendToChat(text)
            return
        }
        val musicActive = isMusicPlaybackActive()
        val wake = wakeWordMatch(text, allowFuzzy = !musicActive)
        if (musicActive && (wake == null || wake.first != 0)) {
            Log.d(TAG, "Ignoring transcript while media playback is active; explicit wake word required")
            return
        }
        Log.d(TAG, "Speech segment chars=" + text.length + "; wakeDetected=" + (wake != null) + "; musicActive=" + musicActive + "; activeSession=" + (SystemClock.elapsedRealtime() < activeUntil))
        if (wake != null) {
            // A new wake word starts a fresh local and server-side voice conversation.
            voiceHistoryId = UUID.randomUUID().toString()
            RayaMemoryStore(this).setProfile("voice_local_history_id", voiceHistoryId)
            conversationId = null
            RayaMemoryStore(this).setProfile("voice_server_conversation_id", "")
            activeUntil = SystemClock.elapsedRealtime() + SESSION_MS
            val command = text.substring(wake.last + 1).trim()
            if (command.isBlank()) speak("بله، چه کاری می‌توانم انجام دهم؟") else sendToChat(command)
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
        val playbackEpoch = speechEpoch
        requestSpeechAudioFocus()
        setListeningCue(false)
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[#*_>|]"), " ").replace(Regex("\\s+"), " ").trim().take(3500)
        if (clean.isBlank()) { resumeListening(); return }
        activeSpeechText = clean
        busy = true
        speechService?.setPause(true)
        prepareBargeInCapture()
        updateNotification("رایا در حال پاسخ‌گویی صوتی است")
        Thread {
            var track: AudioTrack? = null
            try {
                val engine = offlineTts ?: throw IllegalStateException("Offline Persian TTS is not ready")
                val audio = engine.generateWithConfig(clean, GenerationConfig(sid = 0, speed = 1.0f, silenceScale = 0.2f))
                if (destroyed || !speechPlaybackActive || speechEpoch != playbackEpoch) return@Thread
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
                if (destroyed || !speechPlaybackActive || speechEpoch != playbackEpoch) { track.release(); return@Thread }
                activeOfflineTrack = track
                track.setVolume(1.0f)
                Log.i(TAG, "Offline TTS output trackState=${track.state} volume=1.0 stream=ASSISTANT sampleRate=$sampleRate samples=${samples.size}")
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
                if (activeOfflineTrack === track) activeOfflineTrack = null
                handler.post { if (!destroyed && speechPlaybackActive && speechEpoch == playbackEpoch) resumeListening(playbackEpoch) }
            }
        }.apply { name = "raya-persian-tts-playback"; start() }
    }

    private fun speak(text: String, voiceOverride: String? = null) {
        if (text.isBlank()) { resumeListening(); return }
        speechPlaybackActive = true
        speechEpoch++
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[#*_>|]"), " ").replace(Regex("\\s+"), " ").trim().take(3500)
        if (clean.isBlank()) { resumeListening(); return }
        activeSpeechText = clean
        requestSpeechAudioFocus()
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
                    prepareBargeInCapture()
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
                    prepareBargeInCapture()
                    updateNotification("رایا در حال پاسخ‌گویی است")
                    if (tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, ("raya-$speechEpoch-${UUID.randomUUID()}")) == TextToSpeech.ERROR)
                        fallbackToOffline(clean)
                } else if (offlineTts != null) {
                    playOfflineSpeech(clean)
                } else {
                    busy = true
                    prepareBargeInCapture()
                    pendingSpeech = clean
                    loadOfflineTtsAsync()
                    updateNotification("صدای فارسی گوشی موجود نیست؛ آماده‌سازی صدای آفلاین")
                }
                return
            }
        }
    }

    private fun playEdgeSpeech(text: String, voice: String) {
        val playbackEpoch = speechEpoch
        Log.i(TAG, "Edge TTS requested voice=$voice chars=${text.length}")
        busy = true
        speechService?.setPause(true)
        prepareBargeInCapture()
        updateNotification("در حال دریافت صدای طبیعی فارسی از Microsoft Edge")
        Thread {
            try {
                val audio = EdgeTtsClient.synthesize(text, voice)
                if (destroyed || !speechPlaybackActive || speechEpoch != playbackEpoch) return@Thread
                Log.i(TAG, "Edge TTS synthesis succeeded voice=$voice bytes=${audio.size}")
                val file = File(cacheDir, "raya-edge-${UUID.randomUUID()}.mp3")
                file.writeBytes(audio)
                handler.post {
                    if (destroyed || !speechPlaybackActive || speechEpoch != playbackEpoch) { file.delete(); return@post }
                    try {
                        edgeAudioFile = file
                        val player = MediaPlayer()
                        edgePlayer = player
                        player.setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                        )
                        player.setDataSource(file.absolutePath)
                        player.setVolume(1.0f, 1.0f)
                        player.setOnPreparedListener {
                            if (!speechPlaybackActive || speechEpoch != playbackEpoch) {
                                try { it.release() } catch (_: Exception) {}
                                if (edgePlayer === it) edgePlayer = null
                                file.delete()
                                return@setOnPreparedListener
                            }
                            updateNotification("رایا در حال صحبت است")
                            Log.i(TAG, "Edge TTS output starting volume=1.0 route=ASSISTANT")
                            it.start()
                        }
                        player.setOnCompletionListener {
                            try { it.release() } catch (_: Exception) {}
                            edgePlayer = null
                            file.delete()
                            edgeAudioFile = null
                            resumeListening(playbackEpoch)
                        }
                        player.setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "Edge audio playback failed what=$what extra=$extra")
                            fallbackToOffline(text, playbackEpoch)
                            true
                        }
                        player.prepareAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Edge audio player setup failed: ${e.javaClass.simpleName}")
                        fallbackToOffline(text, playbackEpoch)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Edge TTS unavailable; using offline fallback (${e.javaClass.simpleName})")
                handler.post { if (!destroyed) fallbackToOffline(text, playbackEpoch) }
            }
        }.apply { name = "raya-edge-tts"; start() }
    }

    private fun fallbackToOffline(text: String, playbackEpoch: Long = speechEpoch) {
        if (!speechPlaybackActive || speechEpoch != playbackEpoch) return
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
            prepareBargeInCapture()
            loadOfflineTtsAsync()
        }
    }

    private fun requestSpeechAudioFocus() {
        // Intentionally do not request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK.
        // Raya is mixed with media; an assistant reply must not lower the music volume.
        Log.d(TAG, "Audio focus request skipped: media mixing policy")
    }
    private fun abandonSpeechAudioFocus() {
        if (Build.VERSION.SDK_INT < 26) return
        val request = speechAudioFocusRequest ?: return
        try { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) }
        catch (e: Exception) { Log.w(TAG, "Audio focus release failed: ${e.javaClass.simpleName}") }
        speechAudioFocusRequest = null
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
    private fun prepareBargeInCapture() {
        if (ActivationMode.fromId(Prefs.activationMode(this)) == ActivationMode.MANUAL && !manualCaptureActive) return
        if (recognizer != null && !voskAudioRunning) startVoskAudio(allowDuringSpeech = true)
        else if (offlineRecognizer != null && !sherpaRunning) startSherpaAudio(allowDuringSpeech = true)
    }

    /** Stop current speech immediately when the user starts speaking over it. */
    private fun interruptSpeechPlayback() {
        if (destroyed || !speechPlaybackActive) return
        Log.i(TAG, "Barge-in detected; interrupting TTS playback")
        speechPlaybackActive = false
        activeSpeechText = ""
        speechEpoch++
        try { tts?.stop() } catch (_: Exception) {}
        try { edgePlayer?.stop() } catch (_: Exception) {}
        try { edgePlayer?.release() } catch (_: Exception) {}
        edgePlayer = null
        try { edgeAudioFile?.delete() } catch (_: Exception) {}
        edgeAudioFile = null
        try { activeOfflineTrack?.pause(); activeOfflineTrack?.flush(); activeOfflineTrack?.stop(); activeOfflineTrack?.release() } catch (_: Exception) {}
        activeOfflineTrack = null
        pendingSpeech = null
        busy = false
        abandonSpeechAudioFocus()
        try { synchronized(voskRecognizerLock) { recognizer?.reset() } } catch (_: Exception) {}
        setListeningCue(true)
        updateNotification("Speech interrupted; listening")
    }

    private fun sendToChat(message: String) {
        if (busy) return
        setListeningCue(false)
        busy = true
        speechService?.setPause(true)
        if (offlineRecognizer != null) stopSherpaAudio()
        updateNotification("در حال ارسال درخواست به رایا")
        Thread {
            val requestStartedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "Chat request started chars=${message.length}")
            var answer = ""
            var failure: String? = null
            val memory = RayaMemoryStore(this)
            try {
                memory.addTurn(voiceHistoryId, "user", message)
                val previousTurns = memory.turnsForConversation(voiceHistoryId, 4).dropLast(1).joinToString(" | ") { "${it.role}: ${it.content.take(260)}" }
                val context = memory.personaContext(message) + if (previousTurns.isBlank()) "" else " Previous voice turns: $previousTurns"
                val contextualMessage = context + " Current user message: " + message
                val body = JSONObject().put("conversationId", conversationId ?: JSONObject.NULL)
                    .put("message", contextualMessage).put("attachmentIds", JSONArray())
                var attempt = 0
                while (true) {
                    val conn = (URL(Prefs.serverUrl(this) + "/api/chat").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 120_000; doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "text/event-stream, application/json")
                    }
                    try {
                        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                        val status = conn.responseCode
                        Log.i(TAG, "Chat HTTP status=" + status + " attempt=" + (attempt + 1) + " elapsedMs=" + (SystemClock.elapsedRealtime() - requestStartedAt))
                        if (status !in 200..299) {
                            val retryable = status == 429 || status == 502 || status == 503 || status == 504
                            if (retryable && attempt < 2) {
                                attempt++
                                Thread.sleep(900L * attempt)
                                continue
                            }
                            if (retryable) throw Exception("SERVER_BUSY: The AI server is temporarily busy. Please try again shortly.")
                            throw Exception("HTTP " + status)
                        }

                        var streamError: String? = null
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
                                    "tool" -> ev.optJSONObject("event")?.let { tool ->
                                        Log.i(TAG, "Chat tool event tool=" + tool.optString("tool") + " ok=" + tool.optBoolean("ok") + " durationMs=" + tool.optLong("durationMs"))
                                    }
                                    "done" -> {
                                        ev.optString("conversationId").takeIf { it.isNotBlank() }?.let {
                                            conversationId = it; memory.setProfile("voice_server_conversation_id", it)
                                        }
                                        ev.optJSONObject("assistantMessage")?.optString("content")?.takeIf { it.isNotBlank() }?.let { answer = it }
                                    }
                                    "error" -> streamError = ev.optString("message", "The AI server returned an error")
                                }
                            }
                        }

                        val serverError = streamError
                        if (!serverError.isNullOrBlank()) {
                            val errorText = serverError.lowercase()
                            val providerBusy = serverError.contains("\u0634\u0644\u0648\u063A") || errorText.contains("busy") ||
                                errorText.contains("overload") || errorText.contains("rate limit") ||
                                errorText.contains("temporarily unavailable") || errorText.contains("try again")
                            if (providerBusy && attempt < 2) {
                                attempt++
                                Log.w(TAG, "Chat provider busy event; retry attempt=" + (attempt + 1))
                                Thread.sleep(900L * attempt)
                                answer = ""
                                continue
                            }
                            throw Exception(serverError)
                        }
                        break
                    } finally { conn.disconnect() }
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

    private fun resumeListening(expectedEpoch: Long? = null) {
        if (expectedEpoch != null && expectedEpoch != speechEpoch) return
        if (!speechPlaybackActive && busy) return
        speechPlaybackActive = false
        activeSpeechText = ""
        abandonSpeechAudioFocus()
        busy = false
        speechService?.setPause(false)
        val oneShotFinished = quickCaptureAwaitingReply
        if (oneShotFinished) quickCaptureAwaitingReply = false
        if (!destroyed && !oneShotFinished) startAudioForCurrentMode()
        if (!destroyed) updateNotification("در حال شنیدن؛ برای شروع بگو رایا")
    }
    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "\u0645\u06a9\u0627\u0644\u0645\u0647 \u0635\u0648\u062a\u06cc \u0631\u0627\u06cc\u0627", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app).setContentTitle("\u062f\u0633\u062a\u06cc\u0627\u0631 \u0635\u0648\u062a\u06cc \u0631\u0627\u06cc\u0627")
            .setContentText("\u0622\u0645\u0627\u062f\u0647\u200c\u0633\u0627\u0632\u06cc \u0634\u0646\u0648\u062f \u0641\u0627\u0631\u0633\u06cc\u2026")
            .setOngoing(true).setContentIntent(open)
        if (speechPlaybackActive) {
            val interrupt = PendingIntent.getService(this, 3, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_INTERRUPT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "\u062a\u0648\u0642\u0641 \u0635\u062d\u0628\u062a \u0631\u0627\u06cc\u0627", interrupt)
        }
        if (ActivationMode.fromId(Prefs.activationMode(this)) == ActivationMode.MANUAL) {
            val captureAction = if (manualCaptureActive) ACTION_STOP_CAPTURE else ACTION_START_CAPTURE
            val captureLabel = if (manualCaptureActive) "\u062a\u0648\u0642\u0641 \u0634\u0646\u0648\u062f" else "\u0634\u0631\u0648\u0639 \u0634\u0646\u0648\u062f"
            val capture = PendingIntent.getService(this, 4, Intent(this, VoiceAssistantService::class.java).setAction(captureAction), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_btn_speak_now, captureLabel, capture)
        }
        builder.addAction(android.R.drawable.ic_delete, "\u062a\u0648\u0642\u0641 \u0634\u0646\u0648\u062f", stop)
        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun updateNotification(text: String) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app)
            .setContentTitle("\u062f\u0633\u062a\u06cc\u0627\u0631 \u0635\u0648\u062a\u06cc \u0631\u0627\u06cc\u0627").setContentText(text)
            .setOngoing(true).setContentIntent(open)
        if (speechPlaybackActive) {
            val interrupt = PendingIntent.getService(this, 3, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_INTERRUPT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "\u062a\u0648\u0642\u0641 \u0635\u062d\u0628\u062a \u0631\u0627\u06cc\u0627", interrupt)
        }
        if (ActivationMode.fromId(Prefs.activationMode(this)) == ActivationMode.MANUAL) {
            val captureAction = if (manualCaptureActive) ACTION_STOP_CAPTURE else ACTION_START_CAPTURE
            val captureLabel = if (manualCaptureActive) "\u062a\u0648\u0642\u0641 \u0634\u0646\u0648\u062f" else "\u0634\u0631\u0648\u0639 \u0634\u0646\u0648\u062f"
            val capture = PendingIntent.getService(this, 4, Intent(this, VoiceAssistantService::class.java).setAction(captureAction), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_btn_speak_now, captureLabel, capture)
        }
        builder.addAction(android.R.drawable.ic_delete, "\u062a\u0648\u0642\u0641 \u0634\u0646\u0648\u062f", stop)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    override fun onDestroy() {
        abandonSpeechAudioFocus()
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
        voskAudioRunning = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { voskAudioThread?.join(700) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        voskAudioThread = null
        stopSherpaAudio()
        try { echoCanceler?.release() } catch (_: Exception) {}; echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}; noiseSuppressor = null
        try { automaticGainControl?.release() } catch (_: Exception) {}; automaticGainControl = null
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

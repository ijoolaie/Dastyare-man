package ir.dastyar.bridge

import android.content.Context

object Prefs {
    private const val FILE = "bridge_prefs"
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_VOICE_ENABLED = "voice_enabled"
    private const val KEY_VOICE_MODEL = "voice_model"
    private const val KEY_VOICE_MODEL_MIGRATED = "voice_model_migrated_to_multilingual"
    private const val KEY_TTS_VOICE = "tts_voice"
    private const val KEY_ACTIVATION_MODE = "voice_activation_mode"
    private const val KEY_MANUAL_CAPTURE_ACTIVE = "manual_capture_active"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun serverUrl(c: Context): String = sp(c).getString(KEY_SERVER, "") ?: ""
    fun token(c: Context): String = sp(c).getString(KEY_TOKEN, "") ?: ""
    fun isPaired(c: Context): Boolean = serverUrl(c).isNotEmpty() && token(c).isNotEmpty()
    fun isVoiceEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_VOICE_ENABLED, false)
    fun setVoiceEnabled(c: Context, enabled: Boolean) { sp(c).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply() }
    fun voiceModel(c: Context): String {
        val prefs = sp(c)
        if (!prefs.getBoolean(KEY_VOICE_MODEL_MIGRATED, false)) {
            val stored = prefs.getString(KEY_VOICE_MODEL, null)
            val migrated = if (stored == null || stored == VoiceModel.VOSK.id) VoiceModel.WHISPER.id else stored
            prefs.edit().putString(KEY_VOICE_MODEL, migrated).putBoolean(KEY_VOICE_MODEL_MIGRATED, true).apply()
            return migrated
        }
        return prefs.getString(KEY_VOICE_MODEL, VoiceModel.WHISPER.id) ?: VoiceModel.WHISPER.id
    }
    fun setVoiceModel(c: Context, model: String) { sp(c).edit().putString(KEY_VOICE_MODEL, VoiceModel.fromId(model).id).putBoolean(KEY_VOICE_MODEL_MIGRATED, true).apply() }
    fun activationMode(c: Context): String = ActivationMode.fromId(sp(c).getString(KEY_ACTIVATION_MODE, ActivationMode.WAKE_WORD.id) ?: ActivationMode.WAKE_WORD.id).id
    fun setActivationMode(c: Context, mode: String) { sp(c).edit().putString(KEY_ACTIVATION_MODE, ActivationMode.fromId(mode).id).apply() }
    fun isManualCaptureActive(c: Context): Boolean = sp(c).getBoolean(KEY_MANUAL_CAPTURE_ACTIVE, false)
    fun setManualCaptureActive(c: Context, active: Boolean) { sp(c).edit().putBoolean(KEY_MANUAL_CAPTURE_ACTIVE, active).apply() }
    fun ttsVoice(c: Context): String = sp(c).getString(KEY_TTS_VOICE, TtsVoice.EDGE_FARID.id) ?: TtsVoice.EDGE_FARID.id
    fun setTtsVoice(c: Context, voice: String) { sp(c).edit().putString(KEY_TTS_VOICE, TtsVoice.fromId(voice).id).apply() }

    fun save(c: Context, serverUrl: String, token: String) {
        sp(c).edit().putString(KEY_SERVER, serverUrl.trim().trimEnd('/')).putString(KEY_TOKEN, token.trim()).commit()
    }
    fun clear(c: Context) { sp(c).edit().remove(KEY_SERVER).remove(KEY_TOKEN).putBoolean(KEY_VOICE_ENABLED, false).putBoolean(KEY_MANUAL_CAPTURE_ACTIVE, false).commit() }
}

enum class ActivationMode(val id: String, val title: String) {
    ALWAYS("always", "\u0647\u0645\u06cc\u0634\u0647 \u06af\u0648\u0634 \u0628\u062f\u0647"),
    WAKE_WORD("wake_word", "\u0628\u0627 \u06af\u0641\u062a\u0646 \u0631\u0627\u06cc\u0627 \u0641\u0639\u0627\u0644 \u0634\u0648"),
    MANUAL("manual", "\u0641\u0642\u0637 \u0628\u0627 \u0641\u0634\u0631\u062f\u0646 \u062f\u06a9\u0645\u0647");

    companion object {
        fun fromId(id: String): ActivationMode = values().firstOrNull { it.id == id } ?: WAKE_WORD
    }
}

enum class VoiceModel(val id: String, val title: String) {
    VOSK("vosk", "Vosk فارسی"),
    WHISPER("whisper_tiny", "Whisper Tiny — \u062a\u0634\u062e\u06cc\u0635 \u062e\u0648\u062f\u06a9\u0627\u0631 \u0641\u0627\u0631\u0633\u06cc \u0648 \u0627\u0646\u06af\u0644\u06cc\u0633\u06cc"),
    SHENAVA("shenava_rizeh", "Shenava Rizeh فارسی");

    companion object {
        fun fromId(id: String): VoiceModel = values().firstOrNull { it.id == id } ?: WHISPER
    }
}

enum class TtsVoice(val id: String, val title: String) {
    EDGE_FARID("fa-IR-FaridNeural", "مایکروسافت آنلاین - فرید (مرد)"),
    EDGE_DILARA("fa-IR-DilaraNeural", "مایکروسافت آنلاین - دیلارا (زن)"),
    PIPER("piper", "Piper آفلاین"),
    ANDROID("android", "صدای پیش‌فرض گوشی");

    companion object {
        fun fromId(id: String): TtsVoice = values().firstOrNull { it.id == id } ?: EDGE_FARID
    }
}

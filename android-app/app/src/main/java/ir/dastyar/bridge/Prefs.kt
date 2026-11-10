package ir.dastyar.bridge

import android.content.Context

object Prefs {
    private const val FILE = "bridge_prefs"
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_VOICE_ENABLED = "voice_enabled"
    private const val KEY_VOICE_MODEL = "voice_model"
    private const val KEY_TTS_VOICE = "tts_voice"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun serverUrl(c: Context): String = sp(c).getString(KEY_SERVER, "") ?: ""
    fun token(c: Context): String = sp(c).getString(KEY_TOKEN, "") ?: ""
    fun isPaired(c: Context): Boolean = serverUrl(c).isNotEmpty() && token(c).isNotEmpty()
    fun isVoiceEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_VOICE_ENABLED, false)
    fun setVoiceEnabled(c: Context, enabled: Boolean) { sp(c).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply() }
    fun voiceModel(c: Context): String = sp(c).getString(KEY_VOICE_MODEL, VoiceModel.VOSK.id) ?: VoiceModel.VOSK.id
    fun setVoiceModel(c: Context, model: String) { sp(c).edit().putString(KEY_VOICE_MODEL, VoiceModel.fromId(model).id).apply() }
    fun ttsVoice(c: Context): String = sp(c).getString(KEY_TTS_VOICE, TtsVoice.EDGE_FARID.id) ?: TtsVoice.EDGE_FARID.id
    fun setTtsVoice(c: Context, voice: String) { sp(c).edit().putString(KEY_TTS_VOICE, TtsVoice.fromId(voice).id).apply() }

    fun save(c: Context, serverUrl: String, token: String) {
        sp(c).edit().putString(KEY_SERVER, serverUrl.trim().trimEnd('/')).putString(KEY_TOKEN, token.trim()).apply()
    }
    fun clear(c: Context) { sp(c).edit().remove(KEY_SERVER).remove(KEY_TOKEN).putBoolean(KEY_VOICE_ENABLED, false).apply() }
}

enum class VoiceModel(val id: String, val title: String) {
    VOSK("vosk", "Vosk فارسی"),
    WHISPER("whisper_tiny", "Whisper Tiny چندزبانه"),
    SHENAVA("shenava_rizeh", "Shenava Rizeh فارسی");

    companion object {
        fun fromId(id: String): VoiceModel = values().firstOrNull { it.id == id } ?: VOSK
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

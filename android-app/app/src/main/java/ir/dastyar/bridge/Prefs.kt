package ir.dastyar.bridge

import android.content.Context

/** ذخیره امن اتصال (آدرس سرور و توکن) */
object Prefs {
    private const val FILE = "bridge_prefs"
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "device_token"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(c: Context): String = sp(c).getString(KEY_SERVER, "") ?: ""

    fun token(c: Context): String = sp(c).getString(KEY_TOKEN, "") ?: ""

    fun isPaired(c: Context): Boolean = serverUrl(c).isNotEmpty() && token(c).isNotEmpty()

    fun save(c: Context, serverUrl: String, token: String) {
        sp(c).edit()
            .putString(KEY_SERVER, serverUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun clear(c: Context) {
        sp(c).edit().clear().apply()
    }
}

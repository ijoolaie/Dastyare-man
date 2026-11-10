package ir.dastyar.bridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Small, best-effort client for Microsoft's free Edge Read Aloud endpoint.
 * This is an unofficial consumer endpoint (not a supported Azure API), so
 * callers must provide an offline fallback and handle rate limits / outages.
 */
object EdgeTtsClient {
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val EDGE_VERSION = "155.0.4283.45"
    private const val GEC_VERSION = "1-$EDGE_VERSION"
    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val WSS_URL = "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun synthesize(text: String, voice: String): ByteArray {
        require(text.isNotBlank()) { "متن خالی است" }
        require(voice == "fa-IR-FaridNeural" || voice == "fa-IR-DilaraNeural") {
            "صدای انتخاب‌شده پشتیبانی نمی‌شود"
        }

        val connectionId = randomHex()
        val requestUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.msedgeservices.com")
            .addPathSegments("tts/cognitiveservices/websocket/v1")
            .addQueryParameter("Ocp-Apim-Subscription-Key", TRUSTED_CLIENT_TOKEN)
            .addQueryParameter("ConnectionId", connectionId)
            .addQueryParameter("Sec-MS-GEC", secMsGec())
            .addQueryParameter("Sec-MS-GEC-Version", GEC_VERSION)
            .build()

        val request = Request.Builder()
            .url(requestUrl)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$EDGE_VERSION Safari/537.36 Edg/$EDGE_VERSION"
            )
            .header("Accept-Language", "fa-IR,fa;q=0.9,en;q=0.8")
            .build()

        val opened = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>(null)
        val wsRef = AtomicReference<WebSocket?>(null)

        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.countDown()
                if (!webSocket.send(configMessage()) || !webSocket.send(ssmlMessage(text, voice))) {
                    failure.set(IllegalStateException("ارسال درخواست Edge TTS ناموفق بود"))
                    completed.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, textMessage: String) {
                if (textMessage.contains("Path:turn.end", ignoreCase = true)) {
                    completed.countDown()
                } else if (textMessage.contains("Path:response", ignoreCase = true) &&
                    (textMessage.contains("403") || textMessage.contains("429") || textMessage.contains("Forbidden"))) {
                    failure.set(IllegalStateException("سرویس Edge TTS درخواست را نپذیرفت"))
                    completed.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = bytes.toByteArray()
                if (frame.size < 3) return
                val headerLength = ((frame[0].toInt() and 0xff) shl 8) or (frame[1].toInt() and 0xff)
                val payloadOffset = 2 + headerLength
                if (headerLength <= 0 || payloadOffset > frame.size) return
                val header = String(frame, 2, headerLength, Charsets.UTF_8)
                if (header.contains("Path:audio", ignoreCase = true) && payloadOffset < frame.size) {
                    synchronized(audio) { audio.write(frame, payloadOffset, frame.size - payloadOffset) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                failure.set(IllegalStateException(
                    if (status != null) "اتصال Edge TTS با خطای HTTP $status روبه‌رو شد"
                    else "اتصال Edge TTS برقرار نشد: ${t.javaClass.simpleName}", t
                ))
                opened.countDown()
                completed.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (completed.count > 0) {
                    failure.set(IllegalStateException("اتصال Edge TTS زودتر از موعد بسته شد"))
                    completed.countDown()
                }
            }
        })
        wsRef.set(socket)

        try {
            if (!opened.await(18, TimeUnit.SECONDS)) {
                throw IllegalStateException("مهلت اتصال به Edge TTS تمام شد")
            }
            failure.get()?.let { throw it }
            if (!completed.await(35, TimeUnit.SECONDS)) {
                throw IllegalStateException("پاسخ صوتی Edge TTS به‌موقع دریافت نشد")
            }
            failure.get()?.let { throw it }
            val result = synchronized(audio) { audio.toByteArray() }
            if (result.isEmpty()) throw IllegalStateException("Edge TTS صدایی برنگرداند")
            return result
        } finally {
            try { wsRef.get()?.close(1000, "done") } catch (_: Exception) {}
        }
    }

    private fun configMessage(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"

    private fun ssmlMessage(text: String, voice: String): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xmlns:mstts=\"http://www.w3.org/2001/mstts\" xml:lang=\"fa-IR\">" +
            "<voice name=\"$voice\"><prosody rate=\"+0%\" pitch=\"+0Hz\" volume=\"+0%\">" +
            "$safe</prosody></voice></speak>"
        return "X-RequestId:${randomHex()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n" + ssml
    }

    private fun secMsGec(): String {
        val windowsEpochOffset = 11644473600.0
        var ticks = System.currentTimeMillis() / 1000.0 + windowsEpochOffset
        ticks -= ticks % 300.0
        ticks *= 10_000_000.0
        val ticksString = String.format(Locale.ROOT, "%.0f", ticks)
        val input = (ticksString + TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { String.format(Locale.ROOT, "%02X", it) }
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private fun randomHex(): String = UUID.randomUUID().toString().replace("-", "")
}
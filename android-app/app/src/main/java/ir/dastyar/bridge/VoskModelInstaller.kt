package ir.dastyar.bridge

import android.content.Context
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object VoskModelInstaller {
    private const val NAME = "vosk-model-small-fa-0.42"
    private const val URL_MODEL = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"
    private const val SHA256 = "977CB5FAA538F3A835CCFD35F5F6D8284B5C450B89C700B9BD4736B66536AD46"
    private const val SIZE = 53_431_220L

    fun ensureModel(context: Context, progress: (Int) -> Unit, cancelled: () -> Boolean): File {
        val modelDir = File(context.filesDir, NAME)
        if (ready(modelDir)) return modelDir
        val zip = File(context.cacheDir, "$NAME.zip.part")
        zip.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "DastyarMan-Android")
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw IOException("Model download HTTP " + conn.responseCode)
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else SIZE
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var last = -1
                    while (true) {
                        if (cancelled()) throw InterruptedException("Download cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        received += n
                        val percent = ((received * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != last && (percent % 5 == 0 || percent == 100)) {
                            last = percent
                            progress(percent)
                        }
                    }
                }
            }
        } finally { conn.disconnect() }

        val actual = digest.digest().joinToString("") { "%02X".format(it.toInt() and 0xff) }
        if (!actual.equals(SHA256, true)) {
            zip.delete()
            throw IOException("Model checksum mismatch")
        }

        val staging = File(context.filesDir, "$NAME.extracting")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot create model staging directory")
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
                val buffer = ByteArray(64 * 1024)
                var expanded = 0L
                var entries = 0
                while (true) {
                    if (cancelled()) throw InterruptedException("Extraction cancelled")
                    val entry = input.nextEntry ?: break
                    if (++entries > 3000) throw IOException("Invalid model archive")
                    val target = File(staging, entry.name)
                    val prefix = staging.canonicalPath + File.separator
                    if (!target.canonicalPath.startsWith(prefix)) throw IOException("Invalid archive path")
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                expanded += n
                                if (expanded > 250_000_000L) throw IOException("Model archive too large")
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    input.closeEntry()
                }
            }
            val extracted = File(staging, NAME)
            if (!ready(extracted)) throw IOException("Persian model is incomplete")
            modelDir.deleteRecursively()
            if (!extracted.renameTo(modelDir)) extracted.copyRecursively(modelDir, overwrite = true)
        } finally {
            staging.deleteRecursively()
            zip.delete()
        }
        if (!ready(modelDir)) throw IOException("Model installation failed")
        return modelDir
    }

    private fun ready(dir: File) =
        File(dir, "am/final.mdl").isFile &&
        File(dir, "conf/model.conf").isFile &&
        File(dir, "graph/Gr.fst").isFile
}
package ir.dastyar.bridge

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object PersianTtsModel {
    private const val MODEL_DIR = "vits-piper-fa_IR-gyro-medium"
    private const val ARCHIVE_NAME = "$MODEL_DIR.tar.bz2"
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$ARCHIVE_NAME"
    private const val SHA256 = "75dc485f0c0528b1b07800464e5c72f4229339919525b3f634aa89690988d30e"
    private const val EXPECTED_BYTES = 67_183_308L

    fun ensureInstalled(
        context: Context,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val root = File(context.filesDir, "persian-tts")
        val model = File(root, MODEL_DIR)
        if (isInstalled(model)) return model
        if (!root.exists() && !root.mkdirs()) error("Cannot create TTS model directory")

        val archive = File(root, ARCHIVE_NAME)
        if (!archive.exists() || archive.length() != EXPECTED_BYTES || sha256(archive) != SHA256) {
            if (archive.exists()) archive.delete()
            val temp = File(root, "$ARCHIVE_NAME.part")
            if (temp.exists()) temp.delete()
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Dastyare-man-Android")
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) error("TTS model download HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: EXPECTED_BYTES
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            if (isCancelled()) error("TTS model download cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            onProgress(((received * 100) / total).toInt().coerceIn(0, 99))
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (temp.length() != EXPECTED_BYTES || sha256(temp) != SHA256) {
                temp.delete()
                error("TTS model integrity check failed")
            }
            if (!temp.renameTo(archive)) {
                temp.copyTo(archive, overwrite = true)
                temp.delete()
            }
        }

        val staging = File(root, "$MODEL_DIR.tmp")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) error("Cannot create TTS extraction directory")
        try {
            FileInputStream(archive).use { fileInput ->
                BufferedInputStream(fileInput).use { buffered ->
                    BZip2CompressorInputStream(buffered, true).use { bz2 ->
                        TarArchiveInputStream(bz2).use { tar ->
                            while (true) {
                                if (isCancelled()) error("TTS model extraction cancelled")
                                val entry = tar.nextTarEntry ?: break
                                val output = File(staging, entry.name)
                                val canonicalRoot = staging.canonicalPath + File.separator
                                if (!output.canonicalPath.startsWith(canonicalRoot)) error("Unsafe TTS archive entry")
                                if (entry.isDirectory) {
                                    if (!output.exists() && !output.mkdirs()) error("Cannot create TTS model folder")
                                } else if (entry.isFile) {
                                    output.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Cannot create TTS model folder") }
                                    FileOutputStream(output).use { stream ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val count = tar.read(buffer)
                                            if (count < 0) break
                                            stream.write(buffer, 0, count)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val extractedModel = File(staging, MODEL_DIR)
            if (!isInstalled(extractedModel)) error("Downloaded TTS model is incomplete")
            if (model.exists()) model.deleteRecursively()
            if (!extractedModel.renameTo(model)) extractedModel.copyRecursively(model, overwrite = true)
            archive.delete()
            onProgress(100)
            return model
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun isInstalled(dir: File): Boolean =
        File(dir, "fa_IR-gyro-medium.onnx").isFile &&
            File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data").isDirectory

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

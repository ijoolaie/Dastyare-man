package ir.dastyar.bridge

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object VoiceModelInstaller {
    private const val WHISPER_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/"
    private const val SHENAVA_BASE = "https://huggingface.co/Reza2kn/Shenava-Rizeh-v1.0-sherpa-onnx/resolve/main/"

    fun createSherpaRecognizer(context: Context, model: VoiceModel, progress: (String) -> Unit, cancelled: () -> Boolean = { false }): OfflineRecognizer {
        val root = File(context.filesDir, "asr-models")
        if (!root.exists()) root.mkdirs()
        val config = when (model) {
            VoiceModel.WHISPER -> {
                val dir = File(root, "whisper-tiny")
                dir.mkdirs()
                val encoder = ensureFile(File(dir, "tiny-encoder.int8.onnx"), WHISPER_BASE + "tiny-encoder.int8.onnx", 1_000_000, progress, cancelled)
                val decoder = ensureFile(File(dir, "tiny-decoder.int8.onnx"), WHISPER_BASE + "tiny-decoder.int8.onnx", 10_000_000, progress, cancelled)
                val tokens = ensureFile(File(dir, "tiny-tokens.txt"), WHISPER_BASE + "tiny-tokens.txt", 100_000, progress, cancelled)
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(encoder = encoder.absolutePath, decoder = decoder.absolutePath, language = "", task = "transcribe"),
                    tokens = tokens.absolutePath, modelType = "whisper", numThreads = 2, provider = "cpu"
                )
            }
            VoiceModel.SHENAVA -> {
                val dir = File(root, "shenava-rizeh")
                dir.mkdirs()
                val modelFile = ensureFile(File(dir, "model.onnx"), SHENAVA_BASE + "model.onnx", 10_000_000, progress, cancelled)
                val tokens = ensureFile(File(dir, "tokens.txt"), SHENAVA_BASE + "tokens.txt", 10_000, progress, cancelled)
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath),
                    tokens = tokens.absolutePath, numThreads = 2, provider = "cpu"
                )
            }
            else -> throw IllegalArgumentException("Vosk does not use sherpa-onnx")
        }
        progress("در حال آماده‌سازی موتور تشخیص صدا")
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = config, decodingMethod = "greedy_search"))
    }

    private fun ensureFile(file: File, url: String, minimumBytes: Long, progress: (String) -> Unit, cancelled: () -> Boolean): File {
        if (file.exists() && file.length() >= minimumBytes) return file
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        if (part.exists()) part.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Dastyare-man-Android")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("Model download HTTP ${connection.responseCode}")
            val expected = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (cancelled()) throw InterruptedException("Model download cancelled")
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        total += read
                        if (expected > 0) progress("دریافت ${file.name}: ${(total * 100 / expected).toInt()}٪")
                    }
                }
            }
            if (part.length() < minimumBytes || (expected > 0 && part.length() != expected))
                throw IllegalStateException("Downloaded model file is incomplete: ${file.name}")
            if (file.exists()) file.delete()
            if (!part.renameTo(file)) throw IllegalStateException("Cannot install model file: ${file.name}")
            return file
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}

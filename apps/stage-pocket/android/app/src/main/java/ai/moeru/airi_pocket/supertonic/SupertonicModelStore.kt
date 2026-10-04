package ai.moeru.airi_pocket.supertonic

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Supertonic 3 model files in app-private storage.
 *
 * Layout mirrors the Hugging Face repo:
 *   <filesDir>/supertonic/onnx/{*.onnx, tts.json, unicode_indexer.json}
 *   <filesDir>/supertonic/voice_styles/{M1..M5,F1..F5}.json
 *
 * Downloads resume with HTTP Range, retry per file, check sizes, and publish atomically
 * (`*.part` then rename). Ported from supertonic-android `ModelAssets` and `ModelDownloader`.
 */
class SupertonicModelStore(context: Context) {

    /** A remote file. [size] is the expected byte length, or -1 when unknown (small JSON). */
    data class Asset(val path: String, val size: Long)

    /** Cumulative progress across all files. */
    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val currentPath: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    private val baseDir = File(context.filesDir, "supertonic")
    private val cancelled = AtomicBoolean(false)

    val onnxDir: File get() = File(baseDir, "onnx")

    fun voiceStyleFile(name: String): File = File(baseDir, "voice_styles/$name.json")

    /** True only when every asset exists and matches its known size. */
    fun isReady(): Boolean = ALL.all { isComplete(it) }

    /** Bytes already on disk for known-size assets. */
    fun downloadedBytes(): Long = ALL.sumOf { asset ->
        val file = localFile(asset)
        when {
            isComplete(asset) -> knownSize(asset)
            else -> File(file.parentFile, file.name + ".part").takeIf(File::exists)?.length() ?: 0L
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    /** Blocks the calling thread until all assets are present. Throws on failure or cancel. */
    fun download(onProgress: (Progress) -> Unit) {
        cancelled.set(false)
        File(baseDir, "onnx").mkdirs()
        File(baseDir, "voice_styles").mkdirs()

        var cumulativeBefore = 0L
        ALL.forEachIndexed { index, asset ->
            val finalFile = localFile(asset)
            if (isComplete(asset)) {
                cumulativeBefore += knownSize(asset)
                onProgress(Progress(index, ALL.size, asset.path, cumulativeBefore, KNOWN_TOTAL_BYTES))
                return@forEachIndexed
            }

            val base = cumulativeBefore
            downloadOne(asset, finalFile) { fileBytes ->
                onProgress(Progress(index, ALL.size, asset.path, base + fileBytes, KNOWN_TOTAL_BYTES))
            }
            cumulativeBefore += knownSize(asset)
        }
    }

    fun deleteAll() {
        baseDir.deleteRecursively()
    }

    private fun localFile(asset: Asset): File = File(baseDir, asset.path)

    private fun isComplete(asset: Asset): Boolean {
        val file = localFile(asset)
        return file.exists() && (asset.size < 0 || file.length() == asset.size)
    }

    private fun knownSize(asset: Asset): Long = if (asset.size > 0) asset.size else 0L

    private fun downloadOne(asset: Asset, finalFile: File, onBytes: (Long) -> Unit) {
        val part = File(finalFile.parentFile, finalFile.name + ".part")
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                transfer(asset, part, onBytes)
                if (asset.size >= 0 && part.length() != asset.size) {
                    throw IllegalStateException(
                        "Size mismatch for ${asset.path}: got ${part.length()}, expected ${asset.size}",
                    )
                }
                if (finalFile.exists()) finalFile.delete()
                if (!part.renameTo(finalFile)) {
                    throw IllegalStateException("Failed to publish ${finalFile.name}")
                }
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (cancelled.get()) throw e
                if (attempt == MAX_ATTEMPTS - 1) part.delete()
            }
        }
        throw lastError ?: IllegalStateException("Download failed: ${asset.path}")
    }

    private fun transfer(asset: Asset, part: File, onBytes: (Long) -> Unit) {
        val existing = if (part.exists()) part.length() else 0L
        val conn = (URL(HF_BASE + asset.path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "airi-pocket")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            conn.connect()
            val code = conn.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                throw IllegalStateException("HTTP $code for ${asset.path}")
            }
            // If the server ignored the Range header, restart from the beginning.
            val startFrom = if (resuming) existing else 0L

            FileOutputStream(part, resuming).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var fileBytes = startFrom
                    var sinceEmit = 0L
                    while (true) {
                        if (cancelled.get()) throw IllegalStateException("Download cancelled")
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        fileBytes += read
                        sinceEmit += read
                        if (sinceEmit >= EMIT_EVERY_BYTES) {
                            onBytes(fileBytes)
                            sinceEmit = 0L
                        }
                    }
                    onBytes(fileBytes)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val HF_BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/main/"

        val VOICE_NAMES: List<String> = listOf("M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5")

        private val ONNX: List<Asset> = listOf(
            Asset("onnx/duration_predictor.onnx", 3_700_147),
            Asset("onnx/text_encoder.onnx", 36_416_150),
            Asset("onnx/vector_estimator.onnx", 256_534_781),
            Asset("onnx/vocoder.onnx", 101_424_195),
            Asset("onnx/tts.json", 8_253),
            Asset("onnx/unicode_indexer.json", 277_676),
        )

        private val ALL: List<Asset> = ONNX + VOICE_NAMES.map { Asset("voice_styles/$it.json", -1) }

        val KNOWN_TOTAL_BYTES: Long = ALL.sumOf { if (it.size > 0) it.size else 0L }

        private const val MAX_ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val EMIT_EVERY_BYTES = 512L * 1024
    }
}

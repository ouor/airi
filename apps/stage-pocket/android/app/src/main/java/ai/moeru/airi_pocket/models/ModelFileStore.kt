package ai.moeru.airi_pocket.models

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a set of model files that download once into app-private storage.
 *
 * Each asset downloads from `baseUrl + asset.path` to `rootDir/asset.path`.
 * Downloads resume with HTTP Range, retry per file, check sizes, and publish atomically
 * (`*.part` then rename).
 */
class ModelFileStore(
    val rootDir: File,
    private val baseUrl: String,
    private val assets: List<Asset>,
) {

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

    private val cancelled = AtomicBoolean(false)

    val totalBytes: Long = assets.sumOf(::knownSize)

    fun file(path: String): File = File(rootDir, path)

    /** True only when every asset exists and matches its known size. */
    fun isReady(): Boolean = assets.all(::isComplete)

    /** Bytes already on disk for known-size assets. */
    fun downloadedBytes(): Long = assets.sumOf { asset ->
        val file = file(asset.path)
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

        var cumulativeBefore = 0L
        assets.forEachIndexed { index, asset ->
            val finalFile = file(asset.path)
            finalFile.parentFile?.mkdirs()
            if (isComplete(asset)) {
                cumulativeBefore += knownSize(asset)
                onProgress(Progress(index, assets.size, asset.path, cumulativeBefore, totalBytes))
                return@forEachIndexed
            }

            val base = cumulativeBefore
            downloadOne(asset, finalFile) { fileBytes ->
                onProgress(Progress(index, assets.size, asset.path, base + fileBytes, totalBytes))
            }
            cumulativeBefore += knownSize(asset)
        }
    }

    fun deleteAll() {
        rootDir.deleteRecursively()
    }

    private fun isComplete(asset: Asset): Boolean {
        val file = file(asset.path)
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
        val conn = (URL(baseUrl + asset.path).openConnection() as HttpURLConnection).apply {
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

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_SIZE = 64 * 1024
        const val EMIT_EVERY_BYTES = 512L * 1024
    }
}

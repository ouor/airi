package ai.moeru.airi_pocket

import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import ai.moeru.airi_pocket.supertonic.Languages
import ai.moeru.airi_pocket.supertonic.SupertonicModelStore
import ai.moeru.airi_pocket.supertonic.SupertonicTts
import ai.moeru.airi_pocket.supertonic.VoiceStyle

/**
 * Runs the Supertonic 3 TTS model on the device with ONNX Runtime.
 *
 * The web layer calls `synthesize` and receives a base64 16-bit PCM WAV.
 * Model files download once from Hugging Face into app-private storage.
 */
@CapacitorPlugin(name = "SupertonicTts")
class SupertonicTtsPlugin : Plugin() {
    private val store by lazy { SupertonicModelStore(context) }

    /** One thread keeps ONNX sessions and voice styles away from concurrent access. */
    private val synthesisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val downloading = AtomicBoolean(false)

    private var engine: SupertonicTts? = null
    private val styles = HashMap<String, VoiceStyle>()

    @PluginMethod
    fun getStatus(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("ready", store.isReady())
            put("loaded", engine != null)
            put("downloading", downloading.get())
            put("downloadedBytes", store.downloadedBytes())
            put("totalBytes", SupertonicModelStore.KNOWN_TOTAL_BYTES)
        })
    }

    @PluginMethod
    fun listVoices(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("voices", JSArray(SupertonicModelStore.VOICE_NAMES))
            put("languages", JSArray(Languages.AVAILABLE))
        })
    }

    /** Downloads missing model files. Emits `downloadProgress` events while it runs. */
    @PluginMethod
    fun downloadModels(call: PluginCall) {
        if (!downloading.compareAndSet(false, true)) {
            call.reject("A model download is already running")
            return
        }

        downloadExecutor.execute {
            try {
                var lastPercent = -1
                store.download { progress ->
                    val percent = if (progress.totalBytes > 0) (progress.downloadedBytes * 100 / progress.totalBytes).toInt() else 0
                    if (percent == lastPercent) return@download
                    lastPercent = percent
                    notifyListeners("downloadProgress", JSObject().apply {
                        put("file", progress.currentPath)
                        put("fileIndex", progress.fileIndex)
                        put("fileCount", progress.fileCount)
                        put("downloadedBytes", progress.downloadedBytes)
                        put("totalBytes", progress.totalBytes)
                        put("percent", percent)
                    })
                }
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: e.javaClass.simpleName, e)
            } finally {
                downloading.set(false)
            }
        }
    }

    @PluginMethod
    fun cancelDownload(call: PluginCall) {
        store.cancel()
        call.resolve()
    }

    @PluginMethod
    fun synthesize(call: PluginCall) {
        val text = call.getString("text")?.trim().orEmpty()
        val voice = call.getString("voice") ?: SupertonicModelStore.VOICE_NAMES.first()
        val lang = call.getString("lang") ?: "ko"
        val speed = call.getFloat("speed") ?: 1.05f
        val steps = call.getInt("steps") ?: 8

        if (text.isEmpty()) {
            call.reject("Text is empty")
            return
        }
        if (voice !in SupertonicModelStore.VOICE_NAMES) {
            call.reject("Unknown voice: $voice")
            return
        }
        if (!Languages.isValid(lang)) {
            call.reject("Unsupported language: $lang")
            return
        }

        synthesisExecutor.execute {
            try {
                if (!store.isReady()) {
                    call.reject("Supertonic models are not downloaded")
                    return@execute
                }

                val tts = engine ?: SupertonicTts.load(store.onnxDir).also { engine = it }
                val style = styles.getOrPut(voice) { VoiceStyle.load(store.voiceStyleFile(voice)) }
                val result = tts.synthesize(text, lang, style, totalStep = steps, speed = speed)

                call.resolve(JSObject().apply {
                    put("audio", Base64.encodeToString(encodeWav(result.wav, tts.sampleRate), Base64.NO_WRAP))
                    put("sampleRate", tts.sampleRate)
                    put("duration", result.duration[0].toDouble())
                })
            } catch (e: Throwable) {
                call.reject(e.message ?: e.javaClass.simpleName, e as? Exception)
            }
        }
    }

    /** Frees the ONNX sessions. The next `synthesize` call loads them again. */
    @PluginMethod
    fun unload(call: PluginCall) {
        synthesisExecutor.execute {
            releaseEngine()
            call.resolve()
        }
    }

    @PluginMethod
    fun deleteModels(call: PluginCall) {
        synthesisExecutor.execute {
            releaseEngine()
            store.deleteAll()
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        store.cancel()
        synthesisExecutor.execute(::releaseEngine)
        synthesisExecutor.shutdown()
        downloadExecutor.shutdown()
        super.handleOnDestroy()
    }

    private fun releaseEngine() {
        engine?.close()
        engine = null
        styles.clear()
    }

    /** Encodes mono float32 PCM as a 16-bit little-endian WAV file. */
    private fun encodeWav(pcm: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        out.write(header.array())

        val samples = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) {
            samples.putShort((sample * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        out.write(samples.array())
        return out.toByteArray()
    }
}

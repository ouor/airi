package ai.moeru.airi_pocket

import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import ai.moeru.airi_pocket.asr.SherpaAsrModel
import ai.moeru.airi_pocket.models.ModelFileStore

/**
 * Runs offline speech recognition with sherpa-onnx on the device.
 *
 * The web layer sends one finished utterance as base64 16-bit PCM and receives its text.
 * Model files download once from Hugging Face into app-private storage.
 */
@CapacitorPlugin(name = "SherpaAsr")
class SherpaAsrPlugin : Plugin() {
    private val stores = HashMap<SherpaAsrModel, ModelFileStore>()

    /** One thread keeps the native recognizer away from concurrent access. */
    private val recognitionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val downloadingModel = AtomicReference<SherpaAsrModel?>(null)

    private var recognizer: OfflineRecognizer? = null
    private var recognizerKey: String? = null

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val model = resolveModel(call) ?: return
        val store = store(model)
        call.resolve(JSObject().apply {
            put("ready", store.isReady())
            put("loaded", recognizerKey?.startsWith(model.id) == true)
            put("downloading", downloadingModel.get() == model)
            put("downloadedBytes", store.downloadedBytes())
            put("totalBytes", store.totalBytes)
        })
    }

    /** Downloads missing model files. Emits `downloadProgress` events while it runs. */
    @PluginMethod
    fun downloadModel(call: PluginCall) {
        val model = resolveModel(call) ?: return
        if (!downloadingModel.compareAndSet(null, model)) {
            call.reject("A model download is already running")
            return
        }

        downloadExecutor.execute {
            try {
                var lastPercent = -1
                store(model).download { progress ->
                    val percent = if (progress.totalBytes > 0) (progress.downloadedBytes * 100 / progress.totalBytes).toInt() else 0
                    if (percent == lastPercent) return@download
                    lastPercent = percent
                    notifyListeners("downloadProgress", JSObject().apply {
                        put("model", model.id)
                        put("file", progress.currentPath)
                        put("downloadedBytes", progress.downloadedBytes)
                        put("totalBytes", progress.totalBytes)
                        put("percent", percent)
                    })
                }
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: e.javaClass.simpleName, e)
            } finally {
                downloadingModel.set(null)
            }
        }
    }

    @PluginMethod
    fun cancelDownload(call: PluginCall) {
        downloadingModel.get()?.let { store(it).cancel() }
        call.resolve()
    }

    @PluginMethod
    fun deleteModel(call: PluginCall) {
        val model = resolveModel(call) ?: return
        recognitionExecutor.execute {
            if (recognizerKey?.startsWith(model.id) == true) releaseRecognizer()
            store(model).deleteAll()
            call.resolve()
        }
    }

    /**
     * Recognizes one utterance.
     *
     * `audio` is base64 little-endian 16-bit mono PCM at `sampleRate` hertz.
     */
    @PluginMethod
    fun transcribe(call: PluginCall) {
        val model = resolveModel(call) ?: return
        val language = call.getString("language") ?: "ko"
        val sampleRate = call.getInt("sampleRate") ?: 16_000
        val audio = call.getString("audio")
        if (audio.isNullOrEmpty()) {
            call.reject("Audio is empty")
            return
        }

        recognitionExecutor.execute {
            try {
                val store = store(model)
                if (!store.isReady()) {
                    call.reject("The ${model.id} model files are not downloaded")
                    return@execute
                }

                val samples = decodePcm16(Base64.decode(audio, Base64.DEFAULT))
                val recognizer = recognizer(model, store, language)
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    call.resolve(JSObject().apply {
                        put("text", result.text.trim())
                        put("language", result.lang)
                        put("duration", samples.size.toDouble() / sampleRate)
                    })
                } finally {
                    stream.release()
                }
            } catch (e: Throwable) {
                call.reject(e.message ?: e.javaClass.simpleName, e as? Exception)
            }
        }
    }

    /** Frees the native recognizer. The next `transcribe` call loads it again. */
    @PluginMethod
    fun unload(call: PluginCall) {
        recognitionExecutor.execute {
            releaseRecognizer()
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        downloadingModel.get()?.let { store(it).cancel() }
        recognitionExecutor.execute(::releaseRecognizer)
        recognitionExecutor.shutdown()
        downloadExecutor.shutdown()
        super.handleOnDestroy()
    }

    private fun resolveModel(call: PluginCall): SherpaAsrModel? {
        val id = call.getString("model") ?: SherpaAsrModel.SenseVoice.id
        val model = SherpaAsrModel.fromId(id)
        if (model == null) call.reject("Unknown model: $id")
        return model
    }

    private fun store(model: SherpaAsrModel): ModelFileStore =
        synchronized(stores) { stores.getOrPut(model) { model.createStore(context) } }

    private fun recognizer(model: SherpaAsrModel, store: ModelFileStore, language: String): OfflineRecognizer {
        val key = "${model.id}:$language"
        recognizer?.takeIf { recognizerKey == key }?.let { return it }

        releaseRecognizer()
        val config = OfflineRecognizerConfig(modelConfig = model.modelConfig(store.rootDir, language))
        return OfflineRecognizer(config = config).also {
            recognizer = it
            recognizerKey = key
        }
    }

    private fun releaseRecognizer() {
        recognizer?.release()
        recognizer = null
        recognizerKey = null
    }

    private fun decodePcm16(bytes: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }
}

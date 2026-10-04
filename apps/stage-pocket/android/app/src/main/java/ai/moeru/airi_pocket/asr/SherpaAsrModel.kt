package ai.moeru.airi_pocket.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

import ai.moeru.airi_pocket.models.ModelFileStore
import ai.moeru.airi_pocket.models.ModelFileStore.Asset

/**
 * Offline (non-streaming) sherpa-onnx models that recognize Korean.
 * Configurations follow `sherpa-onnx/kotlin-api/OfflineRecognizer.kt` (types 13, 15, and 61).
 */
enum class SherpaAsrModel(
    val id: String,
    private val repository: String,
    private val assets: List<Asset>,
) {
    KoreanZipformer(
        id = "korean-zipformer",
        repository = "k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24",
        assets = listOf(
            Asset("encoder-epoch-99-avg-1.int8.onnx", 70_784_728),
            Asset("decoder-epoch-99-avg-1.onnx", 11_309_084),
            Asset("joiner-epoch-99-avg-1.int8.onnx", 2_581_421),
            Asset("tokens.txt", 60_246),
        ),
    ) {
        override fun modelConfig(dir: File, language: String) = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                decoder = File(dir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            ),
            tokens = File(dir, "tokens.txt").absolutePath,
            modelType = "transducer",
            numThreads = NUM_THREADS,
        )
    },

    SenseVoice(
        id = "sense-voice",
        repository = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        assets = listOf(
            Asset("model.int8.onnx", 239_233_841),
            Asset("tokens.txt", 315_894),
        ),
    ) {
        override fun modelConfig(dir: File, language: String) = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = File(dir, "model.int8.onnx").absolutePath,
                // An empty language lets SenseVoice detect it.
                language = language.takeIf { it in SENSE_VOICE_LANGUAGES }.orEmpty(),
                useInverseTextNormalization = true,
            ),
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = NUM_THREADS,
        )
    },

    Qwen3Asr(
        id = "qwen3-asr",
        repository = "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        assets = listOf(
            Asset("conv_frontend.onnx", 44_148_281),
            Asset("encoder.int8.onnx", 182_491_662),
            Asset("decoder.int8.onnx", 755_914_231),
            Asset("tokenizer/merges.txt", 1_671_853),
            Asset("tokenizer/tokenizer_config.json", 12_487),
            Asset("tokenizer/vocab.json", 2_776_833),
        ),
    ) {
        // Qwen3-ASR detects the language itself and takes no language hint.
        override fun modelConfig(dir: File, language: String) = OfflineModelConfig(
            qwen3Asr = OfflineQwen3AsrModelConfig(
                convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                decoder = File(dir, "decoder.int8.onnx").absolutePath,
                tokenizer = File(dir, "tokenizer").absolutePath,
            ),
            tokens = "",
            numThreads = 4,
        )
    };

    abstract fun modelConfig(dir: File, language: String): OfflineModelConfig

    fun createStore(context: Context): ModelFileStore = ModelFileStore(
        rootDir = File(context.filesDir, "sherpa-onnx/$id"),
        baseUrl = "https://huggingface.co/$repository/resolve/main/",
        assets = assets,
    )

    companion object {
        private const val NUM_THREADS = 2
        private val SENSE_VOICE_LANGUAGES = setOf("zh", "en", "ja", "ko", "yue")

        fun fromId(id: String): SherpaAsrModel? = entries.firstOrNull { it.id == id }
    }
}

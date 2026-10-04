package ai.moeru.airi_pocket.supertonic

import android.content.Context
import java.io.File

import ai.moeru.airi_pocket.models.ModelFileStore
import ai.moeru.airi_pocket.models.ModelFileStore.Asset

/**
 * Supertonic 3 model files on Hugging Face. Layout mirrors the repo:
 *   <filesDir>/supertonic/onnx/{*.onnx, tts.json, unicode_indexer.json}
 *   <filesDir>/supertonic/voice_styles/{M1..M5,F1..F5}.json
 */
object SupertonicAssets {
    private const val HF_BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/main/"

    val VOICE_NAMES: List<String> = listOf("M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5")

    private val ASSETS: List<Asset> = listOf(
        Asset("onnx/duration_predictor.onnx", 3_700_147),
        Asset("onnx/text_encoder.onnx", 36_416_150),
        Asset("onnx/vector_estimator.onnx", 256_534_781),
        Asset("onnx/vocoder.onnx", 101_424_195),
        Asset("onnx/tts.json", 8_253),
        Asset("onnx/unicode_indexer.json", 277_676),
    ) + VOICE_NAMES.map { Asset("voice_styles/$it.json", -1) }

    fun createStore(context: Context): ModelFileStore =
        ModelFileStore(File(context.filesDir, "supertonic"), HF_BASE, ASSETS)

    fun onnxDir(store: ModelFileStore): File = store.file("onnx")

    fun voiceStyleFile(store: ModelFileStore, name: String): File = store.file("voice_styles/$name.json")
}

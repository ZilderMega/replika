package team.replika.engine

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ModelFiles(val directory: File) {
    fun path(name: String) = File(directory, name).absolutePath
    fun missing() = REQUIRED.filter { !File(directory, it).isFile || File(directory, it).length() == 0L }
    suspend fun verify(force: Boolean = true) {
        require(missing().isEmpty()) { "Импортируйте комплект моделей: ${missing().joinToString()}" }
        val manifestText = File(directory, "manifest.json").readText()
        val manifest = JSONObject(manifestText)
        require(manifest.getInt("schema") == 1) { "Неизвестная версия комплекта моделей" }
        require(manifest.getString("profile") == "zipformer-ru-small-2024-09-18_pyannote3_campplus_qwen2.5-0.5b") { "Несовместимый комплект моделей" }
        val hashes = manifest.getJSONObject("sha256")
        val snapshot = VerifiedSet(manifestText, REQUIRED.map { name -> File(directory, name).let { Triple(name, it.length(), it.lastModified()) } })
        val key = directory.canonicalPath
        if (!force && verified[key] == snapshot) return
        REQUIRED.filter { it != "manifest.json" }.forEach { name ->
            val digest = MessageDigest.getInstance("SHA-256")
            File(directory, name).inputStream().buffered().use { source ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = EXTRA_HASHES[name] ?: hashes.getString(name)
            require(actual.equals(expected, true)) { "Повреждена модель $name. Импортируйте комплект заново." }
        }
        verified[key] = snapshot
    }
    companion object {
        private data class VerifiedSet(val manifest: String, val stamps: List<Triple<String, Long, Long>>)
        private val verified = ConcurrentHashMap<String, VerifiedSet>()
        // Separate pinned extras preserve the base manifest and existing ASR checkpoint signatures.
        val EXTRA_HASHES = mapOf("speaker-refinement.onnx" to "5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94")
        val REQUIRED = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "segmentation.onnx", "embedding.onnx", "llm.gguf",
            "punctuation.onnx", "punctuation-tokenizer.json", "punctuation-config.json", "denoise.onnx", "vad.onnx",
            "whisper-encoder.onnx", "whisper-decoder.onnx", "whisper-tokens.txt", "separation.onnx", "manifest.json") + EXTRA_HASHES.keys
    }
}

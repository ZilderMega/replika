package team.replika.engine

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class ModelFiles(val directory: File) {
    fun path(name: String) = File(directory, name).absolutePath
    fun missing() = REQUIRED.filter { !File(directory, it).isFile || File(directory, it).length() == 0L }
    suspend fun verify() {
        require(missing().isEmpty()) { "Импортируйте комплект моделей: ${missing().joinToString()}" }
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        require(manifest.getInt("schema") == 1) { "Неизвестная версия комплекта моделей" }
        require(manifest.getString("profile") == "zipformer-ru-small-2024-09-18_pyannote3_campplus_qwen2.5-0.5b") { "Несовместимый комплект моделей" }
        val hashes = manifest.getJSONObject("sha256")
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
            require(actual.equals(hashes.getString(name), true)) { "Повреждена модель $name. Импортируйте комплект заново." }
        }
    }
    companion object {
        val REQUIRED = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "segmentation.onnx", "embedding.onnx", "llm.gguf", "manifest.json")
    }
}

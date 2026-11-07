package team.replika

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import team.replika.engine.*
import java.io.File

import org.json.JSONObject

data class EngineUiState(val busy: Boolean = false, val stage: String = "", val progress: Float = 0f,
    val error: String? = null, val result: EngineResult? = null, val modelsReady: Boolean = false)

class EngineViewModel(application: Application) : AndroidViewModel(application) {
    private val modelsDir = File(application.filesDir, "models")
    private val mutable = MutableStateFlow(EngineUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun clearResult() { if (!mutable.value.busy) mutable.value = mutable.value.copy(result = null, error = null, stage = "") }
    fun cancel() { job?.cancel(); mutable.value = mutable.value.copy(stage = "Отмена: ожидаем освобождения моделей…") }

    init { prepareModels() }

    fun prepareModels() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, modelsReady = false, stage = "Подготовка приложения", progress = 0f, error = null)
        job = viewModelScope.launch {
            try {
                BundledModels(getApplication()).prepare {
                    mutable.value = mutable.value.copy(progress = it)
                }
                mutable.value = mutable.value.copy(modelsReady = true, stage = "Готово к работе")
            } catch (e: CancellationException) {
                mutable.value = mutable.value.copy(stage = "Подготовка отменена")
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = "Не удалось подготовить приложение. Освободите место и повторите: ${e.message}")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun transcribe(uri: Uri) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, stage = "Чтение аудиофайла", progress = 0f, error = null, result = null)
        job = viewModelScope.launch {
            val app = getApplication<Application>()
            var input: File? = null
            try {
                val sourceFile = File.createTempFile("replika-input-", ".audio", app.cacheDir)
                input = sourceFile
                withContext(Dispatchers.IO) {
                    requireNotNull(app.contentResolver.openInputStream(uri)).use { source ->
                        sourceFile.outputStream().buffered().use { target ->
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = source.read(buffer); if (n < 0) break
                                total += n; require(total <= 1_000_000_000) { "Аудиофайл больше 1 ГБ" }
                                target.write(buffer, 0, n)
                            }
                        }
                    }
                }
                val result = LocalTranscriptionEngine(ModelFiles(modelsDir), app.cacheDir).process(sourceFile) {
                    mutable.value = mutable.value.copy(stage = it.stage, progress = it.fraction)
                }
                withContext(Dispatchers.IO) {
                    val record = JSONObject().put("raw", JSONObject(result.raw.toJson()))
                        .put("formatted", JSONObject(result.formatted.toJson()))
                        .put("duration_ms", result.durationMs).put("elapsed_ms", result.elapsedMs)
                        .put("stages_ms", JSONObject(result.stagesMs)).put("rejected_llm_edits", result.rejectedEdits)
                    val pending = File(app.filesDir, "last-result.tmp")
                    pending.writeText(record.toString(2))
                    check(pending.renameTo(File(app.filesDir, "last-result.json")))
                }
                mutable.value = mutable.value.copy(result = result, stage = "Готово")
            } catch (e: CancellationException) { mutable.value = mutable.value.copy(stage = "Обработка отменена")
            } catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "Не удалось обработать запись")
            } catch (e: OutOfMemoryError) { mutable.value = mutable.value.copy(error = "Недостаточно памяти. Выберите запись короче.")
            } finally { input?.delete(); mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun export(uri: Uri) {
        val result = mutable.value.result ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                    it.write(result.raw.toJson())
                }
            } catch (e: Exception) { mutable.value = mutable.value.copy(error = "Не удалось сохранить JSON: ${e.message}") }
        }
    }
}

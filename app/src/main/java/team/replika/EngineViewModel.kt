package team.replika

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import team.replika.engine.*
import java.io.File

data class EngineUiState(val busy: Boolean = false, val stage: String = "", val progress: Float = 0f,
    val error: String? = null, val result: EngineResult? = null, val modelsReady: Boolean = false,
    val sessionId: String? = null, val preview: EngineResult? = null, val resumable: Boolean = false,
    val useLlm: Boolean = false, val elapsedMs: Long = 0, val stageElapsedMs: Long = 0,
    val unitElapsedMs: Long = 0, val remainingMs: Long? = null, val detail: String = "",
    val stageCompleted: Long = 0, val stageTotal: Long = 0, val unitKey: String = "",
    val options: ProcessingOptions = ProcessingOptions(), val fragment: Transcript? = null, val fragmentSerial: Long = 0)

private data class RestoredJob(val checkpoint: PipelineCheckpoint, val result: EngineResult?, val preview: EngineResult?, val options: ProcessingOptions)

class EngineViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = ProcessingService.mutable
    val state = mutable.asStateFlow()
    private var preparation: Job? = null
    private var selection: Job? = null
    fun clearResult() {
        selection?.cancel()
        if (!mutable.value.busy) mutable.update { EngineUiState(modelsReady = it.modelsReady) }
    }
    fun cancel() {
        if (preparation?.isActive == true) preparation?.cancel()
        else getApplication<Application>().startService(Intent(getApplication(), ProcessingService::class.java).setAction("cancel"))
    }
    init { prepareModels() }

    fun selectSession(id: String) {
        if (mutable.value.busy) return
        selection?.cancel()
        selection = viewModelScope.launch {
            val directory = ProcessingService.jobDirectory(getApplication<Application>().filesDir, id)
            try {
                val restored = withContext(Dispatchers.IO) {
                    val checkpoint = PipelineCheckpoint.load(directory)
                    val complete = File(directory, "result.json")
                    val preview = File(directory, "preview.json")
                    val config = File(directory, "options.json")
                    val options = if (config.isFile) org.json.JSONObject(config.readText()).let {
                        ProcessingOptions(it.optBoolean("denoise"), it.optBoolean("vad"), it.optInt("threads", 4), it.optString("provider", "cpu"), it.optInt("speakers"))
                    } else ProcessingOptions()
                    RestoredJob(checkpoint, if (complete.isFile && checkpoint.finished) ProcessingService.readResult(complete) else null,
                        if (preview.isFile) ProcessingService.readResult(preview) else null, options)
                }
                if (!mutable.value.busy) mutable.update { it.copy(sessionId = id, result = restored.result,
                    preview = restored.result ?: restored.preview, resumable = File(directory, "source.audio").isFile && !restored.checkpoint.finished,
                    useLlm = restored.checkpoint.punctuationMode == "llm", elapsedMs = restored.checkpoint.elapsedMs, options = restored.options,
                    stage = if (restored.result != null) "Готово" else "") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.update { it.copy(error = "Не удалось восстановить задание: ${e.message}") } }
        }
    }

    fun prepareModels() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, modelsReady = false, stage = "Подготовка приложения", progress = 0f, error = null) }
        preparation = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val timer = launch {
                while (isActive) { mutable.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - started) }; delay(1000) }
            }
            try {
                BundledModels(getApplication()).prepare { fraction -> mutable.update { it.copy(progress = fraction) } }
                mutable.update { it.copy(modelsReady = true, stage = "Готово к работе") }
            } catch (e: CancellationException) { mutable.update { it.copy(stage = "Подготовка отменена") }
            } catch (e: Exception) { mutable.update { it.copy(error = "Не удалось подготовить приложение: ${e.message}") }
            } finally { timer.cancel(); mutable.update { it.copy(busy = false) } }
        }
    }

    fun transcribe(uri: Uri, sessionId: String, useLlm: Boolean = false, options: ProcessingOptions = ProcessingOptions(), forceDiarization: Boolean = false) {
        if (mutable.value.busy || !mutable.value.modelsReady) return
        selection?.cancel()
        val app = getApplication<Application>()
        mutable.update { it.copy(busy = true, sessionId = sessionId, result = null, error = null, useLlm = useLlm, stage = "Запуск обработки") }
        try {
            app.startForegroundService(Intent(app, ProcessingService::class.java).putExtra("uri", uri.toString())
                .putExtra("session_id", sessionId).putExtra("llm", useLlm).putExtra("denoise", options.denoise)
                .putExtra("vad", options.filterPauses).putExtra("threads", options.threads).putExtra("provider", options.provider)
                .putExtra("speakers", options.speakers).putExtra("force_diarization", forceDiarization))
        } catch (e: Exception) { mutable.update { it.copy(busy = false, error = "Не удалось начать обработку: ${e.message}") } }
    }

    fun refine(uri: Uri, sessionId: String, line: Utterance, mixed: Boolean, separate: Boolean, denoise: Boolean) {
        if (mutable.value.busy || !mutable.value.modelsReady) return
        selection?.cancel()
        mutable.update { it.copy(busy = true, fragment = null, fragmentSerial = it.fragmentSerial + 1, error = null, stage = "Запуск обработки фрагмента") }
        try {
            val app = getApplication<Application>()
            app.startForegroundService(Intent(app, ProcessingService::class.java).putExtra("uri", uri.toString())
                .putExtra("session_id", sessionId).putExtra("fragment", true).putExtra("start_ms", line.startMs)
                .putExtra("end_ms", line.endMs).putExtra("speaker", line.speakerId).putExtra("mixed", mixed)
                .putExtra("separate", separate).putExtra("denoise", denoise))
        } catch (e: Exception) { mutable.update { it.copy(busy = false, error = "Не удалось обработать фрагмент: ${e.message}") } }
    }
}

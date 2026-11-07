package team.replika

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import team.replika.engine.*
import java.io.File

/** Owns inference independently of the activity and its ViewModel. */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            if (work == null) stopSelf() else { work?.cancel(); mutable.update { it.copy(stage = "Остановка: сохраняем готовые части…") } }
            return START_NOT_STICKY
        }
        if (work?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("session_id") ?: run { stopSelf(); return START_NOT_STICKY }
        val uri = intent.getStringExtra("uri") ?: run { stopSelf(); return START_NOT_STICKY }
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { stopSelf(); return START_NOT_STICKY }
        val llm = intent.getBooleanExtra("llm", false)
        val fragment = intent.getBooleanExtra("fragment", false)
        val options = ProcessingOptions(intent.getBooleanExtra("denoise", false), intent.getBooleanExtra("vad", false),
            intent.getIntExtra("threads", 4), intent.getStringExtra("provider") ?: "cpu", intent.getIntExtra("speakers", 0))
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing", "Расшифровка записей", NotificationManager.IMPORTANCE_LOW))
        startForeground(11, notification("Подготовка записи"))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transcription").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
        mutable.update {
            if (fragment) it.copy(busy = true, sessionId = id, stage = "Чтение выбранного фрагмента", fragment = null, error = null)
            else EngineUiState(busy = true, modelsReady = it.modelsReady, sessionId = id, stage = "Чтение аудиофайла", useLlm = llm, options = options)
        }
        work = scope.launch {
            val directory = jobDirectory(filesDir, id).apply { mkdirs() }
            val started = SystemClock.elapsedRealtime()
            var priorElapsed = 0L
            var stageStarted = started
            var unitStarted = started
            var lastStage = ""
            var lastUnit = ""
            val estimate = StageEstimate()
            var lastNotification = 0L
            var lastPreviewSaved = 0L
            val timer = launch {
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    synchronized(estimate) {
                        mutable.update { it.copy(elapsedMs = priorElapsed + now - started,
                            stageElapsedMs = now - stageStarted, unitElapsedMs = now - unitStarted,
                            remainingMs = estimate.remaining(now)) }
                    }
                    if (now - lastNotification >= 5000) {
                        val state = mutable.value
                        manager.notify(11, notification("${state.stage} · ${clockText(state.elapsedMs)}"))
                        lastNotification = now
                    }
                    delay(1000)
                }
            }
            try {
                val checkpoint = withContext(Dispatchers.IO) { PipelineCheckpoint.load(directory) }
                priorElapsed = if (fragment) 0 else checkpoint.elapsedMs
                val sourceFile = File(directory, "source.audio")
                if (!sourceFile.isFile) withContext(Dispatchers.IO) {
                    val pending = File(directory, "source.tmp")
                    try {
                        requireNotNull(contentResolver.openInputStream(Uri.parse(uri))).use { source ->
                            pending.outputStream().buffered().use { target ->
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
                        check(pending.renameTo(sourceFile)) { "Не удалось сохранить аудиофайл" }
                    } finally { pending.delete() }
                }
                fun report(progress: EngineProgress) {
                    val now = SystemClock.elapsedRealtime()
                    synchronized(estimate) {
                        if (progress.stage != lastStage) { stageStarted = now; lastStage = progress.stage }
                        if (progress.unitKey != lastUnit) { unitStarted = now; lastUnit = progress.unitKey }
                        estimate.update(progress, now)
                    }
                    mutable.update { it.copy(stage = progress.stage, progress = progress.fraction, detail = progress.detail,
                        stageCompleted = progress.completed, stageTotal = progress.total, unitKey = progress.unitKey) }
                }
                if (fragment) {
                    val candidate = FragmentProcessor.process(sourceFile, ModelFiles(File(filesDir, "models")), cacheDir,
                        intent.getLongExtra("start_ms", 0), intent.getLongExtra("end_ms", 0), intent.getStringExtra("speaker") ?: "speaker_unknown",
                        intent.getBooleanExtra("mixed", false), intent.getBooleanExtra("separate", false), options.denoise, ::report)
                    mutable.update { it.copy(fragment = candidate, stage = "Фрагмент готов: проверьте результат") }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    PipelineCheckpoint.writeAtomically(File(directory, "options.json"), org.json.JSONObject()
                        .put("denoise", options.denoise).put("vad", options.filterPauses).put("threads", options.threads).put("provider", options.provider)
                        .put("speakers", options.speakers).toString())
                }
                val result = LocalTranscriptionEngine(ModelFiles(File(filesDir, "models")), cacheDir).process(
                    sourceFile, useLlm = llm, checkpointDir = directory, options = options, forceDiarization = intent.getBooleanExtra("force_diarization", false),
                    startupElapsedMs = SystemClock.elapsedRealtime() - started, onPartial = { partial ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPreviewSaved >= 5000) {
                            PipelineCheckpoint.writeAtomically(File(directory, "preview.json"), resultJson(partial).toString())
                            lastPreviewSaved = now
                        }
                        mutable.update { it.copy(preview = partial, resumable = true) }
                    }, progress = ::report)
                withContext(Dispatchers.IO) {
                    val json = resultJson(result)
                    PipelineCheckpoint.writeAtomically(File(directory, "result.json"), json.toString())
                    PipelineCheckpoint.writeAtomically(File(filesDir, "last-result.json"), json.toString())
                    // Keep the imported source to allow punctuation later without re-import / ASR.
                    File(directory, "audio.pcm").delete()
                }
                mutable.update { it.copy(result = result, preview = result, stage = "Готово", progress = 1f,
                    elapsedMs = priorElapsed + SystemClock.elapsedRealtime() - started, resumable = false) }
            } catch (e: CancellationException) {
                mutable.update { it.copy(stage = if (timedOut) "Фоновая обработка приостановлена системой" else "Обработка приостановлена", resumable = if (fragment) it.resumable else true) }
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Не удалось обработать запись", resumable = if (fragment) it.resumable else true) }
            } catch (e: OutOfMemoryError) {
                mutable.update { it.copy(error = "Недостаточно памяти. Закройте другие приложения и продолжите обработку.", resumable = true) }
            } finally {
                timer.cancel()
                mutable.update { it.copy(busy = false, remainingMs = null) }
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 12, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 13, Intent(this, ProcessingService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "processing").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Реплика расшифровывает запись").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(android.R.drawable.ic_media_pause, "Приостановить", pause).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        work?.cancel()
        // Android requires immediate stop; the last atomic checkpoint remains valid.
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        internal val mutable = MutableStateFlow(EngineUiState())
        fun jobDirectory(filesDir: File, id: String): File {
            require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            return File(filesDir, "processing/$id")
        }
        fun resultJson(result: EngineResult): JSONObject = PipelineCheckpoint.resultJson(result)
        fun readResult(file: File): EngineResult {
            val json = JSONObject(file.readText())
            val stages = json.getJSONObject("stages_ms")
            return EngineResult(PipelineCheckpoint.transcript(json.getJSONObject("raw")),
                PipelineCheckpoint.transcript(json.getJSONObject("formatted")), json.getLong("duration_ms"),
                json.getLong("elapsed_ms"), stages.keys().asSequence().associateWith { stages.getLong(it) }, json.getInt("rejected_llm_edits"))
        }
    }
}

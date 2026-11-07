package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.replika.Transcript
import team.replika.TranscriptionEngine
import java.io.File
import android.os.SystemClock

data class EngineProgress(val stage: String, val fraction: Float, val completed: Long = 0,
    val total: Long = 0, val detail: String = "", val unitKey: String = "")
data class EngineResult(val raw: Transcript, val formatted: Transcript, val durationMs: Long,
    val elapsedMs: Long, val stagesMs: Map<String, Long>, val rejectedEdits: Int)

fun clockText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}

class LocalTranscriptionEngine(private val models: ModelFiles, private val cacheDir: File) : TranscriptionEngine {
    private val mutex = Mutex()
    override suspend fun transcribe(audio: File, onProgress: (Float) -> Unit): Transcript =
        process(audio, useLlm = false) { onProgress(it.fraction) }.raw

    suspend fun process(audio: File, useLlm: Boolean = true, checkpointDir: File? = null, startupElapsedMs: Long = 0,
        options: ProcessingOptions = ProcessingOptions(), forceDiarization: Boolean = false,
        onPartial: (EngineResult) -> Unit = {}, progress: (EngineProgress) -> Unit): EngineResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val signature = "pipeline-3:${options.audioSignature}:${audio.length()}:${audio.lastModified()}:" +
                File(models.directory, "manifest.json").readText().hashCode()
            var state = if (checkpointDir != null) PipelineCheckpoint.load(checkpointDir) else PipelineCheckpoint(cacheDir)
            if (state.signature != signature) {
                if (checkpointDir != null) state.pcmFile.delete()
                state = PipelineCheckpoint(checkpointDir ?: cacheDir).apply { this.signature = signature }
            }
            state.finished = false
            if (forceDiarization || state.voiceMatcherVersion != VoiceMatcher.VERSION || state.expectedSpeakers != options.speakers)
                state.resetSpeakers(options.speakers)
            val mode = if (useLlm) "llm" else "fast"
            if (state.punctuationMode != mode) {
                state.edits.clear(); state.rejected = 0; state.punctuated = false; state.punctuationMode = mode
            }
            val started = SystemClock.elapsedRealtime()
            val previousElapsed = state.elapsedMs + startupElapsedMs
            var stageStart = started
            var previousStage = ""
            fun accountStage() {
                val now = SystemClock.elapsedRealtime()
                if (previousStage.isNotEmpty()) state.stagesMs[previousStage] = (state.stagesMs[previousStage] ?: 0) + now - stageStart
                stageStart = now
            }
            fun stage(name: String, fraction: Float, completed: Long = 0, total: Long = 0,
                detail: String = "", unitKey: String = "") {
                if (name != previousStage) { accountStage(); previousStage = name }
                progress(EngineProgress(name, fraction, completed, total, detail, unitKey))
            }
            fun save() {
                state.elapsedMs = previousElapsed + SystemClock.elapsedRealtime() - started
                if (checkpointDir != null) state.save()
            }
            fun result(raw: Transcript, formatted: Transcript = raw) = EngineResult(raw, formatted, state.durationMs,
                previousElapsed + SystemClock.elapsedRealtime() - started, state.stagesMs.toMap(), state.rejected)
            fun publish() { onPartial(result(state.raw())) }
            val context = currentCoroutineContext()
            val threads = options.actualThreads
            var pcm: PcmAudio? = null
            try {
                stage("Проверка моделей", 0f)
                // Startup preparation already hashes every model. Reuse that check until files change.
                models.verify(force = false)
                context.ensureActive()
                if (state.samples > 0 && (state.pcmFile.length() == state.samples * 2 ||
                        (state.recognizedMs == state.durationMs && (state.diarizedSamples == state.samples || options.speakers == 1 || state.hasCompleteVoiceEvidence)))) {
                    pcm = PcmAudio(state.pcmFile, state.samples, deleteOnClose = false)
                    if (state.words.isNotEmpty()) publish()
                } else {
                    stage("Подготовка звука", .01f)
                    val decoded = AudioDecoder(cacheDir).decode(audio) { done, total ->
                        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        stage("Подготовка звука", .01f + .02f * fraction, done, total,
                            "${clockText(done)} / ${if (total > 0) clockText(total) else "…"}", "decode")
                    }
                    state.samples = decoded.samples
                    state.denoised = false
                    if (checkpointDir != null) {
                        checkpointDir.mkdirs()
                        check(decoded.file.renameTo(state.pcmFile)) { "Не удалось сохранить подготовленный звук" }
                        pcm = PcmAudio(state.pcmFile, decoded.samples, deleteOnClose = false)
                    } else pcm = decoded
                    save()
                }
                if (options.denoise && !state.denoised) {
                    stage("Очистка фонового шума", .02f, 0, state.durationMs)
                    val cleaned = AudioPreparation.clean(requireNotNull(pcm), models, threads, cacheDir) { done ->
                        stage("Очистка фонового шума", .02f, done, state.durationMs,
                            "${clockText(done)} / ${clockText(state.durationMs)}", "denoise:${done / 15000}")
                    }
                    if (checkpointDir != null) {
                        check(cleaned.file.renameTo(state.pcmFile)) { "Не удалось сохранить очищенный звук" }
                        pcm = PcmAudio(state.pcmFile, state.samples, deleteOnClose = false)
                    } else { pcm?.close(); pcm = cleaned }
                    state.denoised = true; save()
                }
                val sound = requireNotNull(pcm)
                if (options.filterPauses && !state.speechReady) {
                    stage("Поиск речи и пауз", .03f, 0, state.durationMs)
                    state.speechRegions += AudioPreparation.speech(sound, models) { done ->
                        stage("Поиск речи и пауз", .03f, done, state.durationMs,
                            "${clockText(done)} / ${clockText(state.durationMs)}", "vad")
                    }
                    state.speechReady = true; save()
                }
                if (state.recognizedMs < sound.durationMs) {
                    stage("Распознавание речи", .03f, state.recognizedMs, sound.durationMs)
                    val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(encoder = models.path("encoder.onnx"),
                            decoder = models.path("decoder.onnx"), joiner = models.path("joiner.onnx")),
                        tokens = models.path("tokens.txt"), modelType = "transducer", numThreads = threads, provider = options.provider)))
                    try {
                        val windows = recognitionWindows(sound.durationMs).filter { it.first >= state.recognizedMs }
                        windows.forEachIndexed { index, range ->
                            context.ensureActive()
                            val detail = "Участок ${clockText(range.first)}–${clockText(range.last)} из ${clockText(sound.durationMs)}"
                            stage("Распознавание речи", .03f + .59f * state.recognizedMs / sound.durationMs,
                                state.recognizedMs, sound.durationMs, detail, "asr:${range.first}")
                            if (!options.filterPauses || state.speechRegions.any { it.first < range.last && it.last > range.first }) {
                                state.words += SafeAsr.recognize(recognizer, sound, range.first, range.last)
                            }
                            state.recognizedMs = range.last
                            if (index == 0 || (index + 1) % 5 == 0 || range.last == sound.durationMs) { save(); publish() }
                            stage("Распознавание речи", .03f + .59f * state.recognizedMs / sound.durationMs,
                                state.recognizedMs, sound.durationMs, detail, "asr:${range.first}")
                        }
                    } finally { recognizer.release() }
                }
                context.ensureActive()
                // Text appears before participant assignment; each model is loaded only once.
                if (options.speakers == 1 && state.words.isNotEmpty()) {
                    state.spans.clear(); state.spans += SpeakerSpan(0, sound.durationMs, 0)
                    state.diarizedSamples = sound.samples; save(); publish()
                } else if (state.diarizedSamples < sound.samples && state.words.isNotEmpty()) {
                    stage("Определение участников", .62f, state.diarizedSamples / 16, sound.durationMs)
                    val modelLoadStarted = System.nanoTime()
                    val pool = if (state.hasCompleteVoiceEvidence) null else DiarizerPool(models, threads, options.provider, options.actualDiarizationWorkers)
                    val diarizer = pool?.engines?.first()
                    state.diarizationMetrics["native_model_load_ms"] = if (pool == null) 0 else (System.nanoTime()-modelLoadStarted)/1_000_000
                    try {
                        check(diarizer == null || diarizer.sampleRate() == PcmAudio.RATE)
                        LongDiarization.process(sound, diarizer, models, threads, checkpoint = state, expectedSpeakers = options.speakers,
                            parallelDiarizers = pool?.engines?.takeIf { it.size == 2 } ?: emptyList(), onBlock = {
                            save(); publish(); context.ensureActive()
                        }, onReconcile = { stage("Согласование участников по всей записи", .86f) }) { fraction ->
                            val from = state.diarizedSamples / 16
                            val to = minOf(from + 120_000*options.actualDiarizationWorkers, sound.durationMs)
                            stage("Определение участников", .62f + .25f * fraction,
                                (sound.durationMs * fraction).toLong(), sound.durationMs,
                                "Участок ${clockText(from)}–${clockText(to)} из ${clockText(sound.durationMs)}", "speaker:$from")
                        }
                    } finally { pool?.close() }
                } else if (state.words.isEmpty()) state.diarizedSamples = sound.samples
                context.ensureActive()
                stage("Сборка реплик", .87f)
                val raw = state.raw()
                val formatted = raw.utterances.toMutableList()
                if (formatted.isNotEmpty()) {
                    val punctuationStage = if (useLlm) "Локальная языковая модель" else "Быстрая пунктуация"
                    stage(punctuationStage, .88f)
                    save()
                    val punctuator: Punctuator = if (useLlm) LocalLlm(models.path("llm.gguf"), threads) else LocalPunctuation(models, threads)
                    punctuator.use { llm ->
                        val pieces = formatted.flatMapIndexed { index, line -> llm.pieces(line.text).map { index to it } }
                        val linePieces = mutableMapOf<Int, MutableList<String>>()
                        pieces.forEachIndexed { i, (line, original) ->
                            context.ensureActive()
                            val detail = "Фрагмент ${i + 1} из ${pieces.size} · реплика ${line + 1} из ${formatted.size}"
                            stage(punctuationStage, .88f + .11f * i / pieces.size,
                                i.toLong(), pieces.size.toLong(), detail, "llm:$i")
                            val accepted = state.edits[i] ?: run {
                                val candidate = if (!original.contains(' ') || llm.count(original) > 160) original
                                    else llm.punctuate(original) { context.isActive }
                                context.ensureActive()
                                TextGuard.accept(original, candidate).also {
                                    if (it == original && candidate.trim() != original) state.rejected++
                                    state.edits[i] = it
                                    state.elapsedMs = previousElapsed + SystemClock.elapsedRealtime() - started
                                    if (checkpointDir != null) state.saveEdit(i, it)
                                }
                            }
                            linePieces.getOrPut(line) { mutableListOf() }.add(accepted)
                            if (i == pieces.lastIndex || pieces[i + 1].first != line) {
                                val joined = linePieces.getValue(line).joinToString(" ")
                                formatted[line] = formatted[line].copy(text = TextGuard.accept(raw.utterances[line].text, joined))
                                onPartial(result(raw, Transcript(formatted.toList())))
                            }
                            stage(punctuationStage, .88f + .11f * (i + 1) / pieces.size,
                                (i + 1).toLong(), pieces.size.toLong(), detail, "llm:$i")
                        }
                    }
                    state.punctuated = true
                }
                stage("Готово", 1f)
                accountStage()
                previousStage = ""
                val completedResult = result(raw, Transcript(formatted))
                if (checkpointDir != null) {
                    PipelineCheckpoint.writeAtomically(File(checkpointDir, "result.json"), PipelineCheckpoint.resultJson(completedResult).toString())
                }
                // Publish the completion marker only after its matching result is durable.
                state.finished = true
                save()
                completedResult
            } finally {
                accountStage()
                save()
                pcm?.close()
            }
        }
    }

    companion object {
        fun recognitionWindows(durationMs: Long): List<LongRange> = buildList {
            var from = 0L
            while (from < durationMs) {
                val to = minOf(from + 6_000, durationMs); add(from..to); from = to
            }
        }
    }
}

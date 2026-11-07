package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.replika.Transcript
import team.replika.TranscriptionEngine
import java.io.File
import android.os.SystemClock

data class EngineProgress(val stage: String, val fraction: Float)
data class EngineResult(val raw: Transcript, val formatted: Transcript, val durationMs: Long,
    val elapsedMs: Long, val stagesMs: Map<String, Long>, val rejectedEdits: Int)

class LocalTranscriptionEngine(private val models: ModelFiles, private val cacheDir: File) : TranscriptionEngine {
    private val mutex = Mutex()
    override suspend fun transcribe(audio: File, onProgress: (Float) -> Unit): Transcript =
        process(audio) { onProgress(it.fraction) }.raw

    suspend fun process(audio: File, progress: (EngineProgress) -> Unit): EngineResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtime()
            val stages = linkedMapOf<String, Long>()
            var stageStart = started
            var previousStage = ""
            fun stage(name: String, fraction: Float) {
                val now = SystemClock.elapsedRealtime()
                if (name != previousStage) {
                    if (previousStage.isNotEmpty()) stages[previousStage] = now - stageStart
                    stageStart = now; previousStage = name
                }
                progress(EngineProgress(name, fraction))
            }
            val context = currentCoroutineContext()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            stage("Проверка моделей", 0f)
            models.verify()
            stage("Подготовка звука", .03f)
            AudioDecoder(cacheDir).decode(audio).use { pcm ->
                stage("Определение участников", .10f)
                val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = models.path("segmentation.onnx")), numThreads = threads),
                    embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = threads),
                    clustering = FastClusteringConfig(threshold = .5f),
                    minDurationOn = .15f, minDurationOff = .3f))
                val spans = try {
                    check(diarizer.sampleRate() == PcmAudio.RATE)
                    LongDiarization.process(pcm, diarizer, models, threads) { fraction ->
                        context.ensureActive()
                        stage("Определение участников", .10f + .30f * fraction)
                    }
                } finally { diarizer.release() }
                context.ensureActive()
                val words = mutableListOf<Word>()
                run {
                    stage("Распознавание речи", .4f)
                    val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(encoder = models.path("encoder.onnx"),
                            decoder = models.path("decoder.onnx"), joiner = models.path("joiner.onnx")),
                        tokens = models.path("tokens.txt"), modelType = "transducer", numThreads = threads)))
                    try {
                        // Do not use diarization as a hard VAD gate: it misses short answers.
                        val windows = recognitionWindows(pcm.durationMs)
                        windows.forEachIndexed { index, range ->
                            context.ensureActive()
                            words += SafeAsr.recognize(recognizer, pcm, range.first, range.last)
                            stage("Распознавание речи", .4f + .4f * (index + 1) / windows.size)
                        }
                    } finally { recognizer.release() }
                }
                stage("Сборка реплик", .80f)
                val raw = Transcript(Alignment.assemble(words, spans, pcm.durationMs))
                var rejected = 0
                val formatted = raw.utterances.toMutableList()
                if (formatted.isNotEmpty()) {
                    stage("Локальная языковая модель", .82f)
                    LocalLlm(models.path("llm.gguf"), threads).use { llm ->
                        formatted.indices.forEach { i ->
                            context.ensureActive()
                            val original = formatted[i].text
                            // Bound prompt size even for pathological output; preserve the original on overflow.
                            if (!original.contains(' ')) {
                                // A single word has no punctuation context; retain it verbatim.
                            } else if (original.length <= 600) {
                                val candidate = llm.punctuate(original) { context.isActive }
                                context.ensureActive()
                                val accepted = TextGuard.accept(original, candidate)
                                if (accepted == original && candidate.trim() != original) rejected++
                                formatted[i] = formatted[i].copy(text = accepted)
                            } else rejected++
                            stage("Локальная языковая модель", .82f + .17f * (i + 1) / formatted.size)
                        }
                    }
                }
                stage("Готово", 1f)
                EngineResult(raw, Transcript(formatted), pcm.durationMs, SystemClock.elapsedRealtime() - started, stages, rejected)
            }
        }
    }

    companion object {
        /** Non-overlapping ownership windows on the original timeline, including short speech. */
        fun recognitionWindows(durationMs: Long): List<LongRange> = buildList {
            var from = 0L
            while (from < durationMs) {
                val to = minOf(from + 6_000, durationMs); add(from..to); from = to
            }
        }
    }
}

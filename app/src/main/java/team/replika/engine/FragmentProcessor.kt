package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import team.replika.Transcript
import team.replika.Utterance
import java.io.File

class LocalSeparation(path: String, threads: Int) : AutoCloseable {
    private var handle = open(path, threads)
    fun separate(samples: FloatArray): Array<FloatArray> = run(handle, samples)
    override fun close() { if (handle != 0L) { release(handle); handle = 0 } }
    private external fun open(path: String, threads: Int): Long
    private external fun run(handle: Long, samples: FloatArray): Array<FloatArray>
    private external fun release(handle: Long)
    companion object { init { System.loadLibrary("replika-llm") } }
}

object FragmentProcessor {
    suspend fun process(input: File, models: ModelFiles, cacheDir: File, start: Long, end: Long,
        speaker: String, mixedLanguage: Boolean, separate: Boolean, denoise: Boolean,
        progress: (EngineProgress) -> Unit): Transcript = withContext(Dispatchers.Default) {
        require(start >= 0 && end > start && end - start <= 30_000) { "Выберите фрагмент до 30 секунд" }
        require(!mixedLanguage || end - start <= 29_000) { "Для Whisper выберите фрагмент до 29 секунд" }
        require(!separate || end - start in 100..6000) { "Для разделения голосов выберите от 0,1 до 6 секунд" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val temporary = mutableListOf<PcmAudio>()
        try {
            progress(EngineProgress("Чтение выбранного фрагмента", 0f, detail = "${clockText(start)}–${clockText(end)}", unitKey = "fragment"))
            var pcm = AudioDecoder(cacheDir).decode(input, start, end).also { temporary += it }
            if (denoise) {
                progress(EngineProgress("Очистка выбранного фрагмента", .1f, unitKey = "clean-fragment"))
                pcm = AudioPreparation.clean(pcm, models, threads, cacheDir) {}.also { temporary += it }
            }
            var sources = arrayOf(pcm.read(0, pcm.samples.toInt()))
            if (separate) {
                progress(EngineProgress("Разделение двух голосов", .2f, unitKey = "separation"))
                sources = LocalSeparation(models.path("separation.onnx"), threads).use { it.separate(sources.single()) }
                // Separation can attenuate either source. Normalize bounded excerpts before ASR.
                sources = sources.map { samples ->
                    val peak = samples.maxOf { kotlin.math.abs(it) }
                    val gain = if (peak > .00001f) minOf(.9f / peak, 50f) else 1f
                    FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
                }.toTypedArray()
                currentCoroutineContext().ensureActive()
            }
            val config = if (mixedLanguage) OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = models.path("whisper-encoder.onnx"), decoder = models.path("whisper-decoder.onnx"),
                    language = "", task = "transcribe", tailPaddings = 0), tokens = models.path("whisper-tokens.txt"), numThreads = threads)
            else OfflineModelConfig(transducer = OfflineTransducerModelConfig(encoder = models.path("encoder.onnx"),
                decoder = models.path("decoder.onnx"), joiner = models.path("joiner.onnx")),
                tokens = models.path("tokens.txt"), modelType = "transducer", numThreads = threads)
            val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = config))
            val lines = mutableListOf<Utterance>()
            try {
                sources.forEachIndexed { i, samples ->
                    currentCoroutineContext().ensureActive()
                    progress(EngineProgress(if (mixedLanguage) "Уточнение смешанной речи" else "Повторное распознавание", .3f + .6f * i / sources.size,
                        i.toLong(), sources.size.toLong(), "Дорожка ${i + 1} из ${sources.size}", "fragment-asr:$i"))
                    val stream = recognizer.createStream()
                    val text = try {
                        stream.acceptWaveform(samples.copyOf(maxOf(samples.size, 16000)), 16000)
                        recognizer.decode(stream)
                        currentCoroutineContext().ensureActive()
                        recognizer.getResult(stream).text.trim()
                    } finally { stream.release() }
                    if (text.isNotBlank()) lines += Utterance(if (separate) "speaker_review_${i + 1}" else speaker, start, end, text)
                }
            } finally { recognizer.release() }
            require(lines.isNotEmpty()) { "Речь во фрагменте не обнаружена. Исходная реплика сохранена." }
            require(!separate || lines.size == 2) { "Один из голосов не распознан. Выберите другой диапазон или модель; исходная реплика сохранена." }
            // Deliberately keep the selected range. Whisper here does not claim word-level alignment.
            Transcript(lines)
        } finally { temporary.forEach { it.close() } }
    }
}

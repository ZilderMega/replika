package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import team.replika.Transcript
import java.io.File
import java.util.Locale

object SpeakerTurnRefinement {
    private val tokens = Regex("[\\p{L}\\p{N}]+")

    /** Reuse real ASR timestamps while preserving the selected text's words and punctuation. */
    fun wordsForText(text: String, source: List<Word>, start: Long, end: Long): List<Word> {
        val words = source.filter { (it.startMs + it.endMs) / 2 in start until end }
        val expected = words.flatMapIndexed { index, word -> tokens.findAll(word.text).map { index to it.value.lowercase(Locale.ROOT) }.toList() }
        val actual = tokens.findAll(text).toList()
        require(expected.isNotEmpty() && expected.map { it.second } == actual.map { it.value.lowercase(Locale.ROOT) }) {
            "Для этого текста нет совпадающих времён слов. Сохраните правки и разделите реплику вручную; исходные слова не изменены."
        }
        val starts = linkedMapOf<Int, Int>()
        expected.forEachIndexed { token, (word, _) -> starts.putIfAbsent(word, actual[token].range.first) }
        val indices = starts.keys.toList()
        return indices.mapIndexed { i, index ->
            val from = if (i == 0) 0 else starts.getValue(index)
            val to = indices.getOrNull(i+1)?.let { starts.getValue(it) } ?: text.length
            words[index].copy(text = text.substring(from, to).trim(),
                startMs = words[index].startMs.coerceAtLeast(start), endMs = words[index].endMs.coerceAtMost(end))
        }
    }

    suspend fun process(input: File, models: ModelFiles, cacheDir: File, start: Long, end: Long,
        text: String, sourceWords: List<Word>, progress: (EngineProgress) -> Unit): Transcript {
        require(start >= 0 && end > start && end-start <= 30_000) { "Выберите фрагмент до 30 секунд" }
        val words = wordsForText(text, sourceWords, start, end)
        progress(EngineProgress("Чтение выбранного фрагмента", 0f, unitKey = "speaker-refinement-read"))
        AudioDecoder(cacheDir).decode(input, start, end).use { pcm ->
            currentCoroutineContext().ensureActive()
            progress(EngineProgress("Точное уточнение смены голоса", .05f, detail = "Текст сохраняется; голоса требуют проверки", unitKey = "speaker-refinement"))
            val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = models.path("segmentation.onnx")), numThreads = 4),
                embedding = SpeakerEmbeddingExtractorConfig(model = models.path("speaker-refinement.onnx"), numThreads = 4),
                clustering = FastClusteringConfig(threshold = .5f), minDurationOn = .15f, minDurationOff = .3f))
            val spans = try {
                val audio = pcm.read(0, pcm.samples.toInt())
                diarizer.processWithCallback(if (audio.size < 16000) audio.copyOf(16000) else audio, { done, total, _ ->
                    if (total > 0) progress(EngineProgress("Точное уточнение смены голоса", .05f+.9f*done/total,
                        done.toLong(), total.toLong(), "Текст сохраняется; голоса требуют проверки", "speaker-refinement"))
                    0
                }).map { SpeakerSpan(start+(it.start*1000).toLong(), minOf(end, start+(it.end*1000).toLong()), -it.speaker-2) }
            } finally { diarizer.release() }
            currentCoroutineContext().ensureActive()
            require(spans.isNotEmpty()) { "Голоса не найдены. Исходная реплика сохранена." }
            return Transcript(Alignment.assemble(words, spans, end))
        }
    }
}

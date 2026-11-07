package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import team.replika.Transcript
import team.replika.Utterance
import java.io.File

data class VoiceAnchor(val speakerId: String, val examples: List<FloatArray>)
data class SpeakerSuggestion(val original: Utterance, val speakerId: String)

object SpeakerAssistance {
    private fun evidence(blocks: List<VoiceBlock>) = blocks.flatMap { block -> block.observations.map { observation ->
        observation.copy(intervals = observation.intervals.mapNotNull {
            val start = maxOf(it.first, block.startSample/16); val end = minOf(it.last, block.endSample/16)
            if (end > start) start..end else null
        })
    } }

    fun suggestions(transcript: Transcript, blocks: List<VoiceBlock>, anchors: List<VoiceAnchor>): List<SpeakerSuggestion> {
        val evidence = evidence(blocks)
        return transcript.utterances.mapNotNull { line ->
            val observations = evidence.mapNotNull { observation ->
                val intervals = observation.intervals.mapNotNull {
                    val start = maxOf(it.first, line.startMs); val end = minOf(it.last, line.endMs)
                    if (end-start >= 120) start..end else null
                }
                if (intervals.isEmpty()) null else observation.copy(intervals = intervals)
            }
            if (observations.isEmpty() || observations.any { !it.consistent || it.vector == null || it.supportMs < 1000 }) return@mapNotNull null
            if (observations.indices.any { a -> (a+1 until observations.size).any { b -> VoiceMatcher.overlap(observations[a].intervals, observations[b].intervals) } }) return@mapNotNull null
            val targets = observations.map { observation ->
                val vector = requireNotNull(observation.vector)
                val ranked = anchors.mapNotNull { anchor ->
                    VoiceMatcher.average(anchor.examples)?.let { anchor.speakerId to VoiceMatcher.cosine(it, vector) }
                }.sortedByDescending { it.second }
                val best = ranked.firstOrNull() ?: return@mapNotNull null
                if (best.second < .88f || best.second-(ranked.getOrNull(1)?.second ?: 0f) < .10f) return@mapNotNull null
                best.first
            }.distinct()
            if (targets.size != 1 || targets.single() == line.speakerId) null else SpeakerSuggestion(line, targets.single())
        }
    }

    suspend fun confirmAndSuggest(input: File, models: ModelFiles, cacheDir: File, checkpoint: PipelineCheckpoint,
        anchor: Utterance, transcript: Transcript, progress: (EngineProgress) -> Unit): List<SpeakerSuggestion> {
        require(anchor.speakerId.matches(Regex("speaker_[1-9][0-9]*"))) { "Укажите подтверждённый ID, например speaker_2" }
        require(anchor.startMs >= 0 && anchor.endMs <= checkpoint.durationMs && anchor.endMs-anchor.startMs in 2000..9000) {
            "Выберите 2–9 секунд, где слышен только подтверждённый человек"
        }
        require(checkpoint.hasCompleteVoiceEvidence) { "Сначала пересчитайте участников, чтобы подготовить голосовые образцы" }
        val overlapping = evidence(checkpoint.voiceBlocks).map { observation -> observation.intervals.mapNotNull {
            val a = maxOf(anchor.startMs, it.first); val b = minOf(anchor.endMs, it.last)
            if (b-a >= 120) a..b else null
        } }.filter { it.isNotEmpty() }
        require(!overlapping.indices.any { a -> (a+1 until overlapping.size).any { b -> VoiceMatcher.overlap(overlapping[a], overlapping[b]) } }) {
            "В выбранном образце обнаружено наложение голосов. Выберите чистую реплику."
        }
        progress(EngineProgress("Подготовка подтверждённого голоса", .1f, unitKey = "voice-anchor"))
        val vectors = mutableListOf<FloatArray>()
        AudioDecoder(cacheDir).decode(input, anchor.startMs, anchor.endMs).use { pcm ->
            val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = 4))
            try {
                for (sample in VoiceMatcher.sampleIntervals(listOf(0L..pcm.durationMs))) {
                    currentCoroutineContext().ensureActive()
                    val stream = extractor.createStream()
                    try {
                        stream.acceptWaveform(pcm.read(sample.first*16, ((sample.last-sample.first)*16).toInt()), 16000); stream.inputFinished()
                        if (extractor.isReady(stream)) VoiceMatcher.normalize(extractor.compute(stream))?.let { vectors += it }
                    } finally { stream.release() }
                }
            } finally { extractor.release() }
        }
        require(vectors.isNotEmpty() && VoiceObservation(0, emptyList(), vectors, 3000).consistent) { "Образец голоса неустойчив. Выберите другой чистый участок." }
        currentCoroutineContext().ensureActive()
        val file = File(checkpoint.directory, "confirmed-voices.json")
        val anchors = load(file, checkpoint.signature).filter { it.speakerId != anchor.speakerId } + VoiceAnchor(anchor.speakerId, vectors)
        progress(EngineProgress("Поиск похожих реплик", .8f, unitKey = "voice-anchor-search"))
        val result = suggestions(transcript, checkpoint.voiceBlocks, anchors)
        currentCoroutineContext().ensureActive()
        PipelineCheckpoint.writeAtomically(file, JSONObject().put("signature", checkpoint.signature).put("anchors", JSONArray().apply {
            anchors.forEach { row -> put(JSONObject().put("speaker", row.speakerId).put("examples", JSONArray().apply { row.examples.forEach { put(JSONArray(it.toList())) } })) }
        }).toString())
        return result
    }

    private fun load(file: File, signature: String): List<VoiceAnchor> {
        if (!file.isFile) return emptyList()
        val json = JSONObject(file.readText())
        if (json.getString("signature") != signature) return emptyList()
        val rows = json.getJSONArray("anchors")
        return List(rows.length()) { i -> rows.getJSONObject(i).let { row ->
            val examples = row.getJSONArray("examples")
            VoiceAnchor(row.getString("speaker"), List(examples.length()) { j -> examples.getJSONArray(j).let { v -> FloatArray(v.length()) { k -> v.getDouble(k).toFloat() } } })
        } }
    }
}

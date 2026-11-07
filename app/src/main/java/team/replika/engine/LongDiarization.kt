package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

object LongDiarization {
    suspend fun process(pcm: PcmAudio, diarizer: OfflineSpeakerDiarization, models: ModelFiles, threads: Int,
        blockSeconds: Int = 300, progress: (Float) -> Unit): List<SpeakerSpan> {
        val spans = mutableListOf<SpeakerSpan>()
        val prototypes = mutableListOf<FloatArray>()
        val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = threads))
        try {
            var offset = 0L
            while (offset < pcm.samples) {
                currentCoroutineContext().ensureActive()
                val count = minOf(blockSeconds.toLong() * 16000, pcm.samples - offset).toInt()
                val audio = pcm.read(offset, count)
                if (audio.none { kotlin.math.abs(it) > 1f / 32768 }) {
                    offset += count
                    progress(offset.toFloat() / pcm.samples)
                    continue
                }
                val local = diarizer.process(audio.copyOf(maxOf(audio.size, 16000)))
                val mapping = mutableMapOf<Int, Int>()
                for ((id, segments) in local.groupBy { it.speaker }) {
                    currentCoroutineContext().ensureActive()
                    val representative = segments.maxBy { it.end - it.start }
                    val first = offset + (representative.start * 16000).toLong()
                    val length = minOf(((representative.end - representative.start) * 16000).toLong(), 160000, pcm.samples - first).toInt()
                    if (length < 8000) continue
                    val stream = extractor.createStream()
                    val vector = try {
                        stream.acceptWaveform(pcm.read(first, length), 16000)
                        stream.inputFinished()
                        if (!extractor.isReady(stream)) continue
                        extractor.compute(stream)
                    } finally { stream.release() }
                    val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
                    if (norm <= 0 || !norm.isFinite()) continue
                    val normalized = FloatArray(vector.size) { vector[it] / norm }
                    val scores = prototypes.map { p -> p.indices.sumOf { (p[it] * normalized[it]).toDouble() } }
                    val best = scores.indices.maxByOrNull { scores[it] }
                    mapping[id] = if (best != null && scores[best] >= .65 && best !in mapping.values) best
                        else prototypes.size.also { prototypes += normalized }
                }
                val ms = offset / 16
                spans += local.mapNotNull { segment -> mapping[segment.speaker]?.let { id ->
                    SpeakerSpan(ms + (segment.start * 1000).toLong(), minOf(pcm.durationMs, ms + (segment.end * 1000).toLong()), id)
                } }
                offset += count
                progress(offset.toFloat() / pcm.samples)
            }
        } finally { extractor.release() }
        return spans
    }
}

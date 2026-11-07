package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object LongDiarization {
    suspend fun process(pcm: PcmAudio, diarizer: OfflineSpeakerDiarization, models: ModelFiles, threads: Int,
        blockSeconds: Int = 120, checkpoint: PipelineCheckpoint? = null,
        expectedSpeakers: Int = 0, onBlock: suspend () -> Unit = {}, progress: (Float) -> Unit): List<SpeakerSpan> {
        val spans = checkpoint?.spans ?: mutableListOf()
        val profiles = checkpoint?.voiceProfiles?.map { it.snapshot() }?.toMutableList() ?: mutableListOf()
        val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = threads))
        try {
            var offset = checkpoint?.diarizedSamples ?: 0L
            while (offset < pcm.samples) {
                currentCoroutineContext().ensureActive()
                progress(offset.toFloat() / pcm.samples)
                val count = minOf(blockSeconds.toLong() * 16000, pcm.samples - offset).toInt()
                val audioStart = (offset - 16000).coerceAtLeast(0)
                val audioEnd = (offset + count + 16000).coerceAtMost(pcm.samples)
                val audio = pcm.read(audioStart, (audioEnd - audioStart).toInt())
                if (audio.none { kotlin.math.abs(it) > 1f / 32768 }) {
                    offset += count
                    checkpoint?.diarizedSamples = offset
                    onBlock()
                    progress(offset.toFloat() / pcm.samples)
                    continue
                }
                val local = diarizer.processWithCallback(audio.copyOf(maxOf(audio.size, 16000)), { done, total, _ ->
                    // sherpa ignores the return value; cancellation is checked after the native call.
                    if (total > 0) progress((offset + count * done.toDouble() / total).toFloat() / pcm.samples)
                    0
                })
                currentCoroutineContext().ensureActive()
                val observations = mutableListOf<VoiceObservation>()
                for ((id, segments) in local.groupBy { it.speaker }) {
                    currentCoroutineContext().ensureActive()
                    fun interval(start: Float, end: Float) =
                        (audioStart / 16 + (start * 1000).toLong()).coerceIn(0, pcm.durationMs)..
                            (audioStart / 16 + (end * 1000).toLong()).coerceIn(0, pcm.durationMs)
                    val owned = segments.map { interval(it.start, it.end) }
                    val others = local.filter { it.speaker != id }.map { interval(it.start, it.end) }
                    val snippets = VoiceMatcher.sampleIntervals(VoiceMatcher.cleanIntervals(owned, others))
                    val vectors = mutableListOf<FloatArray>()
                    var support = 0L
                    for (snippet in snippets) {
                        currentCoroutineContext().ensureActive()
                        val stream = extractor.createStream()
                        try {
                            stream.acceptWaveform(pcm.read(snippet.first * 16, ((snippet.last - snippet.first) * 16).toInt()), 16000)
                            stream.inputFinished()
                            if (extractor.isReady(stream)) VoiceMatcher.normalize(extractor.compute(stream))?.let {
                                vectors += it; support += snippet.last - snippet.first
                            }
                        } finally { stream.release() }
                    }
                    observations += VoiceObservation(id, owned, vectors, support)
                }
                val mapping = VoiceMatcher.assign(observations, profiles, expectedSpeakers)
                val ms = audioStart / 16
                spans += local.mapNotNull { segment ->
                    val id = mapping[segment.speaker] ?: -1
                    val start = maxOf(offset / 16, ms + (segment.start * 1000).toLong())
                    val end = minOf((offset + count) / 16, ms + (segment.end * 1000).toLong())
                    if (end > start) SpeakerSpan(start, end, id) else null
                }
                offset += count
                checkpoint?.diarizedSamples = offset
                checkpoint?.voiceProfiles?.apply { clear(); addAll(profiles.map { it.snapshot() }) }
                checkpoint?.prototypes?.apply { clear(); addAll(profiles.map { it.centroid.copyOf() }) }
                onBlock()
                progress(offset.toFloat() / pcm.samples)
            }
        } finally { extractor.release() }
        return spans
    }
}

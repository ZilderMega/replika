package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*

object LongDiarization {
    private data class NativeWindow(val start: Long, val segments: Array<OfflineSpeakerDiarizationSegment>, val elapsedMs: Long, val invoked: Boolean)
    suspend fun process(pcm: PcmAudio, diarizer: OfflineSpeakerDiarization?, models: ModelFiles, threads: Int,
        blockSeconds: Int = 120, checkpoint: PipelineCheckpoint? = null,
        expectedSpeakers: Int = 0, parallelDiarizers: List<OfflineSpeakerDiarization> = emptyList(),
        onBlock: suspend () -> Unit = {}, onReconcile: () -> Unit = {}, progress: (Float) -> Unit): List<SpeakerSpan> {
        require(parallelDiarizers.isEmpty() || parallelDiarizers.size == 2)
        val spans = checkpoint?.spans ?: mutableListOf()
        val profiles = checkpoint?.voiceProfiles?.map { it.snapshot() }?.toMutableList() ?: mutableListOf()
        val blocks = checkpoint?.voiceBlocks ?: mutableListOf()
        val metrics = checkpoint?.diarizationMetrics ?: linkedMapOf()
        fun add(key: String, value: Long = 1) { metrics[key] = (metrics[key] ?: 0) + value }
        fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
        var extractor: SpeakerEmbeddingExtractor? = null
        val prefetched = ArrayDeque<NativeWindow>()
        suspend fun runNative(start: Long, count: Int, engine: OfflineSpeakerDiarization, report: (Int, Int) -> Unit): NativeWindow {
            currentCoroutineContext().ensureActive()
            val from = (start-16000).coerceAtLeast(0)
            val to = (start+count+16000).coerceAtMost(pcm.samples)
            val audio = pcm.read(from, (to-from).toInt())
            val started = System.nanoTime()
            val silent = audio.none { kotlin.math.abs(it) > 1f/32768 }
            val segments = if (silent) emptyArray() else engine.processWithCallback(
                if (audio.size < 16000) audio.copyOf(16000) else audio, { done, total, _ -> report(done, total); 0 })
            currentCoroutineContext().ensureActive()
            return NativeWindow(start, segments, elapsed(started), !silent)
        }
        try {
            var offset = checkpoint?.diarizedSamples ?: 0L
            while (offset < pcm.samples) {
                currentCoroutineContext().ensureActive()
                progress(offset.toFloat() / pcm.samples)
                val cached = blocks.firstOrNull { it.startSample == offset && it.endSample <= pcm.samples }
                val count = (cached?.let { it.endSample-offset } ?: minOf(blockSeconds.toLong() * 16000, pcm.samples - offset)).toInt()
                val audioStart = (offset - 16000).coerceAtLeast(0)
                if (cached == null && parallelDiarizers.isNotEmpty() && prefetched.isEmpty()) {
                    val plans = mutableListOf(offset to count)
                    val next = offset+count
                    if (next < pcm.samples && blocks.none { it.startSample == next })
                        plans += next to minOf(blockSeconds.toLong()*16000, pcm.samples-next).toInt()
                    val fractions = FloatArray(plans.size)
                    val progressLock = Any()
                    val started = System.nanoTime()
                    val windows = coroutineScope {
                        plans.mapIndexed { index, plan -> async(Dispatchers.Default) {
                            runNative(plan.first, plan.second, parallelDiarizers[index]) { done, total ->
                                if (total > 0) synchronized(progressLock) {
                                    fractions[index] = done.toFloat()/total
                                    // Report progress of the first uncommitted block; commits always remain ordered.
                                    progress((offset+count*fractions.average()).toFloat()/pcm.samples)
                                }
                            }
                        } }.awaitAll()
                    }
                    prefetched.addAll(windows)
                    add("native_ms", windows.sumOf { it.elapsedMs }); add("native_wall_ms", elapsed(started))
                    add("native_calls", windows.count { it.invoked }.toLong()); add("parallel_batches")
                }
                val block = cached ?: run {
                val window = if (prefetched.isNotEmpty()) prefetched.removeFirst().also { check(it.start == offset) }
                else runNative(offset, count, requireNotNull(diarizer)) { done, total ->
                    // sherpa ignores the return value; cancellation is checked after the native call.
                    if (total > 0) progress((offset + count * done.toDouble() / total).toFloat() / pcm.samples)
                }.also { add("native_ms", it.elapsedMs); add("native_wall_ms", it.elapsedMs); if (it.invoked) add("native_calls") else add("silent_blocks") }
                val local = window.segments
                currentCoroutineContext().ensureActive()
                val observations = mutableListOf<VoiceObservation>()
                val embeddingStart = System.nanoTime()
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
                        val engine = extractor ?: SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
                            model = models.path("embedding.onnx"), numThreads = threads)).also { extractor = it }
                        val stream = engine.createStream()
                        try {
                            stream.acceptWaveform(pcm.read(snippet.first * 16, ((snippet.last - snippet.first) * 16).toInt()), 16000)
                            stream.inputFinished()
                            if (engine.isReady(stream)) VoiceMatcher.normalize(engine.compute(stream))?.let {
                                vectors += it; support += snippet.last - snippet.first
                            }
                        } finally { stream.release() }
                    }
                    observations += VoiceObservation(id, owned, vectors, support)
                }
                add("profile_embedding_ms", elapsed(embeddingStart))
                VoiceBlock(offset, offset + count, observations).also { blocks += it }
                }
                if (cached != null) add("cached_blocks")
                val matchStart = System.nanoTime()
                val mapping = VoiceMatcher.assign(block.observations, profiles, expectedSpeakers)
                add("matching_ms", elapsed(matchStart))
                // A failed global match is not evidence that distinct local voices are one person.
                // Keep provisional identities unique across committed blocks, including after resume.
                var provisional = minOf(-1, spans.minOfOrNull { it.id } ?: -1)
                val unresolved = block.observations.map { it.localId }.filter { it !in mapping }
                    .associateWith { --provisional }
                block.assignments.clear(); block.assignments.putAll(mapping); block.assignments.putAll(unresolved)
                spans += block.observations.flatMap { observation -> observation.intervals.mapNotNull { interval ->
                    val id = block.assignments.getValue(observation.localId)
                    val start = maxOf(offset / 16, interval.first)
                    val end = minOf((offset + count) / 16, interval.last)
                    if (end > start) SpeakerSpan(start, end, id) else null
                } }
                offset += count
                checkpoint?.diarizedSamples = offset
                checkpoint?.voiceProfiles?.apply { clear(); addAll(profiles.map { it.snapshot() }) }
                checkpoint?.prototypes?.apply { clear(); addAll(profiles.map { it.centroid.copyOf() }) }
                onBlock()
                progress(offset.toFloat() / pcm.samples)
            }
            onReconcile()
            val reconcileStart = System.nanoTime()
            val replacements = VoiceReconciliation.resolve(blocks, profiles)
            spans.indices.forEach { i -> replacements[spans[i].id]?.let { spans[i] = spans[i].copy(id = it) } }
            blocks.forEach { block -> block.assignments.entries.forEach { entry -> replacements[entry.value]?.let { entry.setValue(it) } } }
            add("reconciliation_ms", elapsed(reconcileStart)); add("resolved_provisional_voices", replacements.size.toLong())
            onBlock()
        } finally { extractor?.release() }
        return spans
    }
}

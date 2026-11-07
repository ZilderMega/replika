package team.replika.engine

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream

data class ProcessingOptions(val denoise: Boolean = false, val filterPauses: Boolean = false,
    val threads: Int = 4, val provider: String = "cpu", val speakers: Int = 0, val diarizationWorkers: Int = 1) {
    init { require(threads in 1..8); require(provider in listOf("cpu", "nnapi")); require(speakers in 0..8); require(diarizationWorkers in 1..2) }
    // Speaker settings must not invalidate cached ASR or prepared audio; retain the old signature format.
    val audioSignature get() = "ProcessingOptions(denoise=$denoise, filterPauses=$filterPauses, threads=$threads, provider=$provider)"
    val actualThreads get() = threads.coerceAtMost(Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    val actualDiarizationWorkers get() = if (provider == "cpu" && actualThreads >= 2) diarizationWorkers else 1
}

object AudioPreparation {
    suspend fun clean(pcm: PcmAudio, models: ModelFiles, threads: Int, directory: File,
        progress: (Long) -> Unit): PcmAudio {
        val output = File.createTempFile("replika-clean-", ".pcm", directory)
        val denoiser = OfflineSpeechDenoiser(config = OfflineSpeechDenoiserConfig(model = OfflineSpeechDenoiserModelConfig(
            gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = models.path("denoise.onnx")), numThreads = threads)))
        try {
            check(denoiser.sampleRate == PcmAudio.RATE)
            output.outputStream().buffered().use { sink ->
                var offset = 0L
                while (offset < pcm.samples) {
                    currentCoroutineContext().ensureActive()
                    val end = minOf(offset + 15 * 16000L, pcm.samples)
                    val from = (offset - 8000).coerceAtLeast(0)
                    val to = (end + 8000).coerceAtMost(pcm.samples)
                    val input = pcm.read(from, (to - from).toInt())
                    // STFT/iSTFT rounds the tail to whole hops. Pad the input, then retain the exact ownership interval.
                    val result = denoiser.run(input.copyOf(input.size + 1024), PcmAudio.RATE)
                    check(result.sampleRate == PcmAudio.RATE && result.samples.size >= to - from) { "Шумоподавление изменило длительность записи" }
                    writePcm(sink, result.samples, (offset - from).toInt(), (end - offset).toInt())
                    offset = end; progress(offset / 16)
                }
            }
            return PcmAudio(output, pcm.samples)
        } catch (e: Throwable) { output.delete(); throw e
        } finally { denoiser.release() }
    }

    suspend fun speech(pcm: PcmAudio, models: ModelFiles, progress: (Long) -> Unit): List<LongRange> {
        val vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = models.path("vad.onnx"), threshold = .25f, minSpeechDuration = .05f,
            minSilenceDuration = .3f, maxSpeechDuration = 30f), sampleRate = 16000, numThreads = 1))
        val regions = mutableListOf<LongRange>()
        try {
            var offset = 0L
            fun drain() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    val start = (segment.start.toLong() / 16 - 1000).coerceAtLeast(0)
                    val end = ((segment.start.toLong() + segment.samples.size) / 16 + 1000).coerceAtMost(pcm.durationMs)
                    regions += start..end
                    vad.pop()
                }
            }
            while (offset < pcm.samples) {
                currentCoroutineContext().ensureActive()
                val count = minOf(16000L, pcm.samples - offset).toInt()
                vad.acceptWaveform(pcm.read(offset, count)); drain()
                offset += count
                if (offset % (10 * 16000L) == 0L || offset == pcm.samples) progress(offset / 16)
            }
            vad.flush(); drain()
            // No detection is not proof of silence: retain the original ASR path for faint/short speech.
            return if (regions.isEmpty()) listOf(0L..pcm.durationMs) else merge(regions)
        } finally { vad.release() }
    }

    fun merge(regions: List<LongRange>): List<LongRange> {
        val merged = mutableListOf<LongRange>()
        regions.sortedBy { it.first }.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last) merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            else merged += range
        }
        return merged
    }

    fun writePcm(output: OutputStream, samples: FloatArray, from: Int = 0, count: Int = samples.size) {
        val bytes = ByteArray(count * 2)
        repeat(count) { index ->
            val sample = (samples[from + index].coerceIn(-1f, 1f) * 32767).toInt()
            bytes[index * 2] = sample.toByte(); bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        output.write(bytes)
    }
}

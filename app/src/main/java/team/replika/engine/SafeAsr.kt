package team.replika.engine

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

object SafeAsr {
    suspend fun recognize(recognizer: OfflineRecognizer, pcm: PcmAudio, start: Long, end: Long): List<Word> {
        currentCoroutineContext().ensureActive()
        val from = (start - 1000).coerceAtLeast(0)
        val to = (end + 1000).coerceAtMost(pcm.durationMs)
        val samples = pcm.read(from * 16, ((to - from) * 16).toInt())
        if (samples.none { kotlin.math.abs(it) > 1f / 32768 }) return emptyList()
        try {
            val stream = recognizer.createStream()
            try {
                // Very short tails can violate the encoder's minimum feature length.
                stream.acceptWaveform(if (samples.size < 16000) samples.copyOf(16000) else samples, 16000)
                recognizer.decode(stream)
                currentCoroutineContext().ensureActive()
                val result = recognizer.getResult(stream)
                check(result.text.isBlank() || result.tokens.isNotEmpty()) { "ASR не вернул токены" }
                return Alignment.words(result.tokens, result.timestamps, from, to).filter {
                    (it.startMs + it.endMs) / 2 in start until end
                }
            } finally { stream.release() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (end - start > 2000) {
                val middle = (start + end) / 2
                return recognize(recognizer, pcm, start, middle) + recognize(recognizer, pcm, middle, end)
            }
            throw IllegalStateException("Ошибка распознавания участка ${start / 1000}–${end / 1000} с. ${e.message}", e)
        }
    }
}

package team.replika.engine

import team.replika.Utterance
import java.util.Locale

data class SpeakerSpan(val startMs: Long, val endMs: Long, val id: Int)
data class Word(val text: String, val startMs: Long, val endMs: Long)

object Alignment {
    fun assemble(words: List<Word>, spans: List<SpeakerSpan>, durationMs: Long): List<Utterance> {
        val ids = spans.filter { it.id >= 0 }.sortedBy { it.startMs }.map { it.id }.distinct().withIndex().associate { it.value to "speaker_${it.index + 1}" }
        val provisional = spans.filter { it.id < -1 }.sortedBy { it.startMs }.map { it.id }.distinct()
            .withIndex().associate { it.value to "speaker_review_${it.index + 1}" }
        val result = mutableListOf<Utterance>()
        val orderedSpans = spans.sortedBy { it.startMs }
        val active = mutableListOf<SpeakerSpan>()
        var nextSpan = 0
        words.sortedBy { it.startMs }.forEach { word ->
            val start = word.startMs.coerceIn(0, durationMs)
            val end = word.endMs.coerceIn(start, durationMs)
            active.removeAll { it.endMs <= start }
            while (nextSpan < orderedSpans.size && orderedSpans[nextSpan].startMs < end) {
                active += orderedSpans[nextSpan++]
            }
            val overlaps = mutableMapOf<Int, Long>()
            active.forEach { span ->
                val overlap = (minOf(end, span.endMs) - maxOf(start, span.startMs)).coerceAtLeast(0)
                if (overlap > 0) overlaps[span.id] = (overlaps[span.id] ?: 0) + overlap
            }
            val ranked = overlaps.entries.sortedByDescending { it.value }
            // Do not invent a second transcript for overlapping voices.
            val speaker = if (ranked.isEmpty() || ranked[0].key == -1 || (ranked.size > 1 && ranked[1].value * 2 >= ranked[0].value))
                "speaker_unknown" else ids[ranked[0].key] ?: provisional.getValue(ranked[0].key)
            val previous = result.lastOrNull()
            if (previous != null && previous.speakerId == speaker && start - previous.endMs <= 800 && end - previous.startMs <= 20_000) {
                result[result.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, end), text = previous.text + " " + word.text)
            } else if (word.text.isNotBlank()) result += Utterance(speaker, start, end, word.text)
        }
        return result
    }

    /** SentencePiece pieces become words without fabricating evenly spaced timestamps. */
    fun words(tokens: Array<String>, timestamps: FloatArray, offsetMs: Long, endMs: Long): List<Word> {
        require(tokens.size == timestamps.size) { "ASR не вернул временные метки токенов" }
        val result = mutableListOf<Word>()
        var text = ""
        var start = offsetMs
        var lastTokenTime = offsetMs
        fun flush(end: Long) {
            if (text.isNotBlank()) result += Word(text.trim(), start, minOf(end, lastTokenTime + 300).coerceAtLeast(start).coerceAtMost(endMs))
            text = ""
        }
        tokens.forEachIndexed { i, token ->
            val time = (offsetMs + (timestamps[i] * 1000).toLong()).coerceIn(offsetMs, endMs)
            if (token.startsWith("▁") || token.startsWith(" ")) { flush(time); start = time }
            if (text.isEmpty()) start = time
            text += token.replace('▁', ' ')
            lastTokenTime = time
        }
        flush(minOf(endMs, offsetMs + ((timestamps.lastOrNull() ?: 0f) * 1000).toLong() + 300))
        return result
    }
}

object TextGuard {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private fun tokens(text: String) = words.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
    fun accept(original: String, candidate: String): String {
        // Only whitespace, punctuation and letter case may change; no symbols or instructions.
        val allowed = candidate.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?;:—–-…\"'«»()" }
        return if (candidate.isNotBlank() && candidate.length <= original.length * 2 + 32 && allowed && tokens(original) == tokens(candidate)) candidate.trim() else original
    }
}

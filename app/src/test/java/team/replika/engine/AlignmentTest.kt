package team.replika.engine

import org.junit.Assert.*
import org.junit.Test

class AlignmentTest {
    @Test fun speakerIdsFollowFirstAppearanceAndOverlapIsUnknown() {
        val spans = listOf(SpeakerSpan(0, 1000, 8), SpeakerSpan(1100, 2000, 2), SpeakerSpan(2200, 3000, 8), SpeakerSpan(2400, 3000, 2))
        val words = listOf(Word("привет", 100, 500), Word("да", 1200, 1500), Word("вместе", 2500, 2800))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_unknown"), Alignment.assemble(words, spans, 3000).map { it.speakerId })
    }
    @Test fun punctuationCannotChangeNumbersWordsOrTheirOrder() {
        assertEquals("Привет, мир!", TextGuard.accept("привет мир", "Привет, мир!"))
        for (candidate in listOf("мир привет", "привет новый мир", "привет", "привет мир 🙂"))
            assertEquals("привет мир", TextGuard.accept("привет мир", candidate))
        assertEquals("сто 12", TextGuard.accept("сто 12", "сто 13"))
    }
    @Test fun subwordsRetainModelTimingAndOriginalOffset() {
        val words = Alignment.words(arrayOf("▁при", "вет", "▁мир"), floatArrayOf(.1f, .2f, .7f), 10_000, 11_200)
        assertEquals(listOf("привет", "мир"), words.map { it.text })
        assertEquals(10_100L, words.first().startMs)
        assertEquals(10_500L, words.first().endMs)
    }
    @Test fun windowsDoNotDuplicateOverlappingSpeechOrEraseOriginalClock() {
        val windows = LocalTranscriptionEngine.recognitionWindows(40_000)
        assertEquals(listOf(0L..6000L, 6000L..12000L, 12000L..18000L, 18000L..24000L, 24000L..30000L, 30000L..36000L, 36000L..40000L), windows)
    }
    @Test fun resamplingIsIndependentOfChunkBoundariesAndPreservesDuration() {
        val result = mutableListOf<Float>()
        val converter = MonoResampler(48_000) { result += it }
        repeat(48_000) { converter.push(.5f) }
        assertEquals(16_000, result.size)
        assertTrue(kotlin.math.abs(result.last() - .5f) < .0001)
    }
    @Test fun emptyAudioProducesEmptyTranscript() {
        assertTrue(Alignment.assemble(emptyList(), emptyList(), 1000).isEmpty())
    }
}

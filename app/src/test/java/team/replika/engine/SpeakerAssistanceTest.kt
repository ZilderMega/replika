package team.replika.engine

import org.junit.Assert.*
import org.junit.Test
import team.replika.*

class SpeakerAssistanceTest {
    private val a = floatArrayOf(1f, 0f)
    private val b = floatArrayOf(0f, 1f)
    private fun observation(id: Int, voice: FloatArray, start: Long, end: Long) = VoiceObservation(id, listOf(start..end), listOf(voice), 3000)
    @Test fun onlyMatchingNonoverlappingVoiceIsSuggestedAndWordsRemainUnchanged() {
        val lines = Transcript(listOf(Utterance("speaker_1", 0, 3000, "Первый человек."), Utterance("speaker_review_1", 4000, 7000, "Другой голос.")))
        val block = VoiceBlock(0, 160000, listOf(observation(0, a, 0, 3000), observation(1, b, 4000, 7000)))
        val suggestions = SpeakerAssistance.suggestions(lines, listOf(block), listOf(VoiceAnchor("speaker_2", listOf(a))))
        assertEquals(listOf(SpeakerSuggestion(lines.utterances[0], "speaker_2")), suggestions)
        val after = TranscriptTools.applySpeakerSuggestions(lines, suggestions)
        assertEquals("speaker_1", lines.utterances[0].speakerId)
        assertEquals(lines.utterances[0].copy(speakerId = "speaker_2"), after.utterances[0])
        assertEquals(lines.utterances[1], after.utterances[1])
    }
    @Test fun ambiguousAnchorsAndMixedOrSimultaneousVoicesAreNotApplied() {
        val line = Transcript(listOf(Utterance("speaker_unknown", 0, 7000, "Два человека")))
        val block = VoiceBlock(0, 160000, listOf(observation(0, a, 0, 3000), observation(1, b, 4000, 7000)))
        val anchors = listOf(VoiceAnchor("speaker_1", listOf(a)), VoiceAnchor("speaker_2", listOf(b)))
        assertTrue(SpeakerAssistance.suggestions(line, listOf(block), anchors).isEmpty())
        assertTrue(SpeakerAssistance.suggestions(line, listOf(block.copy(observations = listOf(observation(0, a, 0, 3000)))),
            listOf(VoiceAnchor("speaker_1", listOf(a)), VoiceAnchor("speaker_2", listOf(a)))).isEmpty())
        val simultaneous = block.copy(observations = listOf(observation(0, a, 0, 7000), observation(1, a, 0, 7000)))
        assertTrue(SpeakerAssistance.suggestions(line, listOf(simultaneous), listOf(anchors[0])).isEmpty())
    }
    @Test fun adjacentBlockContextIsNotMistakenForTwoSimultaneousPeople() {
        val line = Transcript(listOf(Utterance("speaker_unknown", 4000, 6000, "Тот же человек")))
        val blocks = listOf(VoiceBlock(0, 80000, listOf(observation(0, a, 0, 6000))),
            VoiceBlock(80000, 160000, listOf(observation(0, a, 4000, 10000))))
        assertEquals(1, SpeakerAssistance.suggestions(line, blocks, listOf(VoiceAnchor("speaker_2", listOf(a)))).size)
    }
    @Test(expected = IllegalArgumentException::class) fun staleSuggestionDoesNotOverwriteNewText() {
        val original = Utterance("speaker_1", 0, 3000, "Старая версия")
        TranscriptTools.applySpeakerSuggestions(Transcript(listOf(original.copy(text = "Исправленная версия"))), listOf(SpeakerSuggestion(original, "speaker_2")))
    }
}

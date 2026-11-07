package team.replika

import org.junit.Assert.*
import org.junit.Test
import team.replika.engine.AudioPreparation

class TranscriptToolsTest {
    @Test fun manualTurnSplitUsesChosenBoundaryAndPreservesWordsAndOriginal() {
        val original = Utterance("speaker_1", 1000, 9000, "Здравствуйте. Добрый день.")
        val split = TranscriptTools.split(original, 4200, original.text.indexOf("Добрый"))
        assertEquals(original.text, split.joinToString(" ") { it.text })
        assertEquals(4200L, split[0].endMs); assertEquals(4200L, split[1].startMs)
        assertEquals("speaker_1", split[0].speakerId); assertEquals("speaker_unknown", split[1].speakerId)
        assertEquals(9000L, original.endMs)
    }
    @Test(expected = IllegalArgumentException::class) fun manualSplitCannotGuessBoundaryInsideAWord() {
        TranscriptTools.split(Utterance("speaker_1", 0, 1000, "привет мир"), 500, 3)
    }
    @Test(expected = IllegalArgumentException::class) fun manualSplitRequiresAnInteriorTime() {
        TranscriptTools.split(Utterance("speaker_1", 0, 1000, "привет мир"), 1000, 7)
    }
    @Test fun globalSpeakerCorrectionChangesOnlyIdsAndKeepsOriginal() {
        val raw = Transcript(List(3) { Utterance(if (it == 1) "speaker_2" else "speaker_1", it * 1000L, (it + 1) * 1000L, "слово$it") })
        val revised = TranscriptTools.reassignSpeaker(raw, "speaker_1", "speaker_2")
        assertTrue(revised.utterances.all { it.speakerId == "speaker_2" })
        assertEquals(raw.utterances.map { it.text to (it.startMs to it.endMs) }, revised.utterances.map { it.text to (it.startMs to it.endMs) })
        assertEquals("speaker_1", raw.utterances.first().speakerId)
    }
    @Test fun replacementPreservesEverythingOutsideSelectedUtterance() {
        val raw = Transcript(List(3) { Utterance("speaker_1", it * 1000L, (it + 1) * 1000L, "текст$it") })
        val candidates = listOf(Utterance("speaker_1", 1000, 2000, "один голос"), Utterance("speaker_2", 1000, 2000, "второй голос"))
        val revised = TranscriptTools.replace(raw, 1, candidates, 3000)
        assertEquals(raw.utterances.first(), revised.utterances.first())
        assertEquals(raw.utterances.last(), revised.utterances.last())
        assertEquals(candidates, revised.utterances.subList(1, 3))
        assertEquals(3, raw.utterances.size)
    }
    @Test(expected = IllegalArgumentException::class) fun replacementCannotExtendBeyondRecording() {
        TranscriptTools.replace(Transcript(listOf(Utterance("speaker_1", 0, 1000, "да"))), 0,
            listOf(Utterance("speaker_1", 0, 2000, "да")), 1000)
    }
    @Test fun paddedSpeechRegionsMergeWithoutChangingOriginalClock() {
        assertEquals(listOf(0L..2000L, 4000L..8000L), AudioPreparation.merge(listOf(1000L..2000L, 0L..1500L, 5000L..8000L, 4000L..6000L)))
    }
}

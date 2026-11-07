package team.replika.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceEvidenceTest {
    @Test fun workerChoicePreservesAsrSignatureAndUnsupportedProvidersUseOneWorker() {
        assertEquals(ProcessingOptions().audioSignature, ProcessingOptions(diarizationWorkers = 2).audioSignature)
        assertEquals(1, ProcessingOptions(threads = 1, diarizationWorkers = 2).actualDiarizationWorkers)
        assertEquals(1, ProcessingOptions(provider = "nnapi", diarizationWorkers = 2).actualDiarizationWorkers)
    }
    @Test(expected = IllegalArgumentException::class) fun unboundedParallelismIsRejected() {
        ProcessingOptions(diarizationWorkers = 3)
    }
    private val a = floatArrayOf(1f, 0f)
    private val b = floatArrayOf(0f, 1f)
    private fun observation(id: Int, vector: FloatArray, from: Long = 0) = VoiceObservation(id, listOf(from..from+3000), listOf(vector), 3000)

    @Test fun laterProfileResolvesEarlierProvisionalWithoutChangingConfirmedVoice() {
        val block = VoiceBlock(0, 160000, listOf(observation(0, a), observation(1, b, 4000)), linkedMapOf(0 to 0, 1 to -2))
        val profiles = listOf(VoiceProfile(a, 3000), VoiceProfile(b, 3000))
        assertEquals(mapOf(-2 to 1), VoiceReconciliation.resolve(listOf(block), profiles))
        assertArrayEquals(a, profiles[0].centroid, 0f)
        assertEquals(mapOf(0 to 0, 1 to -2), block.assignments)
    }
    @Test fun reconciliationDoesNotMergeSimultaneousVoicesOrInventProfiles() {
        val block = VoiceBlock(0, 160000, listOf(observation(0, a), observation(1, a, 1000)), linkedMapOf(0 to 0, 1 to -2))
        assertTrue(VoiceReconciliation.resolve(listOf(block), listOf(VoiceProfile(a, 3000))).isEmpty())
        assertTrue(VoiceReconciliation.resolve(listOf(block), emptyList()).isEmpty())
    }
    @Test fun cachedVoiceEvidenceSurvivesResetAndRematchesWithoutPcmOrNativeModel() = runBlocking {
        val directory = Files.createTempDirectory("voice-evidence").toFile()
        try {
            val state = PipelineCheckpoint(directory).apply {
                samples = 160000; recognizedMs = 10000; diarizedSamples = samples
                words += Word("да", 100, 500)
                voiceBlocks += VoiceBlock(0, samples, listOf(observation(0, a)), linkedMapOf(0 to 0))
            }
            state.save()
            val loaded = PipelineCheckpoint.load(directory)
            loaded.resetSpeakers(0)
            assertTrue(loaded.hasCompleteVoiceEvidence)
            assertFalse(loaded.pcmFile.exists())
            PcmAudio(loaded.pcmFile, loaded.samples, deleteOnClose = false).use { pcm ->
                LongDiarization.process(pcm, null, ModelFiles(File(directory, "no-models")), 4, checkpoint = loaded) {}
            }
            assertEquals(loaded.samples, loaded.diarizedSamples)
            assertEquals(1L, loaded.diarizationMetrics["cached_blocks"])
            assertNull(loaded.diarizationMetrics["native_calls"])
            assertEquals("speaker_1", loaded.raw().utterances.single().speakerId)
        } finally { directory.deleteRecursively() }
    }
    @Test fun partialOrDiscontinuousEvidenceCannotBypassAudioPreparation() {
        val state = PipelineCheckpoint(File("unused")).apply { samples = 320000 }
        state.voiceBlocks += VoiceBlock(0, 160000, emptyList())
        assertFalse(state.hasCompleteVoiceEvidence)
        state.voiceBlocks += VoiceBlock(160001, 320000, emptyList())
        assertFalse(state.hasCompleteVoiceEvidence)
    }
    @Test fun turnRefinementPreservesPunctuationAndActualWordTimes() {
        val words = listOf(Word("привет", 100, 400), Word("мир", 600, 900))
        val result = SpeakerTurnRefinement.wordsForText("«Привет, мир!»", words, 0, 1000)
        assertEquals(listOf(Word("«Привет,", 100, 400), Word("мир!»", 600, 900)), result)
    }
    @Test(expected = IllegalArgumentException::class) fun changedWordsAreNotGivenInventedTimestamps() {
        SpeakerTurnRefinement.wordsForText("новый текст", listOf(Word("другие", 100, 400), Word("слова", 600, 900)), 0, 1000)
    }
}

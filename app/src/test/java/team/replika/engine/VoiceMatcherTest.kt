package team.replika.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VoiceMatcherTest {
    private val a = floatArrayOf(1f, 0f, 0f)
    private val b = floatArrayOf(0f, 1f, 0f)
    private fun observation(id: Int, vector: FloatArray, start: Long = id * 4000L) =
        VoiceObservation(id, listOf(start..start + 3000), listOf(vector), 3000)

    @Test fun nativeSplitOfSameVoiceSharesIdentityAndDistinctVoiceGetsNewIdentity() {
        val profiles = mutableListOf<VoiceProfile>()
        val ids = VoiceMatcher.assign(listOf(observation(0, a), observation(1, a), observation(2, b)), profiles)
        assertEquals(mapOf(0 to 0, 1 to 0, 2 to 1), ids)
        assertEquals(2, profiles.size)
    }
    @Test fun simultaneousClustersCannotBecomeTheSameSpeaker() {
        val profiles = mutableListOf(VoiceProfile(a.copyOf(), 3000))
        val ids = VoiceMatcher.assign(listOf(observation(0, a, 0), observation(1, a, 1000)), profiles)
        assertEquals(1, ids.size)
        assertEquals(1, profiles.size)
    }
    @Test fun ambiguousSimilarityDoesNotDriftProfilesOrInventIdentity() {
        val profiles = mutableListOf(VoiceProfile(a.copyOf(), 3000), VoiceProfile(b.copyOf(), 3000))
        val uncertain = requireNotNull(VoiceMatcher.normalize(floatArrayOf(1f, 1f, 0f)))
        assertTrue(VoiceMatcher.assign(listOf(observation(2, uncertain)), profiles).isEmpty())
        assertArrayEquals(a, profiles[0].centroid, 0f)
        assertEquals(2, profiles.size)
    }
    @Test fun inconsistentSamplesAndShortNewVoicesRemainUnknownAndCapacityIsRespected() {
        val profiles = mutableListOf(VoiceProfile(a.copyOf(), 3000))
        assertTrue(VoiceMatcher.assign(listOf(observation(1, b)), profiles, 1).isEmpty())
        assertTrue(VoiceMatcher.assign(listOf(observation(1, b).copy(supportMs = 500)), profiles).isEmpty())
        assertTrue(VoiceMatcher.assign(listOf(observation(1, b).copy(embeddings = listOf(a, b))), profiles).isEmpty())
    }
    @Test fun cleanSnippetsAvoidOverlapAndBoundEmbeddingWork() {
        val clean = VoiceMatcher.cleanIntervals(listOf(0L..12000), listOf(3000L..5000))
        val samples = VoiceMatcher.sampleIntervals(clean)
        assertTrue(samples.all { it.last <= 2900 || it.first >= 5100 })
        val long = VoiceMatcher.sampleIntervals(listOf(0L..60000))
        assertEquals(3, long.size)
        assertTrue(long.sumOf { it.last - it.first } <= 9000)
        assertFalse(VoiceMatcher.overlap(listOf(long[0]), listOf(long[1])))
    }
    @Test fun profileBankRoundTripsAndSpeakerResetKeepsAsr() {
        val directory = Files.createTempDirectory("voice-state").toFile()
        try {
            val state = PipelineCheckpoint(directory).apply {
                signature = "test"; samples = 160000; recognizedMs = 10000; diarizedSamples = samples
                words += Word("да", 0, 1000); voiceMatcherVersion = VoiceMatcher.VERSION; expectedSpeakers = 2
                voiceProfiles += VoiceProfile(a.copyOf(), 3000, mutableListOf(a.copyOf(), b.copyOf()))
            }
            state.save()
            val loaded = PipelineCheckpoint.load(directory)
            assertEquals(2, loaded.voiceProfiles.single().examples.size)
            assertEquals(VoiceMatcher.VERSION, loaded.voiceMatcherVersion)
            assertArrayEquals(a, loaded.voiceProfiles.single().centroid, 0f)
            val copy = loaded.voiceProfiles.single().snapshot(); copy.examples[0][0] = -1f
            assertEquals(1f, loaded.voiceProfiles.single().examples[0][0])
            loaded.resetSpeakers(1)
            assertEquals(state.words, loaded.words); assertEquals(10000L, loaded.recognizedMs)
            assertTrue(loaded.voiceProfiles.isEmpty()); assertEquals(0L, loaded.diarizedSamples)
            assertEquals(ProcessingOptions().audioSignature, ProcessingOptions(speakers = 2).audioSignature)
        } finally { directory.deleteRecursively() }
    }
    @Test fun explicitlyUnknownVoiceDoesNotBorrowAdjacentIdentity() {
        val result = Alignment.assemble(listOf(Word("да", 100, 400), Word("нет", 1000, 1500)),
            listOf(SpeakerSpan(0, 500, 4), SpeakerSpan(600, 2000, -1)), 2000)
        assertEquals(listOf("speaker_1", "speaker_unknown"), result.map { it.speakerId })
    }
}

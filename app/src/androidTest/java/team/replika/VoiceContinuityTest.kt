package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoiceContinuityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models get() = ModelFiles(File(context.filesDir, "models"))
    @Before fun prepare() { runBlocking { BundledModels(context).prepare() } }
    private fun diarizer() = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = models.path("segmentation.onnx")), numThreads = 2),
        embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = 2),
        clustering = FastClusteringConfig(threshold = .5f), minDurationOn = .15f, minDurationOff = .3f))

    @Test fun oneVoiceAcrossBlocksSurvivesCheckpointAndTwoVoicesRemainDistinct() = runBlocking {
        val proof = JSONObject()
        for ((fixture, expected) in listOf("window-test.mp3" to 1, "two-speakers.wav" to 2)) {
            val directory = File(context.cacheDir, "voice-$expected").apply { deleteRecursively(); mkdirs() }
            try {
                AudioDecoder(context.cacheDir).decode(File(context.filesDir, fixture)).use { pcm ->
                    var state = PipelineCheckpoint(directory).apply { samples = pcm.samples; resetSpeakers(0) }
                    val model = diarizer()
                    try {
                        // Stop after a committed native block; load a fresh state and continue with the saved bank.
                        class BlockSaved : RuntimeException()
                        try {
                            LongDiarization.process(pcm, model, models, 2, blockSeconds = 6, checkpoint = state,
                                onBlock = { state.save(); throw BlockSaved() }) {}
                        } catch (_: BlockSaved) { }
                        val firstIds = state.spans.map { it.id }.filter { it >= 0 }.toSet()
                        val firstSpans = state.spans.toList()
                        state = PipelineCheckpoint.load(directory)
                        LongDiarization.process(pcm, model, models, 2, blockSeconds = 6, checkpoint = state, onBlock = { state.save() }) {}
                        val ids = state.spans.map { it.id }.filter { it >= 0 }.toSet()
                        val knownMs = state.spans.filter { it.id >= 0 }.sumOf { it.endMs - it.startMs }
                        val unknownMs = state.spans.filter { it.id < 0 }.sumOf { it.endMs - it.startMs }
                        proof.put(fixture, JSONObject().put("known_ids", JSONArray(ids.toList())).put("profiles", state.voiceProfiles.size)
                            .put("known_ms", knownMs).put("unknown_ms", unknownMs).put("saved_first_ids", JSONArray(firstIds.toList()))
                            .put("spans", JSONArray().apply { state.spans.forEach { put(JSONArray(listOf(it.startMs, it.endMs, it.id))) } }))
                        File(context.filesDir, "voice-continuity.json").writeText(proof.toString(2))
                        assertEquals("$fixture: $proof", expected, ids.size)
                        assertTrue(ids.containsAll(firstIds))
                        assertEquals(firstSpans, state.spans.take(firstSpans.size))
                        val firstProvisional = firstSpans.map { it.id }.filter { it < -1 }.toSet()
                        assertTrue(state.spans.drop(firstSpans.size).none { it.id in firstProvisional })
                        assertEquals(pcm.samples, state.diarizedSamples)
                        assertTrue("Known voice coverage: $proof", knownMs > unknownMs)
                    } finally { model.release() }
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun speakerRecalculationKeepsWordsAndMonologueDoesNotRequireDeletedPcm() = runBlocking {
        val directory = File(context.cacheDir, "rediarization-test").apply { deleteRecursively(); mkdirs() }
        try {
            val input = File(context.filesDir, "test1.wav")
            val engine = LocalTranscriptionEngine(models, context.cacheDir)
            val original = engine.process(input, useLlm = false, checkpointDir = directory) {}
            val words = PipelineCheckpoint.load(directory).words.toList()
            assertTrue(words.isNotEmpty())
            File(directory, "audio.pcm").delete()
            val stages = mutableListOf<String>()
            val revised = engine.process(input, useLlm = false, checkpointDir = directory,
                options = ProcessingOptions(speakers = 1), forceDiarization = true) { stages += it.stage }
            assertFalse(stages.contains("Распознавание речи"))
            assertFalse(stages.contains("Подготовка звука"))
            assertFalse(stages.contains("Определение участников"))
            assertEquals(words, PipelineCheckpoint.load(directory).words)
            assertTrue(revised.raw.utterances.all { it.speakerId == "speaker_1" })
            assertEquals(original.raw.utterances.joinToString(" ") { it.text }, revised.raw.utterances.joinToString(" ") { it.text })
            val autoStages = mutableListOf<String>()
            engine.process(input, useLlm = false, checkpointDir = directory, forceDiarization = true) { autoStages += it.stage }
            assertTrue(autoStages.contains("Определение участников"))
            assertFalse(autoStages.contains("Распознавание речи"))
            assertEquals(words, PipelineCheckpoint.load(directory).words)
            File(context.filesDir, "voice-recalculation.json").writeText(JSONObject().put("monologue_stages", JSONArray(stages.distinct()))
                .put("auto_stages", JSONArray(autoStages.distinct())).put("preserved_words", words.size).put("raw", JSONObject(revised.raw.toJson())).toString(2))
        } finally { directory.deleteRecursively() }
    }
}

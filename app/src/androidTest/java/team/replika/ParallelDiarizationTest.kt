package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParallelDiarizationTest {
    @Test fun twoWorkersCommitInOrderAndResumeWithoutChangingSpeakerEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = BundledModels(context).prepare()
        val directory = File(context.cacheDir, "parallel-diarization-test").apply { deleteRecursively(); mkdirs() }
        try {
            AudioDecoder(context.cacheDir).decode(File(context.filesDir, "two-speakers.wav")).use { pcm ->
                val baselineState = PipelineCheckpoint(directory).apply { samples = pcm.samples; resetSpeakers(0) }
                val baseline = DiarizerPool(models, 4, "cpu", 1).use { pool ->
                    LongDiarization.process(pcm, pool.engines.first(), models, 4, blockSeconds = 6, checkpoint = baselineState) {}.toList()
                }
                var state = PipelineCheckpoint(directory).apply { samples = pcm.samples; resetSpeakers(0) }
                class BlockSaved : RuntimeException()
                DiarizerPool(models, 4, "cpu", 2).use { pool ->
                    try {
                        LongDiarization.process(pcm, pool.engines.first(), models, 4, blockSeconds = 6, checkpoint = state,
                            parallelDiarizers = pool.engines, onBlock = { state.save(); throw BlockSaved() }) {}
                    } catch (_: BlockSaved) { }
                }
                assertEquals(6000*16L, state.diarizedSamples)
                assertEquals(1, state.voiceBlocks.size)
                state = PipelineCheckpoint.load(directory)
                DiarizerPool(models, 4, "cpu", 2).use { pool ->
                    LongDiarization.process(pcm, pool.engines.first(), models, 4, blockSeconds = 6, checkpoint = state,
                        parallelDiarizers = pool.engines, onBlock = { state.save() }) {}
                }
                assertEquals(pcm.samples, state.diarizedSamples)
                assertTrue(state.hasCompleteVoiceEvidence)
                assertEquals(baseline, state.spans)
                assertEquals(baselineState.voiceProfiles.size, state.voiceProfiles.size)
                File(context.filesDir, "parallel-diarization-proof.json").writeText(JSONObject().put("device", android.os.Build.MODEL)
                    .put("fixture", "two-speakers.wav").put("workers", 2).put("threads_per_worker", 2)
                    .put("checkpoint_committed_in_order", true).put("resumed_at_ms", 6000)
                    .put("same_spans_as_one_worker", true).put("diarization_metrics", JSONObject(state.diarizationMetrics)).toString(2))
            }
        } finally { directory.deleteRecursively() }
    }
}

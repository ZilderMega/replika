package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File

/** Agreement against the conservative algorithm is a regression signal, never a ground-truth DER. */
@RunWith(AndroidJUnit4::class)
class DiarizationStrideTest {
    @Test fun compareSegmentationStrideWithoutChangingDefault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = BundledModels(context).prepare()
        val report = JSONObject().put("device", android.os.Build.MODEL).put("metric", "frame-set agreement against 0.1 baseline; not DER")
        val rows = JSONArray(); report.put("runs", rows)
        val output = File(context.filesDir, "diarization-stride.json")
        for ((fixture, expected) in listOf("window-test.mp3" to 1, "two-speakers.wav" to 2)) {
            AudioDecoder(context.cacheDir).decode(File(context.filesDir, fixture)).use { pcm ->
                var baseline: List<SpeakerSpan>? = null
                for (shift in listOf(.1f, .25f, .5f)) {
                    val directory = File(context.cacheDir, "stride-check").apply { mkdirs() }
                    val state = PipelineCheckpoint(directory).apply { samples = pcm.samples; resetSpeakers(0) }
                    val started = android.os.SystemClock.elapsedRealtime()
                    val model = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                        segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                            model = models.path("segmentation.onnx"), windowShiftRatio = shift), numThreads = 4),
                        embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = 4),
                        clustering = FastClusteringConfig(threshold = .5f), minDurationOn = .15f, minDurationOff = .3f))
                    val spans = try { LongDiarization.process(pcm, model, models, 4, checkpoint = state) {}.toList() } finally { model.release() }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    if (baseline == null) baseline = spans
                    fun idsAt(values: List<SpeakerSpan>, time: Long) = values.filter { it.startMs <= time && it.endMs > time }.map { it.id }.toSet()
                    var matching = 0L; var total = 0L
                    var time = 0L
                    while (time < pcm.durationMs) {
                        if (idsAt(requireNotNull(baseline), time) == idsAt(spans, time)) matching++
                        total++; time += 20
                    }
                    val agreement = matching.toDouble() / total
                    val profiles = spans.map { it.id }.filter { it >= 0 }.distinct().size
                    rows.put(JSONObject().put("fixture", fixture).put("shift", shift).put("elapsed_ms", elapsed)
                        .put("profiles", profiles).put("agreement_with_baseline", agreement)
                        .put("eligible_on_these_fixtures_only", profiles == expected && agreement >= .95)
                        .put("unknown_ms", spans.filter { it.id < 0 }.sumOf { it.endMs - it.startMs }))
                    output.writeText(report.toString(2))
                    if (shift == .1f) assertEquals(expected, profiles)
                }
            }
        }
    }
}

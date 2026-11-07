package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class DiarizationWindowTest {
    @Test fun compareShortWindowsAgainstHumanSpeakerReference() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("windowBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = BundledModels(context).prepare()
        val report = JSONObject().put("device", android.os.Build.MODEL)
        val rows = JSONArray(); report.put("runs", rows)
        for (fixture in listOf("ami-development.wav", "ami-holdout.wav")) {
            AudioDecoder(context.cacheDir).decode(File(context.filesDir, fixture)).use { pcm ->
                for (seconds in listOf(120, 8, 6)) {
                    val directory = File(context.cacheDir, "window-benchmark").apply { deleteRecursively(); mkdirs() }
                    try {
                        val state = PipelineCheckpoint(directory).apply { samples = pcm.samples; resetSpeakers(0) }
                        val started = SystemClock.elapsedRealtime()
                        val spans = DiarizerPool(models, 4, "cpu", 1).use { pool ->
                            LongDiarization.process(pcm, pool.engines.first(), models, 4, blockSeconds = seconds, checkpoint = state) {}
                        }
                        rows.put(JSONObject().put("fixture", fixture).put("block_seconds", seconds)
                            .put("elapsed_ms", SystemClock.elapsedRealtime()-started)
                            .put("metrics", JSONObject(state.diarizationMetrics)).put("spans", JSONArray().apply {
                                spans.forEach { put(JSONArray(listOf(it.startMs, it.endMs, it.id))) }
                            }))
                        File(context.filesDir, "diarization-window-benchmark.json").writeText(report.toString(2))
                    } finally { directory.deleteRecursively() }
                }
            }
        }
    }
}

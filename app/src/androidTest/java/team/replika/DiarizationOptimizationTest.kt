package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class DiarizationOptimizationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models get() = ModelFiles(File(context.filesDir, "models"))
    private fun model(threads: Int) = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
            model = models.path("segmentation.onnx")), numThreads = threads, debug = true),
        embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = threads),
        clustering = FastClusteringConfig(threshold = .5f), minDurationOn = .15f, minDurationOff = .3f))

    @Test fun compareBlockSizesAndWorkerScheduling() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("optimizationBenchmark") == "true")
        val report = JSONObject().put("device", android.os.Build.MODEL)
        val rows = JSONArray(); report.put("runs", rows)
        val output = File(context.filesDir, "diarization-optimization.json")
        val privatePcm = File(context.filesDir, "private-long-validation/audio.pcm")
        if (privatePcm.isFile) {
            PcmAudio(privatePcm, privatePcm.length()/2, deleteOnClose = false).use { pcm ->
                for (block in listOf(120, 60, 30)) {
                    val diarizer = model(4)
                    try {
                        val started = SystemClock.elapsedRealtime()
                        var count = 0
                        for (start in 0 until 120 step block) {
                            val from = (start*16000L-16000).coerceAtLeast(0)
                            val to = minOf((start+block)*16000L+16000, 120*16000L)
                            count += diarizer.process(pcm.read(from, (to-from).toInt())).size
                        }
                        rows.put(JSONObject().put("fixture", "private_first_120_seconds").put("block_seconds", block)
                            .put("threads", 4).put("elapsed_ms", SystemClock.elapsedRealtime()-started)
                            .put("segment_count", count).put("note", "No reference labels; counts do not measure accuracy"))
                        output.writeText(report.toString(2))
                    } finally { diarizer.release() }
                }
            }
        }
        for (workers in listOf(1, 2)) {
            repeat(3) { iteration ->
                var peakPss = android.os.Debug.getPss()
                val sampler = launch(Dispatchers.IO) { while (isActive) { peakPss = maxOf(peakPss, android.os.Debug.getPss()); delay(100) } }
                val started = SystemClock.elapsedRealtime()
                val semaphore = kotlinx.coroutines.sync.Semaphore(workers)
                val results = listOf("ami-development.wav", "ami-holdout.wav").map { fixture ->
                    async(Dispatchers.Default) {
                        semaphore.acquire()
                        try {
                            val diarizer = model(4/workers)
                            try {
                                AudioDecoder(context.cacheDir).decode(File(context.filesDir, fixture)).use { pcm ->
                                    val spans = diarizer.process(pcm.read(0, pcm.samples.toInt()))
                                    JSONObject().put("fixture", fixture).put("spans", JSONArray().apply {
                                        spans.forEach { put(JSONArray(listOf((it.start*1000).toLong(), (it.end*1000).toLong(), it.speaker))) }
                                    })
                                }
                            } finally { diarizer.release() }
                        } finally { semaphore.release() }
                    }
                }.awaitAll()
                sampler.cancelAndJoin()
                rows.put(JSONObject().put("workers", workers).put("threads_per_worker", 4/workers)
                    .put("repeat", iteration).put("elapsed_ms", SystemClock.elapsedRealtime()-started)
                    .put("sampled_process_pss_kib", peakPss)
                    .put("results", JSONArray(results)))
                output.writeText(report.toString(2))
            }
        }
    }
}

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
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class UltraEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models get() = ModelFiles(File(context.filesDir, "models"))
    @Before fun prepare() { runBlocking { BundledModels(context).prepare() } }

    @Test fun noiseAndPauseModesPreserveTimelineAndShortAnswer() = runBlocking {
        val input = File(context.filesDir, "test.wav")
        AudioDecoder(context.cacheDir).decode(input).use { pcm ->
            AudioPreparation.clean(pcm, models, 4, context.cacheDir) {}.use { clean ->
                assertEquals(pcm.samples, clean.samples)
                assertTrue(clean.read(0, clean.samples.toInt()).all { it.isFinite() })
            }
        }
        val result = LocalTranscriptionEngine(models, context.cacheDir).process(input, useLlm = false,
            options = ProcessingOptions(filterPauses = true)) {}
        assertEquals("я тебя люблю", result.raw.utterances.joinToString(" ") { it.text })
        assertTrue(result.stagesMs.containsKey("Поиск речи и пауз"))
        val noisy = File(context.filesDir, "noisy-test.wav")
        assertTrue("Install noisy-test.wav with scripts/test-device.ps1", noisy.isFile)
        run {
            val baseline = LocalTranscriptionEngine(models, context.cacheDir).process(noisy, useLlm = false) {}
            val cleaned = LocalTranscriptionEngine(models, context.cacheDir).process(noisy, useLlm = false, options = ProcessingOptions(denoise = true)) {}
            assertEquals(baseline.durationMs, cleaned.durationMs)
            File(context.filesDir, "ultra-noise.json").writeText(JSONObject().put("reference_smoke_only", "я тебя люблю")
                .put("noisy", ProcessingService.resultJson(baseline)).put("denoised", ProcessingService.resultJson(cleaned)).toString(2))
        }
    }

    @Test fun rangeDecodeReadsOnlyRequestedPartAndWhisperProducesEnglish() = runBlocking {
        AudioDecoder(context.cacheDir).decode(File(context.filesDir, "window-test.mp3"), 12_000, 18_000).use { pcm ->
            assertTrue("${pcm.durationMs}", pcm.durationMs in 5950..6050)
            assertTrue(pcm.read(0, pcm.samples.toInt()).any { abs(it) > .01f })
        }
        val transcript = FragmentProcessor.process(File(context.filesDir, "english.wav"), models, context.cacheDir,
            0, 5000, "speaker_1", mixedLanguage = true, separate = false, denoise = false) {}
        assertTrue(transcript.utterances.single().text.any { it in 'a'..'z' || it in 'A'..'Z' })
        assertEquals(0L, transcript.utterances.single().startMs)
        assertEquals(5000L, transcript.utterances.single().endMs)
        File(context.filesDir, "ultra-english.json").writeText(transcript.toJson())
    }

    @Test fun realTwoSpeakerMixtureIsSeparatedLocally() = runBlocking {
        AudioDecoder(context.cacheDir).decode(File(context.filesDir, "overlap.wav"), 0, 6000).use { pcm ->
            val samples = pcm.read(0, pcm.samples.toInt())
            val sources = LocalSeparation(models.path("separation.onnx"), 4).use { it.separate(samples) }
            assertEquals(2, sources.size)
            sources.forEach { source ->
                assertEquals(samples.size, source.size)
                assertTrue(source.all { it.isFinite() })
                assertTrue(source.any { abs(it) > .0001f })
            }
            sources.forEachIndexed { index, samples -> File(context.filesDir, "ultra-source-$index.pcm").outputStream().use { AudioPreparation.writePcm(it, samples) } }
            assertTrue(sources[0].indices.any { abs(sources[0][it] - sources[1][it]) > .001f })
        }
        val result = FragmentProcessor.process(File(context.filesDir, "overlap.wav"), models, context.cacheDir,
            0, 6000, "speaker_unknown", mixedLanguage = true, separate = true, denoise = false) {}
        assertEquals(2, result.utterances.size)
        File(context.filesDir, "ultra-overlap.json").writeText(result.toJson())
    }

    @Test fun benchmarkThreadsAndRequestedNnapi() = runBlocking {
        val measurements = JSONArray()
        AudioDecoder(context.cacheDir).decode(File(context.filesDir, "window-test.mp3")).use { pcm ->
            var reference: String? = null
            for ((provider, threads) in listOf("cpu" to 2, "cpu" to 4, "cpu" to 6, "nnapi" to 4)) {
                val started = android.os.SystemClock.elapsedRealtime()
                val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(encoder = models.path("encoder.onnx"), decoder = models.path("decoder.onnx"), joiner = models.path("joiner.onnx")),
                    tokens = models.path("tokens.txt"), modelType = "transducer", numThreads = threads, provider = provider)))
                try {
                    val loaded = android.os.SystemClock.elapsedRealtime()
                    SafeAsr.recognize(recognizer, pcm, 0, minOf(6000, pcm.durationMs))
                    val warmed = android.os.SystemClock.elapsedRealtime()
                    val samples = mutableListOf<Long>()
                    repeat(3) {
                        val inferenceStart = android.os.SystemClock.elapsedRealtime()
                        val words = LocalTranscriptionEngine.recognitionWindows(pcm.durationMs).flatMap { SafeAsr.recognize(recognizer, pcm, it.first, it.last) }
                        samples += android.os.SystemClock.elapsedRealtime() - inferenceStart
                        val text = words.joinToString(" ") { it.text }
                        if (reference == null) reference = text else assertEquals(reference, text)
                    }
                    val elapsed = samples.sorted()[1]
                    measurements.put(JSONObject().put("requested_provider", provider).put("threads", threads)
                        .put("load_ms", loaded - started).put("asr_ms", elapsed).put("audio_ms", pcm.durationMs)
                        .put("warmup_ms", warmed - loaded).put("asr_runs_ms", JSONArray(samples))
                        .put("asr_rtf", elapsed.toDouble() / pcm.durationMs).put("same_words", true))
                } finally { recognizer.release() }
            }
        }
        File(context.filesDir, "ultra-threads.json").writeText(JSONObject().put("device", android.os.Build.MODEL)
            .put("warning", "Requested NNAPI does not prove that a physical NPU executed any operator").put("runs", measurements).toString(2))
        val runs = JSONArray()
        repeat(2) {
            val result = LocalTranscriptionEngine(models, context.cacheDir).process(File(context.filesDir, "window-test.mp3"), useLlm = false) {}
            runs.put(ProcessingService.resultJson(result).put("rtf", result.elapsedMs.toDouble() / result.durationMs))
        }
        File(context.filesDir, "ultra-full-speed.json").writeText(JSONObject().put("device", android.os.Build.MODEL).put("runs", runs).toString(2))
    }
}

package team.replika

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File
import com.k2fsa.sherpa.onnx.*

@RunWith(AndroidJUnit4::class)
class PhysicalValidationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models get() = ModelFiles(File(context.filesDir, "models"))
    private fun environment(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(PowerManager::class.java)
        return JSONObject().put("battery_percent", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("battery_temperature_c", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10) / 10.0)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            .put("thermal_status", if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
            .put("process_pss_kib", Debug.getPss())
    }
    private suspend fun measured(block: suspend () -> EngineResult): JSONObject = coroutineScope {
        val before = environment()
        var peak = Debug.getPss()
        val sampling = launch(Dispatchers.IO) { while (isActive) { peak = maxOf(peak, Debug.getPss()); delay(500) } }
        try {
            val result = block()
            ProcessingService.resultJson(result).put("before", before).put("after", environment())
                .put("peak_sampled_process_pss_kib", peak).put("rtf", result.elapsedMs.toDouble() / result.durationMs)
        } finally { sampling.cancelAndJoin() }
    }

    @Test fun benchmarkPhoneProvidersAndEnglishRecognition() = runBlocking {
        BundledModels(context).prepare()
        val report = JSONObject().put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("processors", Runtime.getRuntime().availableProcessors()).put("initial_environment", environment())
            .put("nnapi_note", "Requested provider only, no proof of NPU; PSS sampled every 500ms, not native allocator peak")
        val runs = JSONArray()
        val output = File(context.filesDir, "physical-validation.json")
        report.put("pipeline_runs", runs)
        val input = File(context.filesDir, "window-test.mp3")
        for ((provider, threads) in listOf("cpu" to 2, "cpu" to 4, "cpu" to 6, "nnapi" to 4)) {
            repeat(3) { repeat ->
                val run = measured { LocalTranscriptionEngine(models, context.cacheDir).process(input, useLlm = false,
                    options = ProcessingOptions(threads = threads, provider = provider)) {} }
                run.put("provider", provider).put("threads", threads).put("repeat", repeat)
                assertTrue(run.getJSONObject("raw").getJSONArray("transcript").length() > 0)
                runs.put(run); output.writeText(report.toString(2))
            }
        }
        val languages = JSONArray()
        report.put("language_comparison", languages)
        for (fixture in listOf("english.wav", "english-second.wav")) {
            val source = File(context.filesDir, fixture)
            val duration = AudioDecoder(context.cacheDir).decode(source).use { it.durationMs }
            for (mixed in listOf(false, true)) {
                val start = SystemClock.elapsedRealtime()
                val text = FragmentProcessor.process(source, models, context.cacheDir, 0, minOf(duration, 29000),
                    "speaker_1", mixed, separate = false, denoise = false) {}
                languages.put(JSONObject().put("fixture", fixture).put("mode", if (mixed) "multilingual" else "russian")
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - start).put("duration_ms", duration)
                    .put("result", JSONObject(text.toJson())))
                output.writeText(report.toString(2))
            }
        }
        report.put("final_environment", environment()); output.writeText(report.toString(2))
    }

    @Test fun privateLongRecordingResumesAndRetainsEveryWord() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val filename = arguments.getString("validationFile")
        org.junit.Assume.assumeTrue("Pass validationFile for a private long-recording run", !filename.isNullOrBlank())
        require(filename!!.matches(Regex("[A-Za-z0-9_.-]+")))
        val input = File(context.filesDir, filename)
        require(input.isFile)
        BundledModels(context).prepare()
        val threads = arguments.getString("threads")?.toIntOrNull() ?: 4
        val directory = File(context.filesDir, "private-long-validation").apply { mkdirs() }
        val options = ProcessingOptions(threads = threads)
        val report = JSONObject().put("device", android.os.Build.MODEL).put("before", environment()).put("threads", threads)
        // Deliberately pause after a committed ASR window, then resume the actual long source.
        if (!File(directory, "checkpoint.json").isFile) {
            val job = launch {
                val own = currentCoroutineContext()
                LocalTranscriptionEngine(models, context.cacheDir).process(input, false, directory, options = options) {
                    if (it.stage == "Распознавание речи" && it.completed >= 6000) own.cancel()
                }
            }
            job.join(); assertTrue(job.isCancelled)
        }
        val saved = PipelineCheckpoint.load(directory)
        val committedWords = saved.words.toList()
        val stages = linkedSetOf<String>()
        val result = measured {
            LocalTranscriptionEngine(models, context.cacheDir).process(input, false, directory, options = options) { stages += it.stage }
        }
        val complete = PipelineCheckpoint.load(directory)
        assertEquals(committedWords, complete.words.take(committedWords.size))
        assertTrue(complete.finished)
        assertEquals(complete.durationMs, complete.recognizedMs)
        assertEquals(complete.samples, complete.diarizedSamples)
        report.put("run", result).put("resumed_at_ms", saved.recognizedMs).put("preserved_committed_words", committedWords.size)
            .put("resumed_stages", JSONArray(stages.toList())).put("after", environment())
        File(context.filesDir, "private-long-validation-result.json").writeText(report.toString(2))
        // Never copy this report to public artifacts: it contains the user's private transcript.
    }

    @Test fun showPrivateLongResultInHistory() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("publishPrivateResult") == "true")
        fun retainSourceSignature(audio: File, directory: File) {
            // File.copyTo changes mtime; preserve it so later speaker-only recalculation retains ASR.
            require(audio.setLastModified(File(context.filesDir, "private-interview.m4a").lastModified()))
            val signature = "pipeline-3:${ProcessingOptions(threads = 4).audioSignature}:${audio.length()}:${audio.lastModified()}:" +
                File(models.directory, "manifest.json").readText().hashCode()
            assertEquals(signature, PipelineCheckpoint.load(directory).signature)
        }
        val marker = File(context.filesDir, "private-visible-session-id.txt")
        if (marker.isFile) {
            val directory = ProcessingService.jobDirectory(context.filesDir, marker.readText())
            assertTrue(directory.isDirectory)
            retainSourceSignature(File(directory, "source.audio"), directory)
            return
        }
        val source = File(context.filesDir, "private-long-validation")
        val result = JSONObject(File(source, "result.json").readText())
        require(PipelineCheckpoint.load(source).finished)
        val id = java.util.UUID.randomUUID().toString()
        val destination = ProcessingService.jobDirectory(context.filesDir, id)
        destination.parentFile!!.mkdirs()
        require(destination.mkdirs())
        source.listFiles().orEmpty().filter { it.isFile && it.name != "audio.pcm" }.forEach { it.copyTo(File(destination, it.name)) }
        val audio = File(destination, "source.audio")
        File(context.filesDir, "private-interview.m4a").copyTo(audio)
        retainSourceSignature(audio, destination)
        PipelineCheckpoint.writeAtomically(File(destination, "options.json"), JSONObject().put("threads", 4)
            .put("provider", "cpu").put("denoise", false).put("vad", false).put("speakers", 0).toString())
        val prefs = context.getSharedPreferences("sessions", 0)
        val sessions = SessionCodec.decode(prefs.getString("items", "[]")!!)
        val session = Session(id, "Длинная запись · ${clockText(result.getLong("duration_ms"))}",
            android.net.Uri.fromFile(audio).toString(), result.getJSONObject("raw").toString(), result.getJSONObject("formatted").toString())
        require(prefs.edit().putString("items", SessionCodec.encode(listOf(session) + sessions)).commit())
        marker.writeText(id)
        File(destination, "audio.pcm").delete()
    }

    @Test fun annotatedMeetingDiarization() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Install AMI fixtures explicitly", arguments.getString("amiValidation") == "true")
        BundledModels(context).prepare()
        val report = JSONObject().put("device", android.os.Build.MODEL).put("voice_matcher_version", VoiceMatcher.VERSION)
        val rows = JSONArray(); report.put("runs", rows)
        for (fixture in listOf("ami-development.wav", "ami-holdout.wav")) {
            AudioDecoder(context.cacheDir).decode(File(context.filesDir, fixture)).use { pcm ->
                for (threshold in listOf(.5f, .35f, .25f)) {
                    val model = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                        segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                            model = models.path("segmentation.onnx")), numThreads = 4),
                        embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = 4),
                        clustering = FastClusteringConfig(threshold = threshold), minDurationOn = .15f, minDurationOff = .3f))
                    val started = SystemClock.elapsedRealtime()
                    val spans = try { LongDiarization.process(pcm, model, models, 4) {} } finally { model.release() }
                    rows.put(JSONObject().put("fixture", fixture).put("threshold", threshold)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("profiles", spans.map { it.id }.filter { it >= 0 }.distinct().size)
                        .put("provisional_voices", spans.map { it.id }.filter { it < -1 }.distinct().size)
                        .put("spans", JSONArray().apply { spans.forEach { put(JSONArray(listOf(it.startMs, it.endMs, it.id))) } }))
                    File(context.filesDir, "ami-validation.json").writeText(report.toString(2))
                }
            }
        }
    }

    @Test fun nativeAnnotatedMeetingBaseline() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("amiValidation") == "true")
        BundledModels(context).prepare()
        val report = JSONObject().put("device", android.os.Build.MODEL).put("mode", "native local clustering, no global matcher")
        val rows = JSONArray(); report.put("runs", rows)
        for (fixture in listOf("ami-development.wav", "ami-holdout.wav")) {
            AudioDecoder(context.cacheDir).decode(File(context.filesDir, fixture)).use { pcm ->
                for (threshold in listOf(.5f, .65f, .8f)) {
                    val model = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                        segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                            model = models.path("segmentation.onnx")), numThreads = 4),
                        embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = 4),
                        clustering = FastClusteringConfig(threshold = threshold), minDurationOn = .15f, minDurationOff = .3f))
                    val started = SystemClock.elapsedRealtime()
                    val segments = try { model.process(pcm.read(0, pcm.samples.toInt())) } finally { model.release() }
                    rows.put(JSONObject().put("fixture", fixture).put("threshold", threshold)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("profiles", segments.map { it.speaker }.distinct().size)
                        .put("spans", JSONArray().apply { segments.forEach { put(JSONArray(listOf((it.start*1000).toLong(), (it.end*1000).toLong(), it.speaker))) } }))
                    File(context.filesDir, "ami-native-baseline.json").writeText(report.toString(2))
                }
            }
        }
    }

    @Test fun compareCandidateEmbeddingOnAnnotatedMeeting() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("candidateEmbedding") == "true")
        BundledModels(context).prepare()
        val candidate = ModelFiles(File(context.filesDir, "candidate-embedding"))
        require(File(candidate.path("embedding.onnx")).isFile)
        val report = JSONObject().put("device", android.os.Build.MODEL).put("embedding", "wespeaker_en_voxceleb_resnet34")
        val rows = JSONArray(); report.put("runs", rows)
        for (fixture in listOf("ami-development.wav", "ami-holdout.wav")) {
            AudioDecoder(context.cacheDir).decode(File(context.filesDir, fixture)).use { pcm ->
                for (global in listOf(false, true)) {
                    val model = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                        segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                            model = models.path("segmentation.onnx")), numThreads = 4),
                        embedding = SpeakerEmbeddingExtractorConfig(model = candidate.path("embedding.onnx"), numThreads = 4),
                        clustering = FastClusteringConfig(threshold = .5f), minDurationOn = .15f, minDurationOff = .3f))
                    val started = SystemClock.elapsedRealtime()
                    val spans = try {
                        if (global) LongDiarization.process(pcm, model, candidate, 4) {}
                        else model.process(pcm.read(0, pcm.samples.toInt())).map { SpeakerSpan((it.start*1000).toLong(), (it.end*1000).toLong(), it.speaker) }
                    } finally { model.release() }
                    rows.put(JSONObject().put("fixture", fixture).put("global_matcher", global).put("threshold", .5)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("profiles", spans.map { it.id }.filter { it >= 0 }.distinct().size)
                        .put("spans", JSONArray().apply { spans.forEach { put(JSONArray(listOf(it.startMs,it.endMs,it.id))) } }))
                    File(context.filesDir, "ami-candidate-embedding.json").writeText(report.toString(2))
                }
            }
        }
    }
}

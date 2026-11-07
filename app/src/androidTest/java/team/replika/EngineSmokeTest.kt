package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.json.JSONObject
import team.replika.engine.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.k2fsa.sherpa.onnx.*

/** Inputs are installed by scripts/test-device.ps1, never embedded as fake app results. */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mp3AcrossRecognitionWindows() = runBlocking {
        val result = LocalTranscriptionEngine(ModelFiles(File(context.filesDir, "models")), context.cacheDir)
            .process(File(context.filesDir, "window-test.mp3")) {}
        assertTrue(result.durationMs > 30_000)
        assertTrue(result.raw.utterances.any { it.endMs > 25_000 })
    }

    @Test fun microphoneRecordingIsSaved() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        kotlinx.coroutines.delay(2000)
        context.startForegroundService(android.content.Intent(context, RecordingService::class.java))
        repeat(40) { if (!RecordingService.state.value.active) kotlinx.coroutines.delay(200) }
        try {
            assertTrue(RecordingService.state.value.toString(), RecordingService.state.value.active)
            kotlinx.coroutines.delay(2500)
        } finally { context.startService(android.content.Intent(context, RecordingService::class.java).setAction("stop")) }
        repeat(40) { if (RecordingService.state.value.active) kotlinx.coroutines.delay(200) }
        val path = requireNotNull(RecordingService.state.value.file) { RecordingService.state.value.toString() }
        AudioDecoder(context.cacheDir).decode(File(path)).use { assertTrue(it.durationMs > 1000) }
    }

    @Test fun recordingLongerThanThirtyMinutes() = runBlocking {
        val file = File(context.cacheDir, "long-silence.wav")
        val count = 31 * 60 * 16000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count * 2)
        java.io.RandomAccessFile(file, "rw").use { it.write(header.array()); it.setLength(44L + count * 2) }
        try {
            val result = LocalTranscriptionEngine(ModelFiles(File(context.filesDir, "models")), context.cacheDir).process(file) {}
            assertTrue(result.durationMs >= 31 * 60 * 1000L)
            assertTrue(result.raw.utterances.isEmpty())
        } finally { file.delete() }
    }

    @Before fun prepareBuiltInModels() = runBlocking {
        BundledModels(context).prepare()
    }.let { Unit }

    @Test fun builtInModelsAreReusedOnNextLaunch() = runBlocking {
        val model = File(context.filesDir, "models/llm.gguf")
        val modified = model.lastModified()
        assertTrue(model.length() > 0)
        val files = BundledModels(context).prepare()
        assertTrue(files.missing().isEmpty())
        assertEquals(modified, model.lastModified())
    }

    @Test fun damagedPreparedModelIsRestoredFromApk() = runBlocking {
        val tokens = File(context.filesDir, "models/tokens.txt")
        val expected = context.assets.open("models/tokens.txt").bufferedReader().use { it.readText() }
        tokens.writeText("damaged")
        BundledModels(context).prepare()
        assertEquals(expected, tokens.readText())
        assertFalse(File(context.filesDir, "models-preparing").exists())
    }

    @Test fun cancellationReleasesTemporaryAudio() = runBlocking {
        val before = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        val job = launch {
            val ownContext = currentCoroutineContext()
            LocalTranscriptionEngine(ModelFiles(File(context.filesDir, "models")), context.cacheDir)
                .process(File(context.filesDir, "test.wav")) {
                    if (it.stage == "Распознавание речи") ownContext.cancel()
                }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("replika-pcm-") && it.name !in before })
    }

    @Test fun corruptedModelsAreRejectedBeforeNativeLoading() = runBlocking {
        val directory = File(context.cacheDir, "bad-models").apply { mkdirs() }
        try {
            ModelFiles.REQUIRED.forEach { File(directory, it).writeText("broken") }
            File(context.filesDir, "models/manifest.json").copyTo(File(directory, "manifest.json"), overwrite = true)
            var rejected = false
            try { ModelFiles(directory).verify() } catch (e: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { directory.deleteRecursively() }
    }

    @Test fun secondRussianRecordingAndMp3Decode() = runBlocking {
        val result = LocalTranscriptionEngine(ModelFiles(File(context.filesDir, "models")), context.cacheDir)
            .process(File(context.filesDir, "test1.wav")) {}
        File(context.filesDir, "second-result.json").writeText(JSONObject()
            .put("raw", JSONObject(result.raw.toJson())).put("formatted", JSONObject(result.formatted.toJson()))
            .put("elapsed_ms", result.elapsedMs).put("stages_ms", JSONObject(result.stagesMs)).toString(2))
        assertTrue(result.raw.utterances.joinToString(" ") { it.text }.contains("родион", ignoreCase = true))
        AudioDecoder(context.cacheDir).decode(File(context.filesDir, "test-stereo.mp3")).use {
            assertTrue(it.durationMs in 9000..9600)
            assertTrue(it.read(0, it.samples.toInt()).any { sample -> kotlin.math.abs(sample) > .01f })
        }
    }

    @Test fun fullAudioAsrReference() = runBlocking {
        val models = ModelFiles(File(context.filesDir, "models"))
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = models.path("encoder.onnx"), decoder = models.path("decoder.onnx"), joiner = models.path("joiner.onnx")),
            tokens = models.path("tokens.txt"), modelType = "transducer", numThreads = 4)))
        try {
            AudioDecoder(context.cacheDir).decode(File(context.filesDir, "test.wav")).use { pcm ->
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(pcm.read(0, pcm.samples.toInt()), 16000)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    File(context.filesDir, "asr-reference.txt").writeText(result.text + "\n" + result.tokens.zip(result.timestamps.toList()).joinToString())
                    assertTrue(result.text.isNotBlank())
                } finally { stream.release() }
            }
        } finally { recognizer.release() }
    }

    @Test fun twoSpeakersAreDetectedAutomatically() = runBlocking {
        val models = ModelFiles(File(context.filesDir, "models"))
        AudioDecoder(context.cacheDir).decode(File(context.filesDir, "two-speakers.wav")).use { pcm ->
            val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = models.path("segmentation.onnx")), numThreads = 4),
                embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = 4),
                clustering = FastClusteringConfig(threshold = .5f), minDurationOn = .15f, minDurationOff = .3f))
            try {
                val spans = diarizer.process(pcm.read(0, pcm.samples.toInt()))
                File(context.filesDir, "diarization-result.txt").writeText(spans.joinToString("\n"))
                assertEquals(2, spans.map { it.speaker }.distinct().size)
            } finally { diarizer.release() }
        }
    }

    @Test fun russianAudioRunsEntirelyOnAndroid() = runBlocking {
        val input = File(context.filesDir, "test.wav")
        assertTrue("Install test.wav with scripts/test-device.ps1", input.isFile)
        val stages = mutableSetOf<String>()
        val result = LocalTranscriptionEngine(ModelFiles(File(context.filesDir, "models")), context.cacheDir).process(input) {
            stages += it.stage
        }
        assertTrue(result.raw.utterances.isNotEmpty())
        assertEquals("я тебя люблю", result.raw.utterances.joinToString(" ") { it.text })
        assertTrue(stages.contains("Локальная языковая модель"))
        result.raw.utterances.forEach { assertTrue(it.startMs >= 0 && it.endMs <= result.durationMs) }
        result.raw.utterances.zip(result.formatted.utterances).forEach { (a, b) ->
            assertEquals(b.text, TextGuard.accept(a.text, b.text))
        }
        val record = JSONObject().put("raw", JSONObject(result.raw.toJson())).put("formatted", JSONObject(result.formatted.toJson()))
            .put("duration_ms", result.durationMs).put("elapsed_ms", result.elapsedMs)
            .put("stages_ms", JSONObject(result.stagesMs)).put("rejected_edits", result.rejectedEdits)
        File(context.filesDir, "smoke-result.json").writeText(record.toString(2))
    }

    @Test fun silenceAndCorruptAudio() = runBlocking {
        val silence = File(context.cacheDir, "silence.wav")
        val samples = 32_000
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        silence.writeBytes(bytes.array())
        try {
            val result = LocalTranscriptionEngine(ModelFiles(File(context.filesDir, "models")), context.cacheDir).process(silence) {}
            assertTrue(result.raw.utterances.isEmpty())
            silence.writeText("not audio")
            var failed = false
            try { AudioDecoder(context.cacheDir).decode(silence).close() } catch (e: Exception) { failed = true }
            assertTrue(failed)
        } finally { silence.delete() }
    }
}

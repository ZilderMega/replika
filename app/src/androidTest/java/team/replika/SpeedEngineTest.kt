package team.replika

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpeedEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models get() = ModelFiles(File(context.filesDir, "models"))
    @Before fun prepare() { runBlocking { BundledModels(context).prepare() } }

    @Test fun fastPunctuationHandlesLongRussianTextAndMeasuresAgainstLlm() {
        val text = "шестьдесят тысяч рублей сколько будет стоить"
        val long = List(45) { text }.joinToString(" ")
        val start = android.os.SystemClock.elapsedRealtime()
        val fast: String
        var fastFirstMs = 0L
        var fastWarmMs = 0L
        LocalPunctuation(models, 4).use { model ->
            fast = model.punctuate(text) { true }
            fastFirstMs = android.os.SystemClock.elapsedRealtime() - start
            val warmStart = android.os.SystemClock.elapsedRealtime()
            repeat(10) { model.punctuate(text) { true } }
            fastWarmMs = android.os.SystemClock.elapsedRealtime() - warmStart
            assertEquals(fast, TextGuard.accept(text, fast))
            assertTrue(fast != text)
            val pieces = model.pieces(long)
            assertTrue(pieces.size > 1)
            val restored = pieces.joinToString(" ") { model.punctuate(it) { true } }
            assertEquals(restored, TextGuard.accept(long, restored))
            assertEquals(long, pieces.joinToString(" "))
        }
        val fastMs = android.os.SystemClock.elapsedRealtime() - start
        val llmStart = android.os.SystemClock.elapsedRealtime()
        var llmText = ""
        var llmFirstMs = 0L
        var llmWarmMs = 0L
        LocalLlm(models.path("llm.gguf"), 4).use { llm ->
            llmText = llm.punctuate(text) { true }
            llmFirstMs = android.os.SystemClock.elapsedRealtime() - llmStart
            val warmStart = android.os.SystemClock.elapsedRealtime()
            val repeat = llm.punctuate(text) { true }
            llmWarmMs = android.os.SystemClock.elapsedRealtime() - warmStart
            assertEquals(llmText, repeat) // Checks KV prefix reuse, including identical consecutive prompts.
            val different = "привет как твои дела"
            val changed = llm.punctuate(different) { true }
            assertTrue(changed.isNotBlank())
            assertEquals(text, TextGuard.accept(text, "неверный ответ"))
        }
        val llmMs = android.os.SystemClock.elapsedRealtime() - llmStart
        File(context.filesDir, "speed-punctuation.json").writeText(JSONObject().put("fast_ms_including_long_text", fastMs)
            .put("fast_first_including_load_ms", fastFirstMs).put("fast_warm_average_ms", fastWarmMs / 10.0)
            .put("llm_first_including_load_ms", llmFirstMs).put("llm_warm_ms", llmWarmMs)
            .put("llm_ms_three_short_calls", llmMs).put("fast_text", fast).put("llm_text", llmText).toString(2))
    }

    @Test fun cancelledAsrContinuesFromCheckpointAndPublishesBeforeDiarization() = runBlocking {
        val input = File(context.filesDir, "window-test.mp3")
        val directory = File(context.cacheDir, "resume-speed-test").apply { mkdirs() }
        val seen = mutableListOf<String>()
        try {
            val job = launch {
                val own = currentCoroutineContext()
                var stage = ""
                LocalTranscriptionEngine(models, context.cacheDir).process(input, useLlm = false, checkpointDir = directory,
                    onPartial = { if (it.raw.utterances.isNotEmpty()) seen += stage }) {
                    stage = it.stage
                    if (it.stage == "Распознавание речи" && it.completed >= 6000) own.cancel()
                }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(seen.contains("Распознавание речи"))
            val saved = PipelineCheckpoint.load(directory)
            assertEquals(6000L, saved.recognizedMs)
            assertTrue(saved.pcmFile.isFile)
            val resumedProgress = mutableListOf<EngineProgress>()
            val result = LocalTranscriptionEngine(models, context.cacheDir).process(input, useLlm = false, checkpointDir = directory) { resumedProgress += it }
            assertFalse(resumedProgress.any { it.stage == "Подготовка звука" || it.stage == "Локальная языковая модель" })
            assertTrue(resumedProgress.first { it.stage == "Распознавание речи" }.completed >= 6000)
            assertTrue(result.raw.utterances.any { it.endMs > 25_000 })
            assertTrue(PipelineCheckpoint.load(directory).finished)
            assertTrue(result.stagesMs.containsKey("Быстрая пунктуация"))
            // A second punctuation mode reuses words, participants, and time stamps, even after PCM cleanup.
            saved.pcmFile.delete()
            val replay = mutableListOf<String>()
            val repeated = LocalTranscriptionEngine(models, context.cacheDir).process(input, useLlm = false, checkpointDir = directory) { replay += it.stage }
            assertFalse(replay.contains("Распознавание речи"))
            assertFalse(replay.contains("Определение участников"))
            assertEquals(result.raw, repeated.raw)
        } finally { directory.deleteRecursively() }
    }

    @Test fun foregroundServiceContinuesAfterActivityMovesToBackground() = runBlocking {
        val id = "instrumentation-background"
        val preferences = context.getSharedPreferences("sessions", 0)
        val previousSessions = preferences.getString("items", "[]")
        val sessions = org.json.JSONArray(previousSessions)
        sessions.put(JSONObject().put("id", id).put("name", "Проверка длинной записи")
            .put("uri", Uri.fromFile(File(context.filesDir, "window-test.mp3")).toString()))
        preferences.edit().putString("items", sessions.toString()).commit()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity").let { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        delay(1500)
        withTimeout(60_000) { while (ProcessingService.mutable.value.busy) delay(100) }
        val directory = ProcessingService.jobDirectory(context.filesDir, id)
        directory.deleteRecursively()
        try {
            context.startForegroundService(Intent(context, ProcessingService::class.java).putExtra("session_id", id)
                .putExtra("uri", Uri.fromFile(File(context.filesDir, "window-test.mp3")).toString()).putExtra("llm", false))
            withTimeout(30_000) { while (ProcessingService.mutable.value.sessionId != id || !ProcessingService.mutable.value.busy) delay(50) }
            withTimeout(60_000) { while (ProcessingService.mutable.value.stage != "Распознавание речи") delay(100) }
            delay(1500)
            assertTrue(ProcessingService.mutable.value.elapsedMs > 0)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.takeScreenshot().let { bitmap ->
                File(context.filesDir, "speed-processing-diagnostic.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            withTimeout(15_000) {
                fun hasTimer(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.text?.toString()?.contains("Прошло:") == true) return true
                    return (0 until node.childCount).any { hasTimer(node.getChild(it)) }
                }
                while (!hasTimer(automation.rootInActiveWindow)) delay(100)
            }
            automation.takeScreenshot().let { bitmap ->
                File(context.filesDir, "speed-processing-screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            withTimeout(120_000) { while (ProcessingService.mutable.value.busy) delay(250) }
            val state = ProcessingService.mutable.value
            assertNull(state.error)
            assertNotNull(state.result)
            assertTrue(File(directory, "result.json").isFile)
            File(directory, "result.json").copyTo(File(context.filesDir, "speed-background-result.json"), overwrite = true)
            assertFalse(File(directory, "audio.pcm").exists())
        } finally {
            if (ProcessingService.mutable.value.busy && ProcessingService.mutable.value.sessionId == id) {
                context.startService(Intent(context, ProcessingService::class.java).setAction("cancel"))
                withContext(NonCancellable) { withTimeoutOrNull(30_000) { while (ProcessingService.mutable.value.busy) delay(100) } }
            }
            directory.deleteRecursively()
            preferences.edit().putString("items", previousSessions).commit()
        }
    }
}

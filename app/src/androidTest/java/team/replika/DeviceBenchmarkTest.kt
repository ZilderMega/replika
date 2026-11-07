package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceBenchmarkTest {
    @Test fun baselineOnPhysicalDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = BundledModels(context).prepare()
        val input = File(context.filesDir, "window-test.mp3")
        assertTrue(input.isFile)
        val runs = JSONArray()
        repeat(2) {
            val result = LocalTranscriptionEngine(models, context.cacheDir).process(input, useLlm = false) {}
            assertTrue(result.raw.utterances.any { line -> line.endMs > 25_000 })
            runs.put(ProcessingService.resultJson(result).put("rtf", result.elapsedMs.toDouble() / result.durationMs))
        }
        File(context.filesDir, "device-baseline.json").writeText(JSONObject()
            .put("model", android.os.Build.MODEL).put("manufacturer", android.os.Build.MANUFACTURER)
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("processors", Runtime.getRuntime().availableProcessors())
            .put("runs", runs).toString(2))
    }
}

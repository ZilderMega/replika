package team.replika

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpeakerAnchorUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList()
        else listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun tree(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null) { if (current.isClickable) return current; current = current.parent }
        error("No clickable ancestor")
    }
    private fun click(node: AccessibilityNodeInfo) { assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private suspend fun find(text: String): AccessibilityNodeInfo {
        repeat(15) {
            tree().firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }?.let { return it }
            tree().filter { it.isScrollable }.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(250)
        }
        error("Missing UI text: $text")
    }
    @Test fun voiceSuggestionsRequireSelectionAndPreserveOriginalInRealUi() = runBlocking {
        val models = BundledModels(context).prepare()
        val id = "instrumentation-voice-anchor"
        val directory = ProcessingService.jobDirectory(context.filesDir, id).apply { deleteRecursively(); mkdirs() }
        val prefs = context.getSharedPreferences("sessions", 0)
        val previous = prefs.getString("items", "[]")!!
        try {
            val source = File(directory, "source.audio")
            File(context.filesDir, "window-test.mp3").copyTo(source)
            val result = LocalTranscriptionEngine(models, context.cacheDir).process(source, false, directory) {}
            val raw = result.raw.toJson()
            PipelineCheckpoint.writeAtomically(File(directory, "result.json"), PipelineCheckpoint.resultJson(result).toString())
            val records = SessionCodec.decode(previous)
            val session = Session(id, "Проверка подтверждённого голоса", android.net.Uri.fromFile(source).toString(), raw, result.formatted.toJson())
            prefs.edit().putString("items", SessionCodec.encode(listOf(session)+records)).commit()
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity").let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
            find(session.name)
            withTimeout(60000) { while (ProcessingService.mutable.value.busy || !ProcessingService.mutable.value.modelsReady) delay(100) }
            click(find(session.name))
            withTimeout(10000) { while (ProcessingService.mutable.value.sessionId != id || ProcessingService.mutable.value.busy) delay(100) }
            click(find("Править / распознать фрагмент"))
            withTimeout(10000) { while (tree().none { it.text?.toString() == "Редактор фрагмента" } || tree().count { it.isEditable } < 3) delay(100) }
            listOf("speaker_2", "3000", "9000").forEachIndexed { i, value ->
                assertTrue(tree().filter { it.isEditable }[i].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                delay(100)
            }
            delay(300)
            assertEquals(listOf("speaker_2", "3000", "9000"), tree().filter { it.isEditable }.take(3).map { it.text.toString() })
            click(find("Я прослушал участок"))
            delay(200)
            val previousRequest = ProcessingService.mutable.value.speakerSuggestionSerial
            click(find("Подтвердить голос и найти похожие реплики"))
            withTimeout(60000) { while (ProcessingService.mutable.value.speakerSuggestionSerial <= previousRequest || ProcessingService.mutable.value.busy) delay(100) }
            find("Проверка похожих голосов")
            assertNull(ProcessingService.mutable.value.error)
            assertTrue("The confirmed sample must produce a reviewable proposal", ProcessingService.mutable.value.speakerSuggestions.isNotEmpty())
            val apply = find("Применить выбранные ID")
            assertFalse(clickable(apply).isEnabled)
            assertNull(SessionCodec.decode(prefs.getString("items", "[]")!!).first { it.id == id }.edited)
            click(requireNotNull(tree().firstOrNull { it.isCheckable }))
            click(find("Прослушать с этого места")); delay(500)
            click(find("Остановить прослушивание"))
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.filesDir, "speaker-anchor-review.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            click(find("Применить выбранные ID")); delay(500)
            val saved = SessionCodec.decode(prefs.getString("items", "[]")!!).first { it.id == id }
            assertEquals(raw, saved.transcript)
            val reviewed = PipelineCheckpoint.transcript(JSONObject(requireNotNull(saved.edited)))
            assertEquals(result.formatted.utterances.map { it.text }, reviewed.utterances.map { it.text })
            assertEquals(result.formatted.utterances.map { it.startMs to it.endMs }, reviewed.utterances.map { it.startMs to it.endMs })
            assertEquals(1, reviewed.utterances.zip(result.formatted.utterances).count { it.first.speakerId != it.second.speakerId })
            File(context.filesDir, "speaker-anchor-ui-proof.json").writeText(JSONObject().put("device", android.os.Build.MODEL)
                .put("required_explicit_selection", true).put("original_unchanged", true)
                .put("selected_id_edits", 1).put("words_times_preserved", true).put("playback_invoked", true).toString(2))
        } finally {
            if (ProcessingService.mutable.value.busy && ProcessingService.mutable.value.sessionId == id) {
                context.startService(android.content.Intent(context, ProcessingService::class.java).setAction("cancel"))
                withContext(NonCancellable) { withTimeoutOrNull(30000) { while (ProcessingService.mutable.value.busy) delay(100) } }
            }
            prefs.edit().putString("items", previous).commit()
            directory.deleteRecursively()
        }
    }
}

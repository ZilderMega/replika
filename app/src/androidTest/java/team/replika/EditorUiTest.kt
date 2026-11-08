package team.replika

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.BundledModels
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun tree(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = current.parent
        }
        error("No clickable ancestor for ${node.text}")
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
    private fun imeShown() = shell("dumpsys input_method").contains("mInputShown=true")
    private suspend fun awaitIme(shown: Boolean, step: String) {
        val reached = withTimeoutOrNull(10_000) { while (imeShown() != shown) delay(100); true } == true
        if (!reached) File(context.filesDir, "keyboard-ime-dump.txt").writeText(shell("dumpsys input_method"))
        assertTrue("IME state=$shown at $step", reached)
    }
    @Test fun realTranscriptCanBeReviewedWithoutChangingOriginal() = runBlocking {
        BundledModels(context).prepare()
        val id = "instrumentation-editor"
        val prefs = context.getSharedPreferences("sessions", 0)
        val previous = prefs.getString("items", "[]")
        val directory = ProcessingService.jobDirectory(context.filesDir, id)
        directory.deleteRecursively()
        prefs.edit().putString("items", JSONArray(previous).put(JSONObject().put("id", id).put("name", "Демонстрация: русский диалог")
            .put("uri", Uri.fromFile(File(context.filesDir, "test.wav")).toString())).toString()).commit()
        val ui = instrumentation.uiAutomation
        try {
            ui.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity").let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
            delay(1500)
            withTimeout(60_000) { while (ProcessingService.mutable.value.busy || !ProcessingService.mutable.value.modelsReady) delay(100) }
            context.startForegroundService(Intent(context, ProcessingService::class.java).putExtra("session_id", id)
                .putExtra("uri", Uri.fromFile(File(context.filesDir, "test.wav")).toString()))
            withTimeout(30_000) { while (ProcessingService.mutable.value.sessionId != id || !ProcessingService.mutable.value.busy) delay(50) }
            withTimeout(90_000) { while (ProcessingService.mutable.value.busy) delay(100) }
            assertNull(ProcessingService.mutable.value.error)
            val original = requireNotNull(ProcessingService.mutable.value.result).raw.toJson()
            delay(800)
            var edit: AccessibilityNodeInfo? = null
            repeat(12) {
                if (edit == null) {
                    val tree = nodes(ui.rootInActiveWindow)
                    edit = tree.firstOrNull { it.text?.toString()?.contains("Править / распознать фрагмент") == true }
                    if (edit == null) { tree.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); delay(350) }
                }
            }
            click(requireNotNull(edit) { "Editor action is not visible" })
            delay(500)
            val textFields = nodes(ui.rootInActiveWindow).filter { it.isEditable }
            assertTrue(textFields.size >= 4)
            // Regression: dismissing the IME must keep both the editor and its draft.
            click(textFields.first())
            awaitIme(true, "focus speaker field")
            val speakerDraft = "speaker_keyboard_test"
            assertTrue(tree().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, speakerDraft)
            }))
            click(requireNotNull(tree().firstOrNull { it.text?.toString() == "Скрыть клавиатуру" }))
            awaitIme(false, "hide keyboard button")
            assertTrue(tree().any { it.text?.toString() == "Редактор фрагмента" })
            assertEquals(speakerDraft, tree().first { it.isEditable }.text.toString())
            click(tree().first { it.isEditable })
            awaitIme(true, "refocus speaker field")
            shell("input keyevent 4")
            awaitIme(false, "system Back")
            assertTrue("Back must hide the keyboard without closing the editor", tree().any { it.text?.toString() == "Редактор фрагмента" })
            assertEquals(speakerDraft, tree().first { it.isEditable }.text.toString())
            val changed = "Ручная правка стенограммы."
            assertTrue(tree().last { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, changed)
            }))
            delay(300)
            click(requireNotNull(tree().firstOrNull { it.text?.toString() == "Разделить реплику" }))
            delay(300)
            val splitAt = requireNotNull(ProcessingService.mutable.value.result).raw.utterances.first().let { (it.startMs + it.endMs) / 2 }
            val afterExpansion = tree().filter { it.isEditable }
            val body = requireNotNull(afterExpansion.firstOrNull { it.text?.toString()?.contains(changed) == true })
            val cursor = changed.indexOf(' ') + 1
            assertTrue(body.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }))
            assertTrue(afterExpansion.last().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, splitAt.toString())
            }))
            delay(300)
            click(requireNotNull(tree().firstOrNull { it.text?.toString() == "Создать две реплики" }))
            delay(300)
            ui.takeScreenshot().let { bitmap ->
                File(context.filesDir, "editor-screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            click(requireNotNull(nodes(ui.rootInActiveWindow).firstOrNull { it.text?.toString() == "Сохранить правки" }))
            delay(600)
            val record = JSONArray(prefs.getString("items", "[]")).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == id } }
            assertEquals(original, record.getString("transcript"))
            val reviewed = team.replika.engine.PipelineCheckpoint.transcript(JSONObject(record.getString("edited")))
            assertEquals(changed, reviewed.utterances.take(2).joinToString(" ") { it.text })
            assertEquals(splitAt, reviewed.utterances[0].endMs)
            assertEquals(splitAt, reviewed.utterances[1].startMs)
            File(context.filesDir, "editor-proof.json").writeText(JSONObject().put("original_unchanged", true)
                .put("manual_split", true).put("split_ms", splitAt)
                .put("raw", JSONObject(original)).put("reviewed", JSONObject(record.getString("edited"))).toString(2))
        } finally {
            if (ProcessingService.mutable.value.busy && ProcessingService.mutable.value.sessionId == id) {
                context.startService(Intent(context, ProcessingService::class.java).setAction("cancel"))
                withContext(NonCancellable) { withTimeoutOrNull(30_000) { while (ProcessingService.mutable.value.busy) delay(100) } }
            }
            prefs.edit().putString("items", previous).commit()
            directory.deleteRecursively()
        }
    }
}

package team.replika

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.BundledModels
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryPlayerIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui get() = instrumentation.uiAutomation
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList()
        else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun tree(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) ui.clearCache()
        return nodes(ui.rootInActiveWindow)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        error("Not clickable: ${node.text}")
    }
    private suspend fun find(text: String, scroll: Boolean = false): AccessibilityNodeInfo = withTimeoutOrNull(20_000) {
        while (true) {
            val tree = tree()
            tree.firstOrNull { it.text?.toString() == text }?.let { return@withTimeoutOrNull it }
            if (scroll) tree.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error(text)
    } ?: run {
        val dump = tree().joinToString("\n") { "${it.text} [enabled=${it.isEnabled}, clickable=${it.isClickable}]" }
        File(context.filesDir, "history-player-error.txt").writeText("Missing: $text\n$dump")
        error("Missing UI text: $text\n$dump")
    }
    private fun enabled(node: AccessibilityNodeInfo): Boolean {
        var parent: AccessibilityNodeInfo? = node
        while (parent != null) {
            if (!parent.isEnabled) return false
            if (parent.isClickable) return parent.isEnabled
            parent = parent.parent
        }
        return false
    }
    @Test fun mergedPlayerPausesSeeksAndHistoryDeletionCanBeCancelled() = runBlocking {
        BundledModels(context).prepare()
        val prefs = context.getSharedPreferences("sessions", 0)
        val previous = prefs.getString("items", "[]")
        val input = File(context.filesDir, "window-test.mp3")
        assertTrue(input.isFile)
        val session = Session("instrumentation-history", "Проверка плеера", Uri.fromFile(input).toString())
        prefs.edit().putString("items", SessionCodec.encode(listOf(session))).commit()
        try {
            ui.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity").let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
            withTimeout(60_000) { while (ProcessingService.mutable.value.busy) delay(100) }
            click(find(session.name, scroll = true))
            click(find("Слушать", scroll = true))
            withTimeout(20_000) { while (!enabled(find("Пауза"))) delay(100) }
            click(find("Пауза"))
            withTimeout(5000) { while (enabled(find("Пауза"))) delay(100) }
            click(find("+10 сек"))
            delay(300)
            assertTrue(tree().any { it.text?.toString()?.startsWith("00:1") == true })
            click(find("−10 сек"))
            delay(300)
            assertTrue(tree().any { it.text?.toString() == "00:00" })
            click(find("Слушать"))
            withTimeout(10_000) { while (!enabled(find("Пауза"))) delay(100) }
            click(find("Пауза"))
            click(find("Удалить из истории", scroll = true))
            click(find("Отмена"))
            assertEquals(listOf(session), SessionCodec.decode(prefs.getString("items", "[]")!!))
            click(find("Удалить из истории", scroll = true))
            click(find("Удалить"))
            delay(700)
            assertTrue(SessionCodec.decode(prefs.getString("items", "[]")!!).isEmpty())
            assertTrue(input.isFile)
            File(context.filesDir, "history-player-proof.json").writeText("""{"actual_media_player":true,"pause_resume_seek":true,"delete_cancel":true,"delete_history_only":true}""")
        } finally { prefs.edit().putString("items", previous).commit() }
    }
}

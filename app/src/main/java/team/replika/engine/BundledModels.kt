package team.replika.engine

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

/** Models ship inside the APK. Native runtimes need ordinary files, prepared once off the UI thread. */
class BundledModels(private val context: Context) {
    suspend fun prepare(progress: (Float) -> Unit = {}): ModelFiles = withContext(Dispatchers.IO) {
        val destination = File(context.filesDir, "models")
        val expectedManifest = context.assets.open("models/manifest.json").bufferedReader().use { it.readText() }
        val existing = ModelFiles(destination)
        if (existing.missing().isEmpty() && File(destination, "manifest.json").readText() == expectedManifest) {
            try {
                existing.verify()
                progress(1f)
                return@withContext existing
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { /* Repair from the bundled copy. */ }
        }
        val staging = File(context.filesDir, "models-preparing")
        val backup = File(context.filesDir, "models-previous")
        try {
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Не удалось подготовить приложение. Проверьте свободное место." }
            val totalBytes = ModelFiles.REQUIRED.sumOf { name ->
                context.assets.open("models/$name").use { it.available().toLong() }
            }.coerceAtLeast(1)
            var copied = 0L
            val buffer = ByteArray(1024 * 1024)
            ModelFiles.REQUIRED.forEach { name ->
                context.assets.open("models/$name").use { source ->
                    File(staging, name).outputStream().buffered().use { target ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            copied += count
                            progress((copied.toFloat() / totalBytes * .9f).coerceAtMost(.9f))
                        }
                    }
                }
            }
            ModelFiles(staging).verify()
            currentCoroutineContext().ensureActive()
            backup.deleteRecursively()
            if (destination.exists()) check(destination.renameTo(backup)) { "Не удалось обновить встроенные модели" }
            if (!staging.renameTo(destination)) {
                backup.renameTo(destination)
                error("Не удалось сохранить встроенные модели")
            }
            backup.deleteRecursively()
            progress(1f)
            ModelFiles(destination)
        } finally { staging.deleteRecursively() }
    }
}

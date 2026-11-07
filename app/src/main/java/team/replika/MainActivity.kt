package team.replika

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReplikaApp() }
    }
}

@Composable
private fun ReplikaApp() {
    val context = LocalContext.current
    val engine: EngineViewModel = viewModel()
    val state by engine.state.collectAsState()
    val view = LocalView.current
    DisposableEffect(state.busy) {
        view.keepScreenOn = state.busy
        onDispose { view.keepScreenOn = false }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) engine.export(uri)
    }
    var showFormatted by rememberSaveable { mutableStateOf(false) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedRecording by rememberSaveable { mutableStateOf<String?>(null) }
    val recording by RecordingService.state.collectAsState()
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions[android.Manifest.permission.RECORD_AUDIO] == true
        if (granted) context.startForegroundService(Intent(context, RecordingService::class.java))
        else message = "Для записи разрешите доступ к микрофону."
    }
    LaunchedEffect(recording) {
        recording.error?.let { message = it }
        recording.file?.let { path ->
            if (path == consumedRecording) return@let
            consumedRecording = path
            selectedUri = Uri.fromFile(java.io.File(path)).toString()
            selectedName = java.io.File(path).name
            engine.clearResult()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Аудиозапись"
                selectedUri = uri.toString()
                selectedName = name
                engine.clearResult()
                message = null
            } catch (e: Exception) { message = "Не удалось открыть файл. Выберите другую запись." }
        }
    }
    val colors = lightColorScheme(primary = Color(0xFF315C48), background = Color(0xFFF6F5F0), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF202921))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("реплика", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    AssistChip(onClick = {}, label = { Text("Локально") })
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Разговор становится\nпонятным текстом", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
                    Text("Кто говорил, что сказал и когда.\nВаши записи остаются на устройстве.", color = Color(0xFF657168), lineHeight = 24.sp)
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (selectedUri == null) "Начните с аудиозаписи" else "Запись выбрана", style = MaterialTheme.typography.titleLarge)
                        Text(if (selectedUri == null) "Выберите файл встречи, интервью или разговора." else selectedName)
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (selectedUri == null) "Выбрать аудиофайл" else "Выбрать другой файл")
                        }
                        OutlinedButton(onClick = {
                            if (recording.active) context.startService(Intent(context, RecordingService::class.java).setAction("stop"))
                            else microphonePermission.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS) else arrayOf(android.Manifest.permission.RECORD_AUDIO))
                        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (recording.active) "Остановить и сохранить запись" else "Записать разговор")
                        }
                        if (recording.active) Text("Идёт запись с микрофона. Можно заблокировать экран.")
                        val recordings = remember(recording) {
                            java.io.File(context.filesDir, "recordings").listFiles().orEmpty().filter { it.extension == "m4a" }.sortedByDescending { it.name }.take(5)
                        }
                        recordings.forEach { file ->
                            TextButton(onClick = { selectedUri = Uri.fromFile(file).toString(); selectedName = file.name; engine.clearResult() }, enabled = !state.busy && !recording.active) {
                                Text(file.name)
                            }
                        }
                        if (selectedUri != null) {
                            Button(onClick = { engine.transcribe(Uri.parse(selectedUri)) }, enabled = state.modelsReady && !state.busy && !recording.active,
                                modifier = Modifier.fillMaxWidth()) { Text("Расшифровать локально") }
                            OutlinedButton(onClick = {
                                try { val source = Uri.parse(selectedUri); val playable = if (source.scheme == "file") androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(requireNotNull(source.path))) else source; context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(playable, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                catch (e: Exception) { message = "На устройстве не найден проигрыватель для этой записи." }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Прослушать запись") }
                        }
                    }
                }
                message?.let { Text(it, color = colors.error) }
                state.error?.let { Text(it, color = colors.error) }
                if (!state.modelsReady && !state.busy && !recording.active) {
                    OutlinedButton(onClick = engine::prepareModels) { Text("Повторить подготовку") }
                }
                if (state.busy) {
                    Text(state.stage)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Оставьте приложение открытым. Отмена нейросетевого этапа может занять некоторое время.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = engine::cancel) { Text("Отменить") }
                } else if (state.stage.isNotBlank()) Text(state.stage)
                state.result?.let { result ->
                    Text("Расшифровка", style = MaterialTheme.typography.titleLarge)
                    Text("Обработка: ${result.elapsedMs / 1000} с · Аудио: ${result.durationMs / 1000} с · RTF: ${"%.2f".format(result.elapsedMs.toDouble() / result.durationMs.coerceAtLeast(1))}")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = showFormatted, onCheckedChange = { showFormatted = it })
                        Text("Пунктуация локальной LLM")
                    }
                    Button(onClick = { exportPicker.launch("replika-transcript.json") }) { Text("Экспорт исходной расшифровки JSON") }
                    if (result.raw.utterances.isEmpty()) Text("Речь не обнаружена.")
                    (if (showFormatted) result.formatted else result.raw).utterances.forEach { line ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${if (line.speakerId == "speaker_unknown") "Неопределённый участник / перекрытие" else "Спикер " + line.speakerId.removePrefix("speaker_")} · ${formatTime(line.startMs)}–${formatTime(line.endMs)}", fontWeight = FontWeight.SemiBold)
                                Text(line.text)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Рабочее пространство", style = MaterialTheme.typography.titleLarge)
                    Text("01   Аудиозапись", fontWeight = FontWeight.Medium)
                    Text("02   Текст и участники", color = Color(0xFF657168))
                    Text("03   Экспорт с временными метками", color = Color(0xFF657168))
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Локальный аудиодвижок · 0.4", fontWeight = FontWeight.Medium)
                    Text("Длительные записи обрабатываются частями. Исходные слова хранятся отдельно от пунктуации LLM. Одновременная речь может быть распознана неполностью.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF657168))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String = "%02d:%02d.%03d".format(ms / 60000, ms / 1000 % 60, ms % 1000)

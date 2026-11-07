package team.replika

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class Session(val id: String, val name: String, val uri: String, val transcript: String? = null, val formatted: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContent { App() }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val engine: EngineViewModel = viewModel()
    val engineState by engine.state.collectAsState()
    val view = LocalView.current
    DisposableEffect(engineState.busy) {
        view.keepScreenOn = engineState.busy
        onDispose { view.keepScreenOn = false }
    }
    val prefs = remember { context.getSharedPreferences("sessions", 0) }
    val sessions = remember {
        mutableStateListOf<Session>().apply {
            try {
                val data = JSONArray(prefs.getString("items", "[]"))
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    add(Session(item.getString("id"), item.getString("name"), item.getString("uri"), item.optString("transcript").ifEmpty { null }, item.optString("formatted").ifEmpty { null }))
                }
            } catch (_: Exception) { }
        }
    }
    fun save() {
        val data = JSONArray()
        sessions.forEach { data.put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri).put("transcript", it.transcript ?: "").put("formatted", it.formatted ?: "")) }
        prefs.edit().putString("items", data.toString()).apply()
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFormatted by remember(selectedId) { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val recording by RecordingService.state.collectAsState()
    var consumedRecording by rememberSaveable { mutableStateOf<String?>(null) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[android.Manifest.permission.RECORD_AUDIO] == true) context.startForegroundService(Intent(context, RecordingService::class.java))
        else message = "Для записи разрешите доступ к микрофону."
    }
    LaunchedEffect(recording) {
        recording.error?.let { message = it }
        recording.file?.let { path ->
            if (path != consumedRecording) {
                consumedRecording = path
                val file = java.io.File(path)
                val session = Session(UUID.randomUUID().toString(), file.name, Uri.fromFile(file).toString())
                sessions.add(0, session); save(); engine.clearResult(); selectedId = session.id
            }
        }
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val selected = sessions.firstOrNull { it.id == selectedId }
    LaunchedEffect(engineState.result) {
        val result = engineState.result
        if (result != null && selectedId != null) {
            val index = sessions.indexOfFirst { it.id == selectedId }
            if (index >= 0) { sessions[index] = sessions[index].copy(transcript = result.raw.toJson(), formatted = result.formatted.toJson()); save() }
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Аудиозапись"
            val session = Session(UUID.randomUUID().toString(), name, uri.toString())
            sessions.add(0, session); save(); engine.clearResult(); selectedId = session.id; message = null
        } catch (_: Exception) { message = "Не удалось открыть файл. Выберите другую запись." }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            val json = selected?.transcript ?: error("Нет результата")
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("Нет доступа")
            message = "JSON сохранён."
        } catch (_: Exception) { message = "Не удалось сохранить JSON." }
    }
    fun play(offset: Int? = null) {
        try {
            if (offset == null) {
                when (playbackAction(player != null, player?.isPlaying == true)) {
                    PlaybackAction.Resume -> { player?.start(); return }
                    PlaybackAction.None -> return
                    PlaybackAction.Open -> Unit
                }
            }
            player?.release()
            player = MediaPlayer().apply { setDataSource(context, Uri.parse(selected!!.uri)); prepare(); seekTo(offset ?: 0); start() }
            message = null
        } catch (_: Exception) { player?.release(); player = null; message = "Не удалось воспроизвести запись. Проверьте доступ к файлу." }
    }
    val ink = Color(0xFF252321)
    val muted = Color(0xFF625C56)
    val paper = Color(0xFFF5F0E8)
    val accent = Color(0xFF9B3F2E)
    val typography = Typography().let { base ->
        val family = FontFamily(Font(R.font.manrope))
        base.copy(bodyLarge = base.bodyLarge.copy(fontFamily = family), bodyMedium = base.bodyMedium.copy(fontFamily = family), titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family), headlineMedium = base.headlineMedium.copy(fontFamily = family), labelLarge = base.labelLarge.copy(fontFamily = family))
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = accent, background = paper, surface = paper, onSurface = ink), typography = typography) {
        Scaffold(containerColor = paper) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LogoMark()
                            Text("РЕПЛИКА", fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = ink)
                        }
                        Text("OFFLINE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = ink, thickness = 1.dp)
                }
                if (selected == null) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("АУДИО → ТЕКСТ", fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = accent)
                        Spacer(Modifier.height(14.dp))
                        Text("Разговоры,\nкоторые важно\nсохранить.", fontFamily = FontFamily.Serif, fontSize = 39.sp, lineHeight = 44.sp, letterSpacing = (-1).sp, color = ink)
                        Spacer(Modifier.height(16.dp))
                        Text("Выберите запись, чтобы подготовить её к расшифровке.", fontSize = 18.sp, color = muted, lineHeight = 27.sp)
                    }
                    item {
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !engineState.busy && !recording.active, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White), shape = RoundedCornerShape(4.dp)) {
                            Icon(painterResource(R.drawable.solar_add), null, Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)); Text("Добавить аудиозапись", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    item {
                        OutlinedButton(onClick = {
                            if (recording.active) context.startService(Intent(context, RecordingService::class.java).setAction("stop"))
                            else microphone.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS) else arrayOf(android.Manifest.permission.RECORD_AUDIO))
                        }, enabled = !engineState.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(4.dp)) {
                            Text(if (recording.active) "Остановить запись" else "Записать с микрофона", fontSize = 17.sp)
                        }
                        if (recording.active) Text("Идёт запись с микрофона", fontSize = 16.sp)
                    }
                    item { Spacer(Modifier.height(12.dp)); HorizontalDivider(color = ink, thickness = 1.dp); Spacer(Modifier.height(18.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("История", fontFamily = FontFamily.Serif, fontSize = 29.sp, color = ink); Text("ВСЕГО: ${sessions.size}", fontSize = 13.sp, color = muted, fontWeight = FontWeight.Bold) } }
                    if (sessions.isEmpty()) item { Column(Modifier.padding(vertical = 16.dp)) { Text("Здесь будут ваши записи", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text("Добавьте первую, чтобы начать.", color = muted, fontSize = 16.sp) } }
                    items(sessions, key = { it.id }) { session ->
                        Surface(onClick = { engine.clearResult(); selectedId = session.id; message = null }, enabled = !engineState.busy && !recording.active, color = paper, modifier = Modifier.fillMaxWidth()) {
                            Column { HorizontalDivider(color = Color(0xFFCEC3B6)); Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(painterResource(R.drawable.solar_play), null, Modifier.size(32.dp), tint = accent)
                                Column(Modifier.weight(1f)) { Text(session.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text(if (session.transcript == null) "Ожидает обработки" else "Готово", color = muted, fontSize = 16.sp) }
                                Text("↗", fontSize = 24.sp, color = accent)
                            } }
                        }
                    }
                } else {
                    item { TextButton(onClick = { player?.release(); player = null; engine.clearResult(); selectedId = null; message = null }, enabled = !engineState.busy, contentPadding = PaddingValues(0.dp)) { Icon(painterResource(R.drawable.solar_back), null); Text("История", fontSize = 17.sp) } }
                    item { Text("АУДИОЗАПИСЬ", color = accent, fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text(selected.name, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold) }
                    item { Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { HorizontalDivider(color = ink); Text("Прослушивание", fontFamily = FontFamily.Serif, fontSize = 27.sp); Text("Запись хранится на вашем устройстве", color = muted, fontSize = 17.sp, lineHeight = 25.sp); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play() }, shape = RoundedCornerShape(4.dp), modifier = Modifier.heightIn(min = 56.dp)) { Icon(painterResource(R.drawable.solar_play), null); Spacer(Modifier.width(8.dp)); Text("Слушать", fontSize = 17.sp) }
                        OutlinedButton(onClick = { player?.pause() }, shape = RoundedCornerShape(4.dp), modifier = Modifier.heightIn(min = 56.dp)) { Text("Пауза", fontSize = 17.sp) }
                    } } }
                    if (selected.transcript == null) {
                        item { Button(onClick = { player?.release(); player = null; engine.transcribe(Uri.parse(selected.uri)) }, enabled = engineState.modelsReady && !engineState.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(4.dp)) { Text("Начать обработку", fontSize = 18.sp) } }
                    } else {
                        item { Text("Стенограмма", fontFamily = FontFamily.Serif, fontSize = 29.sp); Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = showFormatted, onCheckedChange = { showFormatted = it }, enabled = selected.formatted != null); Spacer(Modifier.width(10.dp)); Text("Пунктуация LLM", fontSize = 16.sp) } }
                        item { Button(onClick = { exporter.launch("transcript.json") }) { Icon(painterResource(R.drawable.solar_export), null); Spacer(Modifier.width(8.dp)); Text("Экспорт исходного JSON") } }
                        val lines = try { JSONObject(if (showFormatted) selected.formatted ?: selected.transcript else selected.transcript).getJSONArray("transcript") } catch (_: Exception) { JSONArray() }
                        if (lines.length() == 0) item { Text("Речь не обнаружена.", fontSize = 17.sp) }
                        items(lines.length()) { i ->
                            val line = lines.getJSONObject(i)
                            Column { TextButton(onClick = { play(line.optLong("start_ms").coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }) { Text("${line.optString("speaker_id")} · ${line.optLong("start_ms")} мс") }; Text(line.optString("text")) }
                        }
                    }
                }
                if (engineState.busy) item {
                    Text(engineState.stage, fontSize = 17.sp)
                    LinearProgressIndicator(progress = { engineState.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("Оставьте приложение открытым. Отмена может занять некоторое время.", fontSize = 16.sp, lineHeight = 24.sp)
                    OutlinedButton(onClick = engine::cancel) { Text("Отменить", fontSize = 17.sp) }
                }
                engineState.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 17.sp); if (!engineState.modelsReady && !engineState.busy) TextButton(onClick = engine::prepareModels) { Text("Повторить подготовку") } } }
                message?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 17.sp, lineHeight = 25.sp) } }
            }
        }
    }
}

@Composable
private fun LogoMark() {
    Box(Modifier.size(42.dp).background(Color(0xFF9B3F2E)), contentAlignment = Alignment.Center) {
        Text("р.", color = Color.White, fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 30.sp)
    }
}

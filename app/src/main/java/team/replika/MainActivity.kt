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
import team.replika.engine.clockText
import team.replika.engine.PipelineCheckpoint
import team.replika.engine.ProcessingOptions
import kotlinx.coroutines.delay

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
                addAll(SessionCodec.decode(prefs.getString("items", "[]") ?: "[]"))
            } catch (_: Exception) { }
        }
    }
    fun save() {
        prefs.edit().putString("items", SessionCodec.encode(sessions)).apply()
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFormatted by remember(selectedId) { mutableStateOf(true) }
    var showReviewed by remember(selectedId) { mutableStateOf(true) }
    var search by remember(selectedId) { mutableStateOf("") }
    var onlyUncertain by remember(selectedId) { mutableStateOf(false) }
    var options by remember { mutableStateOf(ProcessingOptions()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Pair<Int, Transcript>?>(null) }
    var voiceReview by remember { mutableStateOf<Triple<String, Transcript, Long>?>(null) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var useLlm by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val recording by RecordingService.state.collectAsState()
    var consumedRecording by rememberSaveable { mutableStateOf(prefs.getString("consumed_recording", null)) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[android.Manifest.permission.RECORD_AUDIO] == true) context.startForegroundService(Intent(context, RecordingService::class.java))
        else message = "Для записи разрешите доступ к микрофону."
    }
    LaunchedEffect(recording) {
        recording.error?.let { message = it }
        recording.file?.let { path ->
            if (path != consumedRecording) {
                consumedRecording = path
                prefs.edit().putString("consumed_recording", path).apply()
                val file = java.io.File(path)
                val session = Session(UUID.randomUUID().toString(), file.name, Uri.fromFile(file).toString())
                sessions.add(0, session); save(); engine.clearResult(); selectedId = session.id
            }
        }
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedId }
    val activeState = engineState.takeIf { it.sessionId == selectedId }
    LaunchedEffect(activeState?.sessionId, activeState?.options) {
        if (activeState != null && !engineState.busy) options = activeState.options
    }
    LaunchedEffect(activeState?.sessionId, activeState?.useLlm, activeState?.resumable, engineState.busy) {
        if (activeState?.resumable == true) useLlm = activeState.useLlm
    }
    val live = activeState?.preview
    val rawJson = remember(live, selected?.transcript) { live?.raw?.toJson() ?: selected?.transcript }
    val formattedJson = remember(live, selected?.formatted) { live?.formatted?.toJson() ?: selected?.formatted }
    val displayTranscript = remember(rawJson, formattedJson, selected?.edited, showFormatted, showReviewed) {
        try {
            val source = if (showReviewed && selected?.edited != null) selected.edited else if (showFormatted) formattedJson ?: rawJson else rawJson
            PipelineCheckpoint.transcript(JSONObject(source ?: "{}"))
        } catch (_: Exception) { Transcript(emptyList()) }
    }
    val displayJson = remember(displayTranscript) { displayTranscript.toJson() }
    val lines = remember(displayJson) {
        try { JSONObject(displayJson).getJSONArray("transcript") }
        catch (_: Exception) { JSONArray() }
    }
    val uncertainCount = remember(displayTranscript) { displayTranscript.utterances.count { it.speakerId == "speaker_unknown" || it.speakerId.startsWith("speaker_review_") } }
    val visibleLines = remember(displayTranscript, search, onlyUncertain) { displayTranscript.utterances.indices.filter {
        val line = displayTranscript.utterances[it]
        (!onlyUncertain || line.speakerId == "speaker_unknown" || line.speakerId.startsWith("speaker_review_")) &&
            (search.isBlank() || line.text.contains(search, ignoreCase = true) || line.speakerId.contains(search, ignoreCase = true))
    } }
    LaunchedEffect(selectedId, engineState.modelsReady, engineState.busy) {
        if (selectedId != null && engineState.modelsReady && !engineState.busy) engine.selectSession(selectedId!!)
    }
    LaunchedEffect(engineState.sessionId) {
        if (engineState.busy && sessions.any { it.id == engineState.sessionId }) selectedId = engineState.sessionId
    }
    LaunchedEffect(engineState.result) {
        val result = engineState.result
        if (result != null && engineState.sessionId != null) {
            val index = sessions.indexOfFirst { it.id == engineState.sessionId }
            if (index >= 0) {
                val previous = sessions[index]
                val raw = result.raw.toJson()
                sessions[index] = previous.copy(transcript = raw, formatted = result.formatted.toJson(), edited = previous.edited.takeIf { previous.transcript == raw })
                save()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(playing, player) {
        while (playing) {
            if (!seeking) position = try { player?.currentPosition ?: 0 } catch (_: IllegalStateException) { 0 }
            delay(500)
        }
    }
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
            val json = exportJson
            requireNotNull(json) { "Нет результата" }
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("Нет доступа")
            message = "JSON сохранён."
        } catch (_: Exception) { message = "Не удалось сохранить JSON." }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun beginProcessing(punctuation: Boolean, forceSpeakers: Boolean = false) {
        voiceReview = null
        val session = selected ?: return
        player?.release(); player = null; playing = false; preparing = false; position = 0; duration = 0
        engine.transcribe(Uri.parse(session.uri), session.id, punctuation, options, forceSpeakers)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notification_requested", false)) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun play(offset: Int? = null) {
        try {
            if (preparing) return
            if (offset == null) {
                when (playbackAction(player != null, player?.isPlaying == true)) {
                    PlaybackAction.Resume -> { player?.start(); playing = true; return }
                    PlaybackAction.None -> return
                    PlaybackAction.Open -> Unit
                }
            }
            player?.release()
            player = null
            playing = false; preparing = true; position = 0; duration = 0
            val next = MediaPlayer()
            player = next
            next.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                duration = ready.duration.coerceAtLeast(0)
                position = boundedSeek(offset ?: 0, duration)
                ready.seekTo(position); ready.start(); playing = true; preparing = false
            }
            next.setOnCompletionListener { completed -> if (player === completed) { playing = false; position = duration } }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) { player = null; playing = false; preparing = false; message = "Не удалось воспроизвести запись. Проверьте доступ к файлу." }
                failed.release(); true
            }
            next.setDataSource(context, Uri.parse(selected!!.uri)); next.prepareAsync()
            message = null
        } catch (_: Exception) { player?.release(); player = null; playing = false; preparing = false; message = "Не удалось воспроизвести запись. Проверьте доступ к файлу." }
    }
    val ink = Color(0xFF252321)
    val muted = Color(0xFF625C56)
    val paper = Color(0xFFF5F0E8)
    fun pauseVoicePreview() {
        if (preparing) { player?.release(); player = null; preparing = false }
        else try { player?.pause() } catch (_: IllegalStateException) { }
        playing = false
    }
    val accent = Color(0xFF9B3F2E)
    val typography = Typography().let { base ->
        val family = FontFamily(Font(R.font.manrope))
        base.copy(bodyLarge = base.bodyLarge.copy(fontFamily = family), bodyMedium = base.bodyMedium.copy(fontFamily = family), titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family), headlineMedium = base.headlineMedium.copy(fontFamily = family), labelLarge = base.labelLarge.copy(fontFamily = family))
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = accent, background = paper, surface = paper, onSurface = ink), typography = typography) {
        voiceReview?.takeIf { !engineState.busy && it.first == selectedId && engineState.speakerSuggestionSerial >= it.third }?.let { review ->
            SpeakerSuggestionsDialog(engineState.speakerSuggestions, engineState.error ?: message,
                onListen = { play(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }, onPause = ::pauseVoicePreview, onApply = { suggestions ->
                require(displayTranscript.toJson() == review.second.toJson()) { "Стенограмма изменилась. Повторите поиск." }
                val result = TranscriptTools.applySpeakerSuggestions(review.second, suggestions)
                val index = sessions.indexOfFirst { it.id == review.first }
                require(index >= 0)
                sessions[index] = sessions[index].copy(edited = result.toJson()); save(); showReviewed = true; pauseVoicePreview(); voiceReview = null
            }, onClose = { pauseVoicePreview(); voiceReview = null })
        }
        editor?.let { selection ->
            val line = selection.second.utterances[selection.first]
            val duration = activeState?.preview?.durationMs ?: selection.second.utterances.maxOfOrNull { it.endMs } ?: line.endMs
            TranscriptEditor(line, engineState, duration,
                onRefine = { target, mixed, separate, denoise, turns -> selected?.let { engine.refine(Uri.parse(it.uri), it.id, target, mixed, separate, denoise, turns) } },
                onConfirmVoice = { target -> selected?.let {
                    require(target.endMs-target.startMs in 2000..9000 && target.speakerId.matches(Regex("speaker_[1-9][0-9]*"))) { "Выберите 2–9 секунд и подтверждённый ID, например speaker_2" }
                    val request = engine.confirmVoice(Uri.parse(it.uri), it.id, target, selection.second) ?: error("Дождитесь завершения текущей обработки")
                    voiceReview = Triple(it.id, selection.second, request)
                    editor = null
                } },
                onSave = { replacement, allSpeaker ->
                    val base = if (allSpeaker) TranscriptTools.reassignSpeaker(selection.second, line.speakerId, replacement.first().speakerId) else selection.second
                    val result = TranscriptTools.replace(base, selection.first, replacement, duration)
                    val index = sessions.indexOfFirst { it.id == selectedId }
                    require(index >= 0)
                    sessions[index] = sessions[index].copy(edited = result.toJson()); save(); showReviewed = true; editor = null
                }, cancel = engine::cancel, onClose = { editor = null })
        }
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
                        Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !engineState.busy && !recording.active, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White), shape = RoundedCornerShape(4.dp)) {
                            Icon(painterResource(R.drawable.solar_add), null, Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)); Text("Добавить аудио или видео", fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    item { TextButton(onClick = { player?.release(); player = null; playing = false; preparing = false; position = 0; duration = 0; engine.clearResult(); selectedId = null; message = null }, enabled = !engineState.busy, contentPadding = PaddingValues(0.dp)) { Icon(painterResource(R.drawable.solar_back), null); Text("История", fontSize = 17.sp) } }
                    item { Text("АУДИОЗАПИСЬ", color = accent, fontSize = 14.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text(selected.name, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold) }
                    if (engineState.busy) item { ProcessingProgress(engineState, engine::cancel) }
                    else if (engineState.sessionId == selectedId && engineState.elapsedMs > 0) item {
                        Text("Время обработки: ${clockText(engineState.elapsedMs)}", color = muted)
                    }
                    item { Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { HorizontalDivider(color = ink); Text("Прослушивание", fontFamily = FontFamily.Serif, fontSize = 27.sp); Text("Запись хранится на вашем устройстве", color = muted, fontSize = 17.sp, lineHeight = 25.sp); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play() }, enabled = !preparing, shape = RoundedCornerShape(4.dp), modifier = Modifier.heightIn(min = 56.dp)) { Icon(painterResource(R.drawable.solar_play), null); Spacer(Modifier.width(8.dp)); Text(if (preparing) "Подготовка…" else "Слушать", fontSize = 17.sp) }
                        OutlinedButton(onClick = { player?.pause(); playing = false }, enabled = playing, shape = RoundedCornerShape(4.dp), modifier = Modifier.heightIn(min = 56.dp)) { Text("Пауза", fontSize = 17.sp) }
                    }
                    Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = { value -> seeking = true; position = boundedSeek(value.toInt(), duration) }, onValueChangeFinished = { player?.seekTo(boundedSeek(position, duration)); seeking = false }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = player != null && !preparing && duration > 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatPlaybackTime(position)); Text(formatPlaybackTime(duration)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { position = skipPlayback(position, -10_000, duration); player?.seekTo(position) }, enabled = player != null && !preparing && duration > 0, shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f)) { Text("−10 сек", fontSize = 16.sp) }
                        OutlinedButton(onClick = { position = skipPlayback(position, 10_000, duration); player?.seekTo(position) }, enabled = player != null && !preparing && duration > 0, shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f)) { Text("+10 сек", fontSize = 16.sp) }
                    }
                    } }
                    if (!engineState.busy) item {
                        Text("Участников: ${if (options.speakers == 0) "авто" else options.speakers.toString()}")
                        Slider(options.speakers.toFloat(), { options = options.copy(speakers = it.toInt()) }, valueRange = 0f..8f, steps = 7)
                        Text("Авто — определить голоса; 1 — вся запись с одним участником; 2–8 — верхняя граница числа голосов. Неоднозначные участки требуют проверки.", color = muted, fontSize = 13.sp)
                        if (selected.transcript != null && activeState?.resumable != true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(options.diarizationWorkers == 2, { options = options.copy(diarizationWorkers = if (it) 2 else 1) }, enabled = options.provider == "cpu" && options.threads >= 2 && engineState.modelsReady)
                                Text("Готовить голоса двумя блоками — эксперимент")
                            }
                            Text("Требует больше памяти. Готовый кэш сопоставляется без нативных блоков; для первой подготовки ускорение зависит от устройства.", color = muted, fontSize = 13.sp)
                            OutlinedButton(onClick = { beginProcessing(activeState?.useLlm == true, true) }, enabled = engineState.modelsReady) { Text("Пересчитать участников") }
                            Text("Сохраняет распознанные слова. Если голосовые образцы уже подготовлены, сопоставляет их без повторной обработки аудио. Для старых записей первый пересчёт готовит образцы и может быть долгим. Правки можно экспортировать заранее.", color = muted, fontSize = 13.sp)
                        }
                    }
                    if (!engineState.busy && (selected.transcript == null || activeState?.resumable == true)) {
                        item {
                            TextButton(onClick = { advanced = !advanced }) { Text("Настройки качества и скорости") }
                            if (advanced) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(options.denoise, { options = options.copy(denoise = it) }); Text("Очистить фоновый шум") }
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(options.filterPauses, { options = options.copy(filterPauses = it) }); Text("Пропускать паузы (VAD)") }
                                Text("Потоков: ${options.threads}")
                                Slider(options.threads.toFloat(), { options = options.copy(threads = it.toInt()) }, valueRange = 1f..8f, steps = 6)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(options.diarizationWorkers == 2, { options = options.copy(diarizationWorkers = if (it) 2 else 1) }, enabled = options.provider == "cpu" && options.threads >= 2)
                                    Text("Два блока одновременно — эксперимент")
                                }
                                Text("Делит выбранные CPU-потоки между двумя блоками. Требует больше памяти; выгода зависит от устройства. По умолчанию выключено.", color = muted, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(options.provider == "cpu", { options = options.copy(provider = "cpu") }, label = { Text("CPU") })
                                    FilterChip(options.provider == "nnapi", { options = options.copy(provider = "nnapi") }, label = { Text("NNAPI") })
                                }
                                Text("NNAPI — экспериментальный режим: ускорение зависит от устройства. Очистка шума может менять качество речи; исходный файл сохраняется.", color = muted)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = useLlm, onCheckedChange = { useLlm = it })
                                Spacer(Modifier.width(10.dp)); Text("Пунктуация через LLM", fontSize = 16.sp)
                            }
                            Text(if (useLlm) "Генеративная модель: обработка займёт больше времени." else "Быстрый режим: текст, участники и пунктуация небольшой моделью.", color = muted)
                            Button(onClick = { beginProcessing(useLlm) }, enabled = engineState.modelsReady, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(4.dp)) { Text(if (activeState?.resumable == true) "Продолжить обработку" else "Начать обработку", fontSize = 18.sp) }
                        }
                    }
                    if (rawJson != null) {
                        item {
                            Text(if (engineState.busy || activeState?.resumable == true) "Промежуточная стенограмма" else "Стенограмма", fontFamily = FontFamily.Serif, fontSize = 29.sp)
                            if (engineState.busy) Text("Текст появляется по мере обработки. Участники и пунктуация уточняются на следующих этапах.", color = muted)
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = showFormatted, onCheckedChange = { showFormatted = it }, enabled = formattedJson != null); Spacer(Modifier.width(10.dp)); Text("Пунктуация", fontSize = 16.sp) }
                            Text("Если слово распознано неверно, прослушайте реплику и уточните выбранный участок через многоязычную модель.", color = muted, fontSize = 13.sp)
                            if (selected.edited != null) Row(verticalAlignment = Alignment.CenterVertically) { Switch(showReviewed, { showReviewed = it }); Spacer(Modifier.width(10.dp)); Text("Мои правки") }
                            if (!engineState.busy && selected.transcript != null && activeState?.useLlm != true && activeState?.resumable != true) {
                                OutlinedButton(onClick = { beginProcessing(true) }, enabled = engineState.modelsReady) { Text("Обработать пунктуацию LLM") }
                            }
                        }
                        item {
                            Button(onClick = { exportJson = rawJson; exporter.launch("transcript-original.json") }) { Icon(painterResource(R.drawable.solar_export), null); Spacer(Modifier.width(8.dp)); Text("Экспорт исходного JSON") }
                            OutlinedButton(onClick = { exportJson = displayJson; exporter.launch("transcript-reviewed.json") }) { Text("Экспорт версии на экране") }
                            OutlinedTextField(search, { search = it }, label = { Text("Найти слово или участника") }, modifier = Modifier.fillMaxWidth())
                            if (uncertainCount > 0) FilterChip(onlyUncertain, { onlyUncertain = !onlyUncertain }, label = { Text("Проверить участника: $uncertainCount") })
                        }
                        if (lines.length() == 0) item { Text(if (engineState.busy || activeState?.resumable == true) "В обработанной части речь пока не обнаружена." else "Речь не обнаружена.", fontSize = 17.sp) }
                        items(visibleLines, key = { it }) { i ->
                            val line = lines.getJSONObject(i)
                            Column {
                                val start = line.optLong("start_ms").coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                                val end = line.optLong("end_ms").coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                                TextButton(onClick = { play(start) }) { Text("${speakerLabel(line.optString("speaker_id"))} · ${formatPlaybackTime(start)}–${formatPlaybackTime(end)}", fontSize = 16.sp) }
                                Text(line.optString("text"), fontSize = 18.sp, lineHeight = 27.sp)
                                if (!engineState.busy && activeState?.resumable != true) TextButton(onClick = { editor = i to displayTranscript }) { Text("Править / распознать фрагмент") }
                            }
                        }
                    }
                    item { TextButton(onClick = { confirmDelete = true }, enabled = !engineState.busy) { Text("Удалить из истории", fontSize = 16.sp, color = accent) } }
                }
                if (engineState.busy && selected == null) item { ProcessingProgress(engineState, engine::cancel) }
                engineState.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 17.sp); if (!engineState.modelsReady && !engineState.busy) TextButton(onClick = engine::prepareModels) { Text("Повторить подготовку") } } }
                message?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 17.sp, lineHeight = 25.sp) } }
            }
        }
    }
    if (confirmDelete && selected != null) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить запись из истории?") }, text = { Text("Расшифровка будет удалена из истории. Сам аудиофайл останется на устройстве.") },
            confirmButton = { TextButton(onClick = {
                player?.release(); player = null; playing = false; preparing = false
                val remaining = removeSession(sessions, selected.id)
                sessions.clear(); sessions.addAll(remaining); save()
                engine.clearResult(); selectedId = null; confirmDelete = false
            }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
    }
}

@Composable
private fun ProcessingProgress(state: EngineUiState, cancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.stage, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (state.stageTotal > 0) {
            val fraction = (state.stageCompleted.toFloat() / state.stageTotal).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("Этап выполнен на ${(fraction * 100).toInt()}%")
        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.detail.isNotBlank()) Text(state.detail)
        Text("Прошло: ${clockText(state.elapsedMs)} · текущий этап: ${clockText(state.stageElapsedMs)}")
        if (state.unitKey.isNotBlank()) Text("Текущий участок обрабатывается: ${clockText(state.unitElapsedMs)}")
        Text(state.remainingMs?.let { "До конца текущего этапа ≈ ${clockText(it)}" } ?: "Оцениваем оставшееся время этапа…")
        Text("Можно свернуть приложение. Готовые части сохраняются.", fontSize = 15.sp)
        OutlinedButton(onClick = cancel) { Text("Приостановить", fontSize = 17.sp) }
    }
}

@Composable
private fun LogoMark() {
    Box(Modifier.size(42.dp).background(Color(0xFF9B3F2E)), contentAlignment = Alignment.Center) {
        Text("р.", color = Color.White, fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 30.sp)
    }
}

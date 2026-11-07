package team.replika

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import team.replika.engine.clockText

private data class DraftLine(val speaker: String, val start: String, val end: String, val text: TextFieldValue) {
    fun parse() = Utterance(speaker.trim(), start.toLongOrNull() ?: error("Начало — целое число миллисекунд"),
        end.toLongOrNull() ?: error("Конец — целое число миллисекунд"), text.text.trim())
}
private fun Utterance.draft() = DraftLine(speakerId, startMs.toString(), endMs.toString(), TextFieldValue(text))

@Composable
fun TranscriptEditor(line: Utterance, state: EngineUiState, durationMs: Long,
    onRefine: (Utterance, Boolean, Boolean, Boolean) -> Unit, onSave: (List<Utterance>, Boolean) -> Unit,
    cancel: () -> Unit, onClose: () -> Unit) {
    var drafts by remember(line) { mutableStateOf(listOf(line.draft())) }
    var allSpeaker by remember(line) { mutableStateOf(false) }
    var splitting by remember(line) { mutableStateOf(false) }
    var splitTime by remember(line) { mutableStateOf("") }
    var mixed by remember { mutableStateOf(true) }
    var denoise by remember { mutableStateOf(false) }
    var separate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requestedAfter by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.fragment, state.fragmentSerial, state.busy) {
        if (!state.busy && state.fragment != null && requestedAfter != null && state.fragmentSerial > requestedAfter!!) {
            drafts = state.fragment.utterances.map { it.draft() }; requestedAfter = null
        }
    }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text("Редактор фрагмента") },
        text = {
            Column(Modifier.heightIn(max = 570.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Правки сохраняются отдельно от исходной расшифровки. Время указано в миллисекундах.")
                drafts.forEachIndexed { index, draft ->
                    if (drafts.size > 1) Text("Голос ${index + 1}: проверьте участника", style = MaterialTheme.typography.titleMedium)
                    fun update(value: DraftLine) { drafts = drafts.toMutableList().also { it[index] = value } }
                    OutlinedTextField(draft.speaker, { update(draft.copy(speaker = it)) }, label = { Text("ID участника") }, enabled = !state.busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft.start, { update(draft.copy(start = it)) }, label = { Text("Начало, мс") }, modifier = Modifier.weight(1f), enabled = !state.busy)
                        OutlinedTextField(draft.end, { update(draft.copy(end = it)) }, label = { Text("Конец, мс") }, modifier = Modifier.weight(1f), enabled = !state.busy)
                    }
                    OutlinedTextField(draft.text, { update(draft.copy(text = it)) }, label = { Text("Текст") }, minLines = 3, enabled = !state.busy)
                }
                if (!state.busy && drafts.size == 1) {
                    TextButton(onClick = { splitting = !splitting }) { Text("Разделить реплику") }
                    if (splitting) {
                        Text("Поставьте курсор в тексте на смене говорящего и укажите её время. После разделения назначьте ID обеим частям.")
                        OutlinedTextField(splitTime, { splitTime = it }, label = { Text("Смена голоса, мс") })
                        OutlinedButton(onClick = {
                            try {
                                val draft = drafts.single()
                                val original = draft.parse().copy(text = draft.text.text)
                                val boundary = splitTime.toLongOrNull() ?: error("Укажите время смены голоса в миллисекундах")
                                drafts = TranscriptTools.split(original, boundary, draft.text.selection.start).map { it.draft() }
                                allSpeaker = false; splitting = false; error = null
                            } catch (e: Exception) { error = e.message }
                        }) { Text("Создать две реплики") }
                    }
                }
                if (!state.busy && drafts.size == 1 && line.speakerId != "speaker_unknown" && !line.speakerId.startsWith("speaker_review_")) {
                    Row { Checkbox(allSpeaker, { allSpeaker = it }); Text("Применить ID ко всем репликам «${line.speakerId}»") }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${state.stage} · прошло ${clockText(state.elapsedMs)}")
                    TextButton(onClick = cancel) { Text("Остановить") }
                } else {
                    Row { Checkbox(mixed, { mixed = it }); Text("Уточнить иноязычную речь") }
                    Row { Checkbox(denoise, { denoise = it }); Text("Очистить фоновый шум") }
                    Row { Checkbox(separate, { separate = it }); Text("Разделить наложенные голоса") }
                    Text("Повторная обработка — до 30 с; разделение голосов — до 6 с. При необходимости сузьте границы выше. Разделение экспериментальное: проверьте слова и ID обоих голосов.")
                    OutlinedButton(onClick = {
                        try {
                            val target = drafts.first().parse()
                            require(target.endMs > target.startMs && target.endMs <= durationMs) { "Проверьте границы фрагмента" }
                            require(target.endMs - target.startMs <= if (separate) 6000 else 30000) { "Сузьте выбранный интервал" }
                            requestedAfter = state.fragmentSerial; error = null
                            onRefine(target, mixed, separate, denoise)
                        } catch (e: Exception) { error = e.message }
                    }) { Text("Распознать выбранный участок заново") }
                }
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = {
                try { onSave(drafts.map { it.parse() }, allSpeaker && drafts.size == 1); error = null } catch (e: Exception) { error = e.message }
            }) { Text("Сохранить правки") }
        },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text("Закрыть") } })
}

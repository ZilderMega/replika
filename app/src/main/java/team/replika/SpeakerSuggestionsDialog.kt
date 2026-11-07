package team.replika

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.replika.engine.SpeakerSuggestion
import team.replika.engine.clockText

@Composable
fun SpeakerSuggestionsDialog(suggestions: List<SpeakerSuggestion>, failure: String?,
    onListen: (Long) -> Unit, onPause: () -> Unit, onApply: (List<SpeakerSuggestion>) -> Unit, onClose: () -> Unit) {
    val checked = remember(suggestions) { mutableStateListOf<SpeakerSuggestion>() }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Проверка похожих голосов") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Найдено предложений: ${suggestions.size}. Прослушайте спорные места перед применением. Меняются только выбранные ID в версии правок; текст, времена и исходник сохраняются.")
            if (suggestions.size > 20) Text("Показаны первые 20. После проверки можно повторить поиск для остальных.")
            suggestions.take(20).forEach { suggestion ->
                Row {
                    Checkbox(suggestion in checked, { if (it) checked += suggestion else checked -= suggestion })
                    Column {
                        Text("${clockText(suggestion.original.startMs)} · ${speakerLabel(suggestion.original.speakerId)} → ${speakerLabel(suggestion.speakerId)}")
                        Text(suggestion.original.text.take(160))
                        TextButton(onClick = { onListen(suggestion.original.startMs) }) { Text("Прослушать с этого места") }
                    }
                }
            }
            TextButton(onClick = onPause) { Text("Остановить прослушивание") }
            (error ?: failure)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = checked.isNotEmpty(), onClick = {
            try { onApply(checked.toList()) } catch (e: Exception) { error = e.message }
        }) { Text("Применить выбранные ID") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}

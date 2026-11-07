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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReplikaApp() }
    }
}

@Composable
private fun ReplikaApp() {
    val context = LocalContext.current
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Аудиозапись"
                selectedUri = uri.toString()
                selectedName = name
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
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (selectedUri == null) "Выбрать аудиофайл" else "Выбрать другой файл")
                        }
                        if (selectedUri != null) {
                            OutlinedButton(onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(selectedUri), "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                catch (e: Exception) { message = "На устройстве не найден проигрыватель для этой записи." }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Прослушать запись") }
                        }
                    }
                }
                message?.let { Text(it, color = colors.error) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Рабочее пространство", style = MaterialTheme.typography.titleLarge)
                    Text("01   Аудиозапись", fontWeight = FontWeight.Medium)
                    Text("02   Текст и участники", color = Color(0xFF657168))
                    Text("03   Экспорт с временными метками", color = Color(0xFF657168))
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Первоначальная версия · 0.1", fontWeight = FontWeight.Medium)
                    Text("Выбор и прослушивание файла уже доступны. Распознавание и разделение говорящих появятся после подключения локальных моделей.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF657168))
                }
            }
        }
    }
}

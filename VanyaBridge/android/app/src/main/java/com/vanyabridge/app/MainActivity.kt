package com.vanyabridge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var model: BridgeModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[BridgeModel::class.java]
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { BridgeScreen(model, ::manualChat, ::openImage) }
            }
        }
    }
    override fun onStart() { super.onStart(); model.foreground(true) }
    override fun onStop() { model.foreground(false); super.onStop() }
    private fun manualChat(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Запрос", text))
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))) }
    }
    private fun openImage(url: String) {
        val parsed = Uri.parse(url)
        if (parsed.scheme == "https" && parsed.host == "upload.wikimedia.org")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
    }
}

@Composable
private fun BridgeScreen(model: BridgeModel, manualChat: (String) -> Unit, openImage: (String) -> Unit) {
    val state by model.state.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf("Ultra Free") }
    var draft by rememberSaveable { mutableStateOf("") }
    var approval by remember { mutableStateOf<JSONObject?>(null) }
    var lastSent by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.sentVersion) {
        if (state.sentVersion != lastSent) {
            if (draft.trim() == state.sentText) draft = ""
            lastSent = state.sentVersion
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp)) {
        Text("VanyaBridge", style = MaterialTheme.typography.headlineSmall)
        Text(state.status, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Чат", "Связь", "Доступ").forEachIndexed { index, title ->
                TextButton(onClick = { page = index }) { Text(title) }
            }
        }
        if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error)
        when (page) {
            1 -> Connection(state, model, Modifier.weight(1f))
            2 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Разрешения меняются только на ПК", style = MaterialTheme.typography.titleMedium)
                val permissions = state.snapshot.optJSONObject("permissions") ?: JSONObject()
                permissions.keys().forEach { name -> Text("$name: ${if (permissions.optBoolean(name)) "разрешено" else "выключено"}", Modifier.padding(6.dp)) }
                Text("Все изменения требуют отдельного подтверждения. История хранится на ПК; доступна история этого телефона.")
                Text("Planner / BrowserAgent / SystemAgent — программные компоненты. Standard: LLM пока не настроен.")
            }
            else -> {
                val agents = state.snapshot.optJSONArray("agents")
                Text((0 until (agents?.length() ?: 0)).joinToString(" · ") {
                    val agent = agents!!.getJSONObject(it)
                    "${agent.getString("name")}: ${agent.getString("state")}"
                }, style = MaterialTheme.typography.labelSmall)
                Row {
                    listOf("Ultra Free", "Standard").forEach { name ->
                        FilterChip(selected = mode == name, onClick = { mode = name }, label = { Text(name) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                val array = state.snapshot.optJSONArray("tasks")
                val tasks = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tasks.isEmpty()) item { Text("Попробуйте: «покажи файлы», «открой YouTube на компьютере», «открой VS Code».") }
                    items(tasks, key = { it.getString("id") }) { task ->
                        TaskCard(task, state.connected && !state.busy,
                            { approval = task }, { model.approve(task.getString("id"), false) },
                            { model.cancel(task.getString("id")) },
                            { id -> model.send("установи обои image:$id", mode) }, openImage)
                    }
                }
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= 2000) draft = it },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth(), maxLines = 3, label = { Text("Задача для ПК") })
                Row {
                    Button(onClick = { model.send(draft.trim(), mode) }, enabled = state.connected && !state.busy && draft.isNotBlank()) { Text("Отправить") }
                    TextButton(onClick = { manualChat(draft) }) { Text("В ChatGPT вручную") }
                }
            }
        }
    }
    approval?.let { task ->
        val plan = task.optJSONObject("plan")
        AlertDialog(onDismissRequest = { approval = null }, title = { Text("Разрешить действие на ПК?") },
            text = { Text("${plan?.optString("action")}: ${plan?.optString("argument")}") },
            confirmButton = { TextButton(onClick = { model.approve(task.getString("id"), true); approval = null }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = { approval = null }) { Text("Назад") } })
    }
}

@Composable
private fun Connection(state: BridgeState, model: BridgeModel, modifier: Modifier) {
    var address by rememberSaveable { mutableStateOf("https://192.168.1.100:8765") }
    var fingerprint by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var verified by rememberSaveable { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.paired) {
            Text("Сопряжённый ПК: ${state.address}")
            Text("Соединение восстанавливается автоматически, пока приложение открыто. Для отзыва токена удалите устройство на ПК.")
            Button(onClick = { model.forget() }, enabled = !state.busy) { Text("Забыть на телефоне") }
        } else {
            Text("На ПК откройте «Устройства». Оба устройства должны быть в одной Wi-Fi сети.")
            OutlinedTextField(address, { address = it; verified = false }, label = { Text("HTTPS адрес ПК") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fingerprint, { fingerprint = it; verified = false }, label = { Text("Полный SHA-256 сертификата с ПК") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it.take(6) }, label = { Text("Одноразовый код") }, modifier = Modifier.fillMaxWidth())
            Row {
                Checkbox(verified, { verified = it })
                Text("Я сверил полный отпечаток с экраном своего ПК")
            }
            Button(onClick = { model.pair(address, fingerprint, code) }, enabled = verified && !state.busy) { Text("Подтвердить на телефоне") }
            Text("После отправки подтвердите новое устройство на ПК. Код действует 120 секунд и только один раз.")
        }
    }
}

@Composable
private fun TaskCard(task: JSONObject, enabled: Boolean, approve: () -> Unit, deny: () -> Unit,
                     cancel: () -> Unit, wallpaper: (String) -> Unit, openImage: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val status = task.getString("state")
            Text(task.getString("input"), style = MaterialTheme.typography.titleMedium)
            Text("$status · ${task.getString("id").take(8)}", style = MaterialTheme.typography.labelSmall)
            val messages = task.getJSONArray("messages")
            repeat(messages.length()) { index ->
                val message = messages.getJSONObject(index)
                Text("${message.getString("agent")}: ${message.getString("text")}")
            }
            val result = task.optJSONObject("result")
            if (result?.has("os") == true) Text("ОС: ${result.optString("os")}; свободно: ${result.optLong("free_disk_bytes") / (1024 * 1024)} МиБ")
            result?.optJSONArray("files")?.let { files -> repeat(files.length()) { Text(files.getJSONObject(it).getString("name")) } }
            result?.optJSONArray("images")?.let { images -> repeat(images.length()) {
                val image = images.getJSONObject(it)
                Text(image.getString("title"))
                TextButton(onClick = { openImage(image.getString("url")) }) { Text("Посмотреть изображение") }
                TextButton(onClick = { wallpaper(image.getString("id")) }, enabled = enabled) { Text("Выбрать для обоев") }
            } }
            if (status == "WAITING_APPROVAL") Row {
                Button(onClick = approve, enabled = enabled) { Text("Разрешить") }
                TextButton(onClick = deny, enabled = enabled) { Text("Отклонить") }
            }
            if (status !in listOf("COMPLETED", "FAILED", "CANCELLED"))
                TextButton(onClick = cancel, enabled = enabled) { Text("Отменить задачу") }
        }
    }
}

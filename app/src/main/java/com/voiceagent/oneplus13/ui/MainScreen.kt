package com.voiceagent.oneplus13.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus

/**
 * Callbacks, которые MainActivity даёт экрану — реальные действия (управление
 * сервисом, системные разрешения, экран настроек), а не заглушки.
 */
data class AgentControls(
    val isAgentRunning: Boolean,
    val onStartAgent: () -> Unit,
    val onStopAgent: () -> Unit,
    val onSendText: (String) -> Unit,
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onOpenBatterySettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    // Системный оверлей-пузырь (виден поверх ЛЮБОГО приложения на телефоне,
    // не только внутри VoiceAgent) — см. overlay/OverlayBubbleController.kt.
    val canDrawOverlays: () -> Boolean,
    val onRequestOverlayPermission: () -> Unit,
    val isOverlayEnabled: () -> Boolean,
    val onSetOverlayEnabled: (Boolean) -> Unit,
    val isOverlayAlwaysVisible: () -> Boolean,
    val onSetOverlayAlwaysVisible: (Boolean) -> Unit,
    val onOpenWorkflowWorkshop: () -> Unit
)

private val QUICK_PROMPTS = listOf("Что на экране?", "Что это?", "Что сейчас играет?", "Что нового?", "Заверши лекцию")

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun MainScreen(windowSizeClass: WindowSizeClass, controls: AgentControls) {
    val context = LocalContext.current
    val uiPrefs = remember { UiPreferences(context) }
    var formFactorOverride by remember { mutableStateOf(uiPrefs.formFactorOverride) }
    val isWindowsHost = remember { isLikelyWindowsHost(context) }

    val formFactor = windowSizeClass.toFormFactor(
        override = formFactorOverride,
        isWindowsHost = isWindowsHost
    )

    var panelVisible by remember { mutableStateOf(false) }
    var controlsDialogVisible by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val statusText by AgentEventBus.lastStatus.collectAsState()

    // Реальные события агента (услышанное голосом, ответы, ошибки) стекаются
    // в тот же чат, что и текст, набранный руками через панель.
    LaunchedEffect(Unit) {
        AgentEventBus.events.collect { event ->
            when (event) {
                is AgentEvent.Heard -> messages.add(ChatMessage(event.text, isUser = true))
                is AgentEvent.Reply -> messages.add(ChatMessage(event.text, isUser = false))
                is AgentEvent.Error -> messages.add(ChatMessage("⚠ ${event.message}", isUser = false))
                is AgentEvent.ProjectReady -> messages.add(
                    ChatMessage(
                        text = "Готово — проект «${event.projectName}» собран в zip.",
                        isUser = false,
                        attachmentPath = event.zipPath,
                        attachmentLabel = java.io.File(event.zipPath).name
                    )
                )
                is AgentEvent.WakeDetected -> panelVisible = true
                else -> Unit
            }
        }
    }

    val handleSendText: (String) -> Unit = { text ->
        // Локально показываем сразу же — не ждём, пока событие вернётся с шины.
        messages.add(ChatMessage(text, isUser = true))
        controls.onSendText(text)
    }

    if (controlsDialogVisible) {
        ControlsDialog(
            controls = controls,
            currentOverride = formFactorOverride,
            onOverrideChange = { newOverride ->
                formFactorOverride = newOverride
                uiPrefs.formFactorOverride = newOverride
            },
            onDismiss = { controlsDialogVisible = false }
        )
    }

    when (formFactor) {
        DeviceFormFactor.PHONE -> PhoneLayout(
            panelVisible = panelVisible,
            isListening = controls.isAgentRunning,
            statusText = statusText,
            messages = messages,
            onFabClick = { panelVisible = true },
            onDismiss = { panelVisible = false },
            onSend = handleSendText,
            onOpenControls = { controlsDialogVisible = true }
        )
        DeviceFormFactor.TABLET_OR_DESKTOP -> DesktopLayout(
            panelVisible = panelVisible,
            isListening = controls.isAgentRunning,
            statusText = statusText,
            messages = messages,
            onFabClick = { panelVisible = true },
            onDismiss = { panelVisible = false },
            onSend = handleSendText,
            onOpenControls = { controlsDialogVisible = true }
        )
        DeviceFormFactor.WINDOWS -> WindowsLayout(
            panelVisible = panelVisible,
            isListening = controls.isAgentRunning,
            statusText = statusText,
            messages = messages,
            onFabClick = { panelVisible = true },
            onDismiss = { panelVisible = false },
            onSend = handleSendText,
            onOpenControls = { controlsDialogVisible = true }
        )
    }
}

@Composable
private fun PhoneLayout(
    panelVisible: Boolean,
    isListening: Boolean,
    statusText: String,
    messages: List<ChatMessage>,
    onFabClick: () -> Unit,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onOpenControls: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        HomeContent(statusText = statusText, onOpenControls = onOpenControls)

        FloatingSparkButton(
            isListening = isListening,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            onClick = onFabClick
        )

        AgentSiriPanel(
            isVisible = panelVisible,
            messages = messages,
            quickPrompts = QUICK_PROMPTS,
            onDismiss = onDismiss,
            onSend = onSend,
            isListening = isListening,
            dockedMode = false
        )
    }
}

@Composable
private fun DesktopLayout(
    panelVisible: Boolean,
    isListening: Boolean,
    statusText: String,
    messages: List<ChatMessage>,
    onFabClick: () -> Unit,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onOpenControls: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            HomeContent(statusText = statusText, onOpenControls = onOpenControls)

            FloatingSparkButton(
                isListening = isListening,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp),
                onClick = onFabClick
            )
        }

        if (panelVisible) {
            Box(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFF7F7F7))
            ) {
                AgentSiriPanel(
                    isVisible = true,
                    messages = messages,
                    quickPrompts = QUICK_PROMPTS,
                    onDismiss = onDismiss,
                    onSend = onSend,
                    isListening = isListening,
                    dockedMode = true
                )
            }
        }
    }
}

/**
 * Режим для Windows (WSA / плавающее окно / принудительно включённый виджет):
 * основной экран остаётся виден целиком (как на десктопе), а вызов агента —
 * не боковая колонка и не шторка, а компактная карточка в правом нижнем углу
 * поверх контента, без затемнения — ровно то поведение, которое на macOS/Windows
 * привычно у Siri или Cortana.
 */
@Composable
private fun WindowsLayout(
    panelVisible: Boolean,
    isListening: Boolean,
    statusText: String,
    messages: List<ChatMessage>,
    onFabClick: () -> Unit,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onOpenControls: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        HomeContent(statusText = statusText, onOpenControls = onOpenControls)

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            horizontalAlignment = Alignment.End
        ) {
            WindowsCornerPopup(
                isVisible = panelVisible,
                isListening = isListening,
                messages = messages,
                quickPrompts = QUICK_PROMPTS,
                onDismiss = onDismiss,
                onSend = onSend,
                onQuickPrompt = onSend
            )

            Spacer(modifier = Modifier.height(14.dp))

            FloatingSparkButton(
                isListening = isListening,
                size = 56.dp,
                onClick = { if (panelVisible) onDismiss() else onFabClick() }
            )
        }
    }
}

@Composable
private fun HomeContent(statusText: String, onOpenControls: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onOpenControls,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(imageVector = Icons.Filled.Settings, contentDescription = "Настройки и разрешения")
        }

        Column(
            modifier = Modifier.fillMaxSize().align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SparkIcon(size = 56.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Чем могу помочь?", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Статус: $statusText", color = Color.Gray)
        }
    }
}

/** Заменяет старый экран с кнопками разрешений — та же функциональность,
 *  но вызывается по кнопке-шестерёнке, а не занимает весь экран. */
@Composable
private fun ControlsDialog(
    controls: AgentControls,
    currentOverride: FormFactorOverride,
    onOverrideChange: (FormFactorOverride) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Агент и разрешения") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TextButton(onClick = if (controls.isAgentRunning) controls.onStopAgent else controls.onStartAgent) {
                    Text(if (controls.isAgentRunning) "Остановить агента" else "Запустить агента")
                }
                TextButton(onClick = controls.onOpenAccessibilitySettings) {
                    Text("Спец. доступ (Accessibility)")
                }
                TextButton(onClick = controls.onOpenNotificationSettings) {
                    Text("Доступ к уведомлениям")
                }
                TextButton(onClick = controls.onOpenBatterySettings) {
                    Text("Оптимизация батареи")
                }
                TextButton(onClick = controls.onOpenAppSettings) {
                    Text("Ключи и найденные токены")
                }
                TextButton(onClick = controls.onOpenWorkflowWorkshop) {
                    Text("Мастерская сценариев")
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Вид интерфейса", fontWeight = FontWeight.SemiBold)
                Text(
                    "Авто определяет форму по размеру окна. Если запускаете через " +
                        "Windows Subsystem for Android и авто не сработало — включите Windows вручную.",
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                FormFactorPicker(current = currentOverride, onChange = onOverrideChange)

                Spacer(modifier = Modifier.height(12.dp))
                Text("Оверлей поверх других приложений (OnePlus 13)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Пузырь-искра поверх ЛЮБОГО приложения, как чат-хед у Messenger — тапнуть, " +
                        "чтобы открыть тот же попап, что в Windows-режиме, потянуть — чтобы " +
                        "передвинуть. На OnePlus 13 (OxygenOS/ColorOS) разрешение выдаётся без " +
                        "рута, из системных настроек.",
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                OverlaySection(controls = controls)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun FormFactorPicker(current: FormFactorOverride, onChange: (FormFactorOverride) -> Unit) {
    val options = listOf(
        FormFactorOverride.AUTO to "Авто",
        FormFactorOverride.PHONE to "Телефон",
        FormFactorOverride.DESKTOP to "Боковая панель (планшет/DeX)",
        FormFactorOverride.WINDOWS to "Windows (виджет в углу)"
    )
    Column {
        options.forEach { (value, label) ->
            TextButton(onClick = { onChange(value) }) {
                Text((if (value == current) "● " else "○ ") + label)
            }
        }
    }
}

/**
 * Управление системным оверлеем: сначала разрешение "поверх других
 * приложений" (если ещё не выдано), затем сам переключатель, и — раз пузырь
 * теперь появляется по будильному слову, а не висит постоянно — способ
 * вернуть старое поведение, если так удобнее.
 */
@Composable
private fun OverlaySection(controls: AgentControls) {
    var hasPermission by remember { mutableStateOf(controls.canDrawOverlays()) }
    var enabled by remember { mutableStateOf(controls.isOverlayEnabled()) }
    var alwaysVisible by remember { mutableStateOf(controls.isOverlayAlwaysVisible()) }

    if (!hasPermission) {
        TextButton(onClick = {
            controls.onRequestOverlayPermission()
            // Пользователь вернётся из системных настроек в этот же диалог —
            // перечитываем разрешение, чтобы кнопка ниже стала активной.
            hasPermission = controls.canDrawOverlays()
        }) {
            Text("Разрешить показ поверх приложений")
        }
        Text(
            "После выдачи разрешения в системных настройках вернитесь сюда и включите пузырь.",
            color = Color.Gray
        )
        return
    }

    TextButton(onClick = {
        enabled = !enabled
        controls.onSetOverlayEnabled(enabled)
    }) {
        Text((if (enabled) "● " else "○ ") + "Плавающий пузырь включён")
    }

    if (enabled) {
        TextButton(onClick = {
            alwaysVisible = !alwaysVisible
            controls.onSetOverlayAlwaysVisible(alwaysVisible)
        }) {
            Text(
                (if (alwaysVisible) "● " else "○ ") +
                    if (alwaysVisible) "Висит постоянно" else "Появляется только по имени («Ваня»)"
            )
        }
        Text(
            if (alwaysVisible)
                "Пузырь виден на экране всё время, пока включён."
            else
                "Пузырь скрыт и появляется на пару секунд, когда услышит «Ваня» — " +
                    "и снова прячется, если разговор не продолжился.",
            color = Color.Gray
        )
    }
}

package com.voiceagent.oneplus13.overlay

import android.content.Context
import android.graphics.Rect
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewTreeObserver
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.ui.AgentPhase
import com.voiceagent.oneplus13.ui.ChatMessage
import com.voiceagent.oneplus13.ui.FloatingSparkButton
import com.voiceagent.oneplus13.ui.WindowsCornerPopup
import com.voiceagent.oneplus13.ui.rememberAgentPhase
import com.voiceagent.oneplus13.ui.theme.VoiceAgentTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val QUICK_PROMPTS = listOf("Что на экране?", "Что это?", "Что сейчас играет?", "Что нового?", "Заверши лекцию")

/**
 * Настоящий системный "пузырь" поверх ЛЮБОГО приложения — как чат-хед у
 * Messenger или как Siri/Cortana на десктопе, только теперь и на самом
 * телефоне. Использует [android.view.WindowManager] с
 * `TYPE_APPLICATION_OVERLAY`, для чего нужно разрешение "Отображение поверх
 * других приложений" (`Settings.canDrawOverlays`) — на OnePlus 13
 * (OxygenOS/ColorOS на базе стокового Android 14/15) оно выдаётся из системных
 * настроек в одно нажатие, рут не нужен.
 *
 * Живёт внутри [com.voiceagent.oneplus13.core.VoiceAgentService] и напрямую
 * дёргает тот же `AgentOrchestrator` — поэтому голос, текст из окна приложения
 * и текст из системного пузыря всегда идут в одного и того же агента.
 */
class OverlayBubbleController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSendText: (String) -> Unit,
    private val alwaysVisible: () -> Boolean = { false }
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private val messages = SnapshotStateList<ChatMessage>()

    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var eventsJob: Job? = null
    private var autoHideJob: Job? = null
    private var panelOpen = false

    fun isShowing(): Boolean = composeView != null

    /**
     * Показать пузырь на будильное слово и спрятать его снова само по себе
     * через [AUTO_HIDE_MS] бездействия — то самое "появляется только по
     * вызову", а не висит на экране постоянно. Пока открыт попап (пользователь
     * реально общается), автоскрытие приостановлено — см. [BubbleContent].
     */
    fun flashOnWake() {
        if (composeView == null) show()
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        if (panelOpen || alwaysVisible()) return
        autoHideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            if (!panelOpen && !alwaysVisible()) hide()
        }
    }

    private fun pauseAutoHide() {
        autoHideJob?.cancel()
    }

    fun show() {
        if (composeView != null) return
        if (!canDrawOverlays(context)) return // без разрешения WindowManager.addView просто упадёт

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val bubbleSizePx = (64 * density).roundToInt()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - bubbleSizePx - (16 * density).roundToInt()
            y = (metrics.heightPixels * 0.68f).roundToInt()
        }
        params = layoutParams

        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context)
        owner.attachToView(view)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

        view.setContent {
            // Оверлей живёт вне Activity — тему (ч/б Dark/Light) подключаем сами.
            VoiceAgentTheme {
                BubbleContent(
                    windowManager = windowManager,
                    view = view,
                    params = layoutParams,
                    messages = messages,
                    onSend = { text ->
                        messages.add(ChatMessage(text, isUser = true))
                        onSendText(text)
                    },
                    onPanelOpenChanged = { open ->
                        panelOpen = open
                        if (open) pauseAutoHide() else scheduleAutoHide()
                    }
                )
            }
        }

        runCatching { windowManager.addView(view, layoutParams) }.onFailure {
            view.disposeComposition()
            owner.onDestroy()
            params = null
            return
        }
        lifecycleOwner = owner
        composeView = view

        eventsJob = scope.launch {
            AgentEventBus.events.collect { event ->
                when (event) {
                    is AgentEvent.Heard -> {
                        messages.add(ChatMessage(event.text, isUser = true))
                        scheduleAutoHide()
                    }
                    is AgentEvent.Reply -> {
                        messages.add(ChatMessage(event.text, isUser = false))
                        scheduleAutoHide()
                    }
                    is AgentEvent.Error -> messages.add(ChatMessage("⚠ ${event.message}", isUser = false))
                    is AgentEvent.ProjectReady -> messages.add(
                        ChatMessage(
                            text = "Готово — проект «${event.projectName}» собран в zip.",
                            isUser = false,
                            attachmentPath = event.zipPath,
                            attachmentLabel = java.io.File(event.zipPath).name
                        )
                    )
                    else -> Unit
                }
            }
        }
    }

    fun hide() {
        panelOpen = false
        autoHideJob?.cancel()
        autoHideJob = null
        eventsJob?.cancel()
        eventsJob = null
        composeView?.let { runCatching { windowManager.removeView(it) }; it.disposeComposition() }
        composeView = null
        params = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        private const val AUTO_HIDE_MS = 20_000L

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Интент в системные настройки, где пользователь одним тапом выдаёт
         *  "Отображение поверх других приложений" — без рута, стандартный Android API. */
        fun overlayPermissionIntent(context: Context) =
            android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
    }
}

/**
 * Содержимое оверлея: кнопка-искра, которую можно перетаскивать пальцем в
 * любое место экрана (позиция двигает реальное окно через
 * `windowManager.updateViewLayout`, не изображение внутри Compose), и попап
 * над ней при тапе — тот же [WindowsCornerPopup], что и в Windows-режиме
 * приложения. Clickable обрабатывает тап, drag detector — только перетаскивание.
 */
@Composable
private fun BubbleContent(
    windowManager: WindowManager,
    view: ComposeView,
    params: WindowManager.LayoutParams,
    messages: SnapshotStateList<ChatMessage>,
    onSend: (String) -> Unit,
    onPanelOpenChanged: (Boolean) -> Unit
) {
    var panelVisible by remember { mutableStateOf(false) }
    val statusText by AgentEventBus.lastStatus.collectAsState()
    // Фаза с авто-сбросом: статус на шине сам не гаснет, иначе пульсация шла бы вечно.
    val phase = rememberAgentPhase(running = true, status = statusText)
    val isListening = phase == AgentPhase.LISTENING || phase == AgentPhase.THINKING
    val density = LocalDensity.current
    // Keep the user's requested position separate from temporary IME clamping.
    var preferredX by remember { mutableStateOf(params.x) }
    var preferredY by remember { mutableStateOf(params.y) }
    var availableHeightPx by remember { mutableStateOf(view.resources.displayMetrics.heightPixels) }
    val visibleFrame = remember { Rect() }

    fun fitToVisibleFrame() {
        view.getWindowVisibleDisplayFrame(visibleFrame)
        if (visibleFrame.width() <= 0 || visibleFrame.height() <= 0) return
        availableHeightPx = visibleFrame.height()
        val position = fitOverlayPosition(
            preferredX, preferredY, view.width, view.height,
            visibleFrame.left, visibleFrame.top, visibleFrame.right, visibleFrame.bottom
        )
        if (params.x != position.x || params.y != position.y) {
            params.x = position.x
            params.y = position.y
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { fitToVisibleFrame() }
        val initial = Runnable { fitToVisibleFrame() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.post(initial)
        onDispose {
            view.removeCallbacks(initial)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    fun applyFocusable(focusable: Boolean) {
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun snapToNearestEdge() {
        val metrics = view.resources.displayMetrics
        val margin = (12 * metrics.density).roundToInt()
        preferredX = if (params.x + view.width / 2 < metrics.widthPixels / 2) {
            margin
        } else {
            metrics.widthPixels - view.width - margin
        }
        preferredY = params.y
        fitToVisibleFrame()
    }

    fun setPanelVisible(visible: Boolean) {
        panelVisible = visible
        applyFocusable(visible)
        onPanelOpenChanged(visible)
    }

    Column(
        modifier = Modifier.heightIn(max = with(density) { availableHeightPx.toDp() }),
        horizontalAlignment = Alignment.End
    ) {
        WindowsCornerPopup(
            isVisible = panelVisible,
            isListening = isListening,
            messages = messages,
            quickPrompts = QUICK_PROMPTS,
            onDismiss = { setPanelVisible(false) },
            onSend = onSend,
            onQuickPrompt = onSend,
            modifier = Modifier.weight(1f, fill = false)
        )

        if (panelVisible) {
            Spacer(modifier = Modifier.height(12.dp))
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { snapToNearestEdge() }
                ) { change, dragAmount ->
                    change.consume()
                    preferredX = params.x + dragAmount.x.roundToInt()
                    preferredY = params.y + dragAmount.y.roundToInt()
                    fitToVisibleFrame()
                }
            }
        ) {
            // detectDragGestures consumes movement after touch slop; an ordinary tap
            // must reach clickable (onDragEnd is not called for a stationary tap).
            FloatingSparkButton(isListening = isListening, size = 56.dp,
                onClick = { setPanelVisible(!panelVisible) })
        }
    }
}

package com.voiceagent.oneplus13.core

import android.app.Notification
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.voiceagent.oneplus13.R
import com.voiceagent.oneplus13.VoiceAgentApp
import com.voiceagent.oneplus13.agent.AgentOrchestrator
import com.voiceagent.oneplus13.overlay.OverlayBubbleController
import com.voiceagent.oneplus13.ui.UiPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Always-on foreground service: keeps the microphone pipeline (STT) and the
 * orchestrator alive while the app is backgrounded, per Android's foreground
 * service rules for microphone access.
 *
 * Также хозяин системного оверлей-пузыря ([OverlayBubbleController]) — он
 * специально живёт здесь, а не в MainActivity, чтобы напрямую дёргать
 * `orchestrator` без bindService и продолжать работать, даже если экран
 * приложения закрыт (пузырь виден поверх любого другого приложения).
 */
class VoiceAgentService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    // Всё, что трогает окна/View (оверлей-пузырь: addView/removeView), обязано идти
    // в главном потоке. Раньше пузырь получал scope без диспетчера (= Default) —
    // show()/hide() тихо проваливались внутри runCatching, и пузырь не появлялся.
    private val uiScope = CoroutineScope(job + Dispatchers.Main.immediate)

    private lateinit var stt: STTEngine
    private lateinit var tts: TTSEngine
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var uiPrefs: UiPreferences

    private var overlay: OverlayBubbleController? = null
    private var listeningJob: Job? = null
    private var initFailed = false

    override fun onCreate() {
        super.onCreate()

        // Android 14+: foreground-сервис типа microphone без выданного RECORD_AUDIO падает
        // с SecurityException прямо в startForeground(). Проверяем заранее.
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            initFailed = true
            stopSelf()
            return
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID,
                buildNotification(getString(R.string.notif_listening)),
                if (android.os.Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }.onFailure {
            Log.e(TAG, "startForeground failed", it)
            initFailed = true
            stopSelf()
            return
        }

        try {
            stt = STTEngine(applicationContext)
            tts = TTSEngine(applicationContext)
            orchestrator = AgentOrchestrator(applicationContext, LLMClient(applicationContext), tts, scope)
            uiPrefs = UiPreferences(applicationContext)
        } catch (_: Exception) {
            Log.e(TAG, "Agent initialization failed; stored data was preserved")
            initFailed = true
            stopSelf()
            return
        }

        scope.launch { VoiceRuntime.phase.collect { Log.i("VA_ROUTE", "phase=${it.name}") } }

        stt.ensureModel(
            onReady = { scope.launch { if (job.isActive) startListeningLoop() } },
            onError = { scope.launch {
                Log.i("VA_ROUTE", "stt_model=unavailable")
                AgentEventBus.publish(AgentEvent.Error("Модель STT не загрузилась. Проверьте наличие модели в APK.", it))
            } }
        )

        // Если пользователь раньше включил оверлей и разрешение уже выдано —
        // поднимаем пузырь сразу вместе с сервисом, без повторного тапа в UI.
        if (uiPrefs.overlayEnabled && OverlayBubbleController.canDrawOverlays(applicationContext)) {
            startOverlay()
        }

        // Готовый проект — отдельное (не "вечное") уведомление с кнопками
        // "Открыть"/"Поделиться", чтобы забрать zip можно было сразу, даже
        // если экран приложения сейчас закрыт.
        scope.launch {
            AgentEventBus.events.collect { event ->
                if (event is AgentEvent.Error) Log.i("VA_ROUTE", "route_error")
                if (event is AgentEvent.ProjectReady) postProjectReadyNotification(event)
            }
        }
    }

    private fun startListeningLoop() {
        if (listeningJob?.isActive == true) return
        listeningJob = scope.launch {
            // Сбой распознавателя (ошибка Vosk, занятый микрофон) раньше уходил в
            // неперехваченное исключение корутины и валил всё приложение. Теперь —
            // сообщаем об ошибке и перезапускаем прослушивание с паузой.
            while (isActive) {
                try {
                    if (orchestrator.isLectureActive) delay(200) else listenOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "STT loop failed, restarting", e)
                    AgentEventBus.publish(AgentEvent.Error("Сбой распознавания речи, перезапускаю", e))
                }
                delay(RESTART_DELAY_MS)
            }
        }
    }

    private suspend fun listenOnce() {
        var firstCommand: String? = stt.awaitWakeCommand()
        AgentEventBus.publish(AgentEvent.WakeDetected)
        uiScope.launch { overlay?.flashOnWake() }
        while (!orchestrator.isLectureActive) {
            // first() closes/join-cleans the recorder before LLM/TTS starts.
            val cleaned = firstCommand?.takeIf { it.isNotBlank() } ?: withTimeoutOrNull(CONVERSATION_WINDOW_MS) {
                stt.listen().first { (commandAfterWake(it) ?: it).isNotBlank() }
                    .let { commandAfterWake(it) ?: it.trim() }
            } ?: return
            firstCommand = null
            AgentEventBus.publish(AgentEvent.Heard(cleaned))
            orchestrator.handleTranscript(cleaned, "voice")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (initFailed) START_NOT_STICKY else START_STICKY

    /** Включает/выключает системный оверлей-пузырь и запоминает выбор. Ничего
     *  не делает, если разрешение "поверх других приложений" ещё не выдано —
     *  UI должен сначала направить пользователя в системные настройки. */
    fun setOverlayEnabled(enabled: Boolean) {
        if (!::uiPrefs.isInitialized) return
        uiPrefs.overlayEnabled = enabled
        if (enabled) {
            if (OverlayBubbleController.canDrawOverlays(applicationContext)) startOverlay()
        } else {
            stopOverlay()
        }
    }

    fun isOverlayShowing(): Boolean = overlay?.isShowing() == true

    /** Включает/выключает "висит постоянно" против "появляется только по
     *  будильному слову" — применяется сразу, без перезапуска сервиса. */
    fun setOverlayAlwaysVisible(alwaysVisible: Boolean) {
        if (!::uiPrefs.isInitialized) return
        uiPrefs.overlayAlwaysVisible = alwaysVisible
        if (alwaysVisible) overlay?.show() else overlay?.hide()
    }

    fun isOverlayAlwaysVisible(): Boolean = ::uiPrefs.isInitialized && uiPrefs.overlayAlwaysVisible

    private fun startOverlay() {
        if (overlay != null) return
        overlay = OverlayBubbleController(
            context = applicationContext,
            scope = uiScope,
            onSendText = { text -> scope.launch { orchestrator.handleTranscript(text, "overlay") } },
            alwaysVisible = { uiPrefs.overlayAlwaysVisible }
        ).also {
            // По умолчанию пузырь появляется только по будильному слову
            // (flashOnWake из startListeningLoop). Явная настройка "показывать
            // всегда" возвращает старое поведение постоянно видимого пузыря.
            if (uiPrefs.overlayAlwaysVisible) it.show()
        }
    }

    private fun stopOverlay() {
        overlay?.hide()
        overlay = null
    }

    /**
     * Позволяет UI (MainScreen -> набранный текст в панели чата) отправлять
     * запрос в тот же оркестратор, что обрабатывает голос — без дублирования
     * логики распознавания намерений/действий.
     */
    inner class LocalBinder : Binder() {
        fun isReadyForText(): Boolean = !initFailed && job.isActive && ::orchestrator.isInitialized

        fun sendText(text: String) {
            if (!isReadyForText()) {
                Log.i("VA_UI", "text=rejected reason=service_not_ready")
                return
            }
            Log.i("VA_UI", "text=accepted")
            scope.launch { orchestrator.handleTranscript(text, "text") }
        }

        fun setOverlayEnabled(enabled: Boolean) = this@VoiceAgentService.setOverlayEnabled(enabled)
        fun isOverlayShowing(): Boolean = this@VoiceAgentService.isOverlayShowing()
        fun setOverlayAlwaysVisible(alwaysVisible: Boolean) = this@VoiceAgentService.setOverlayAlwaysVisible(alwaysVisible)
        fun isOverlayAlwaysVisible(): Boolean = this@VoiceAgentService.isOverlayAlwaysVisible()
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        job.cancel()
        if (::orchestrator.isInitialized) orchestrator.close()
        stopOverlay()
        if (::stt.isInitialized) stt.close()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, VoiceAgentApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    /** Отдельное уведомление на каждый готовый проект — с прямыми кнопками
     *  "Открыть"/"Поделиться", без похода в файловый менеджер вручную. */
    private fun postProjectReadyNotification(event: AgentEvent.ProjectReady) {
        val file = File(event.zipPath)
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val shareChooser = Intent.createChooser(shareIntent, "Поделиться проектом").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPending = PendingIntent.getActivity(this, event.projectName.hashCode(), openIntent, flags)
        val sharePending = PendingIntent.getActivity(this, event.projectName.hashCode() + 1, shareChooser, flags)

        val notification = NotificationCompat.Builder(this, VoiceAgentApp.CHANNEL_ID)
            .setContentTitle("Проект «${event.projectName}» готов")
            .setContentText("${file.name} — нажмите, чтобы открыть")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(0, "Открыть", openPending)
            .addAction(0, "Поделиться", sharePending)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(this).notify(event.projectName.hashCode(), notification) }
        catch (_: SecurityException) { Log.w(TAG, "Notification permission was revoked") }
    }

    companion object {
        private const val TAG = "VoiceAgentService"
        private const val RESTART_DELAY_MS = 2_000L
        private const val NOTIF_ID = 1001
        private const val CONVERSATION_WINDOW_MS = 20_000L
    }
}

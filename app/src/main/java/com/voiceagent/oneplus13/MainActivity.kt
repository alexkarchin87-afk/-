package com.voiceagent.oneplus13

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.voiceagent.oneplus13.account.AccountGateScreen
import com.voiceagent.oneplus13.account.AccountManager
import com.voiceagent.oneplus13.core.VoiceAgentService
import com.voiceagent.oneplus13.overlay.OverlayBubbleController
import com.voiceagent.oneplus13.ui.AgentControls
import com.voiceagent.oneplus13.ui.MainScreen
import com.voiceagent.oneplus13.ui.UiPreferences
import com.voiceagent.oneplus13.ui.theme.VoiceAgentTheme
import com.voiceagent.oneplus13.ui.workflow.WorkflowEditorActivity

/**
 * Точка входа. Реального взаимодействия с агентом здесь минимум — это делает
 * VoiceAgentService (голос) и AgentOrchestrator (логика). Activity отвечает
 * за: замок входа через Google (см. account/), запуск/остановку
 * foreground-сервиса, привязку к нему (чтобы прокинуть набранный вручную
 * текст в тот же оркестратор), системные разрешения и (де)активацию
 * системного оверлей-пузыря (см. overlay/OverlayBubbleController.kt).
 */
class MainActivity : ComponentActivity() {

    private var binder: VoiceAgentService.LocalBinder? by mutableStateOf(null)
    private var isAgentRunning by mutableStateOf(false)

    private val accountManager by lazy { AccountManager(this) }
    private var signedInEmail: String? by mutableStateOf(null)
    private var gateUnlocked by mutableStateOf(false)
    private var signInError: String? by mutableStateOf(null)

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (val signInResult = accountManager.handleSignInResult(result.data)) {
            is com.voiceagent.oneplus13.account.SignInResult.Success -> {
                signedInEmail = signInResult.account?.email ?: accountManager.ownerEmail()
                signInError = null
                // Свежий вход (не восстановленный из кэша) сразу пускает внутрь —
                // повторное подтверждение нужно только при холодном старте с уже
                // закэшированным аккаунтом (см. AccountGateScreen: "Продолжить").
                if (signInResult.account != null) gateUnlocked = true
            }
            is com.voiceagent.oneplus13.account.SignInResult.Failure -> {
                signInError = signInResult.message
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? VoiceAgentService.LocalBinder
            // Если пользователь раньше включил оверлей, а сервис только что
            // (пере)запустился — досинхронизируем фактическое состояние пузыря.
            if (UiPreferences(this@MainActivity).overlayEnabled) {
                binder?.setOverlayEnabled(true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        signedInEmail = accountManager.ownerEmail()

        // Если сервис уже был запущен раньше (например, после перезапуска
        // Activity), просто привязываемся к нему без повторного старта.
        if (isServiceRunning()) {
            isAgentRunning = true
            bindToService()
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)

            VoiceAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!gateUnlocked) {
                        AccountGateScreen(
                            signedInEmail = signedInEmail,
                            errorMessage = signInError,
                            onSignInClick = { signInLauncher.launch(accountManager.signInIntent) },
                            onContinueClick = { gateUnlocked = true },
                            onSignOutClick = {
                                accountManager.signOut { signedInEmail = null }
                            },
                            onSkipClick = { gateUnlocked = true }
                        )
                    } else {
                        MainScreen(
                            windowSizeClass = windowSizeClass,
                            controls = AgentControls(
                                isAgentRunning = isAgentRunning,
                                onStartAgent = ::startVoiceAgent,
                                onStopAgent = ::stopVoiceAgent,
                                onSendText = { text -> binder?.sendText(text) },
                                onOpenAccessibilitySettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onOpenNotificationSettings = {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                onOpenBatterySettings = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                    }
                                },
                                onOpenAppSettings = {
                                    startActivity(Intent(this, SettingsActivity::class.java))
                                },
                                canDrawOverlays = { OverlayBubbleController.canDrawOverlays(this) },
                                onRequestOverlayPermission = {
                                    startActivity(OverlayBubbleController.overlayPermissionIntent(this))
                                },
                                isOverlayEnabled = {
                                    UiPreferences(this).overlayEnabled
                                },
                                onSetOverlayEnabled = { enabled ->
                                    UiPreferences(this).overlayEnabled = enabled
                                    if (binder == null && enabled) {
                                        // Пузырю нужен запущенный сервис (он живёт внутри него) —
                                        // если агент ещё не запущен, стартуем его вместе с пузырём.
                                        startVoiceAgent()
                                    }
                                    binder?.setOverlayEnabled(enabled)
                                },
                                isOverlayAlwaysVisible = {
                                    binder?.isOverlayAlwaysVisible() ?: UiPreferences(this).overlayAlwaysVisible
                                },
                                onSetOverlayAlwaysVisible = { alwaysVisible ->
                                    UiPreferences(this).overlayAlwaysVisible = alwaysVisible
                                    binder?.setOverlayAlwaysVisible(alwaysVisible)
                                },
                                onOpenWorkflowWorkshop = {
                                    startActivity(Intent(this, WorkflowEditorActivity::class.java))
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    private fun startVoiceAgent() {
        val intent = Intent(this, VoiceAgentService::class.java)
        startForegroundService(intent)
        bindToService()
        isAgentRunning = true
    }

    private fun stopVoiceAgent() {
        if (binder != null) {
            unbindService(connection)
            binder = null
        }
        stopService(Intent(this, VoiceAgentService::class.java))
        isAgentRunning = false
    }

    private fun bindToService() {
        bindService(Intent(this, VoiceAgentService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == VoiceAgentService::class.java.name }
    }

    override fun onDestroy() {
        if (binder != null) {
            runCatching { unbindService(connection) }
        }
        super.onDestroy()
    }
}

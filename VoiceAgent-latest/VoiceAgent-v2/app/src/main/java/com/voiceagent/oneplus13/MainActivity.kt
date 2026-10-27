package com.voiceagent.oneplus13

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
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
import androidx.core.content.ContextCompat
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
    private var serviceBound = false

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

    // RECORD_AUDIO и POST_NOTIFICATIONS — runtime-разрешения, а нигде в коде их не
    // запрашивали: на Android 14+ запуск микрофонного сервиса без RECORD_AUDIO падал.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission()) {
            launchVoiceAgent()
        } else {
            Toast.makeText(
                this,
                "Без доступа к микрофону агент не сможет слушать. Разрешите его в настройках приложения.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? VoiceAgentService.LocalBinder
            android.util.Log.i("VA_UI", "service=connected")
            // Если пользователь раньше включил оверлей, а сервис только что
            // (пере)запустился — досинхронизируем фактическое состояние пузыря.
            if (UiPreferences(this@MainActivity).overlayEnabled) {
                binder?.setOverlayEnabled(true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.i("VA_UI", "service=disconnected")
            binder = null
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureSettingsAvailable()) return
        enableEdgeToEdge()

        signedInEmail = accountManager.ownerEmail()
        // Раньше замок спрашивал «Продолжить» при КАЖДОМ запуске — это выглядело как «регистрация
        // сбрасывается». Один тап по кэшированному аккаунту ничего не проверял, так что теперь
        // при живом входе (или осознанном «без входа») приложение просто открывается.
        gateUnlocked = accountManager.lastSignedInAccount() != null || accountManager.isLockSkipped()

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
                            onSkipClick = {
                                accountManager.setLockSkipped(true)
                                gateUnlocked = true
                            }
                        )
                    } else {
                        MainScreen(
                            windowSizeClass = windowSizeClass,
                            controls = AgentControls(
                                isAgentRunning = isAgentRunning,
                                onStartAgent = ::startVoiceAgent,
                                onStopAgent = ::stopVoiceAgent,
                                onSendText = { text -> binder?.sendText(text) },
                                canSendText = { binder?.isReadyForText() == true },
                                onOpenAccessibilitySettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onOpenNotificationSettings = {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                onOpenBatterySettings = { openBatteryOptimizationSettings() },
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

    /** Прямой запрос «не ограничивать работу в фоне» для этого приложения; общий список — запасной вариант. */
    private fun openBatteryOptimizationSettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun startVoiceAgent() {
        val missing = buildList {
            if (!hasMicPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) launchVoiceAgent() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun launchVoiceAgent() {
        val intent = Intent(this, VoiceAgentService::class.java)
        startForegroundService(intent)
        bindToService()
        isAgentRunning = true
    }

    private fun stopVoiceAgent() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        binder = null
        stopService(Intent(this, VoiceAgentService::class.java))
        isAgentRunning = false
    }

    private fun bindToService() {
        if (serviceBound) return
        serviceBound = bindService(Intent(this, VoiceAgentService::class.java), connection, Context.BIND_AUTO_CREATE)
        android.util.Log.i("VA_UI", if (serviceBound) "service=binding" else "service=bind_failed")
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == VoiceAgentService::class.java.name }
    }

    override fun onDestroy() {
        if (serviceBound) {
            runCatching { unbindService(connection) }
            serviceBound = false
        }
        binder = null
        super.onDestroy()
    }
}

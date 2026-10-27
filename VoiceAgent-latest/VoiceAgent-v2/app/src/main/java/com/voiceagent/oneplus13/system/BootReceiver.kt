package com.voiceagent.oneplus13.system

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.voiceagent.oneplus13.core.VoiceAgentService

/**
 * Запускает основной голосовой сервис (и вместе с ним — плавающий пузырь,
 * см. VoiceAgentService/OverlayBubbleController) сразу после включения
 * телефона, без необходимости вручную открывать приложение.
 *
 * BOOT_COMPLETED — системная трансляция, поэтому запуск foreground-сервиса
 * из BroadcastReceiver в ответ на неё разрешён политиками фоновых запусков
 * Android даже на 12+ (в отличие от произвольного стороннего триггера).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Android 14+ (API 34) запрещает стартовать foreground-сервис типа microphone из
        // BOOT_COMPLETED — попытка кончается ForegroundServiceStartNotAllowedException.
        // Там пользователь запускает агента кнопкой в приложении.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        runCatching {
            context.startForegroundService(Intent(context, VoiceAgentService::class.java))
        }
    }
}

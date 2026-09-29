package com.voiceagent.oneplus13.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        val serviceIntent = Intent(context, VoiceAgentService::class.java)
        context.startForegroundService(serviceIntent)
    }
}

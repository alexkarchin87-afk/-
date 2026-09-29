package com.voiceagent.oneplus13

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.voiceagent.oneplus13.core.CrashLogger

/** Application entry point: sets up crash logging and the single notification channel used by the foreground service. */
class VoiceAgentApp : Application() {

    companion object {
        const val CHANNEL_ID = "voice_agent_channel"
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_voice),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

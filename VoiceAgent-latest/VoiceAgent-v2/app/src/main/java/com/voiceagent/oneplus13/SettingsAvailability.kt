package com.voiceagent.oneplus13

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import com.voiceagent.oneplus13.core.security.SecretsStore

/** A transient Keystore/storage failure must never turn into an automatic data reset. */
internal fun ComponentActivity.ensureSettingsAvailable(): Boolean {
    return try {
        SecretsStore(this)
        com.voiceagent.oneplus13.core.UserMemory(this).allDetailed()
        true
    } catch (_: Exception) {
        AlertDialog.Builder(this)
            .setTitle("Не удалось открыть настройки")
            .setMessage("Данные не удалены. Разблокируйте устройство и повторите попытку. Если ошибка сохраняется, сохраните данные приложения перед восстановлением; очистка данных удалит и лекции.")
            .setPositiveButton("Повторить") { _, _ -> recreate() }
            .setNegativeButton("Закрыть") { _, _ -> finish() }
            .setCancelable(false)
            .show()
        false
    }
}

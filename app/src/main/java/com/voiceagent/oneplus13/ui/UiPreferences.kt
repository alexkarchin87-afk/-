package com.voiceagent.oneplus13.ui

import android.content.Context

/**
 * Ручной выбор формы интерфейса. AUTO — решает [toFormFactor] сам, по ширине
 * окна и best-effort детекту хоста (см. [isLikelyWindowsHost]). Остальные —
 * принудительно включают конкретный layout, на случай если автоопределение
 * ошиблось (например, в конкретной сборке Windows Subsystem for Android
 * система не отдаёт узнаваемых признаков).
 */
enum class FormFactorOverride {
    AUTO,
    PHONE,
    DESKTOP,
    WINDOWS
}

/**
 * Обычная (не зашифрованная) SharedPreferences-настройка того, как показывать
 * интерфейс. Никаких секретов тут нет — это чисто визуальный выбор, поэтому
 * он намеренно живёт отдельно от [com.voiceagent.oneplus13.core.security.SecretsStore].
 */
class UiPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var formFactorOverride: FormFactorOverride
        get() = runCatching {
            FormFactorOverride.valueOf(prefs.getString(KEY_OVERRIDE, null) ?: FormFactorOverride.AUTO.name)
        }.getOrDefault(FormFactorOverride.AUTO)
        set(value) {
            prefs.edit().putString(KEY_OVERRIDE, value.name).apply()
        }

    /** Показывать ли системный оверлей-пузырь (см. overlay/OverlayBubbleController.kt)
     *  поверх других приложений — как на Windows, только на самом телефоне. */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()
        }

    /** false (по умолчанию) — пузырь скрыт и появляется только по будильному
     *  слову, затем сам прячется через паузу бездействия. true — старое
     *  поведение: висит на экране постоянно, пока включён. */
    var overlayAlwaysVisible: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ALWAYS_VISIBLE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_ALWAYS_VISIBLE, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "voice_agent_ui_prefs"
        private const val KEY_OVERRIDE = "form_factor_override"
        private const val KEY_OVERLAY_ENABLED = "overlay_bubble_enabled"
        private const val KEY_OVERLAY_ALWAYS_VISIBLE = "overlay_always_visible"
    }
}

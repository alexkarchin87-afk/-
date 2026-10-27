package com.voiceagent.oneplus13.ui

import android.content.Context
import com.voiceagent.oneplus13.core.security.SecretsStore

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

/** UI preferences share the authoritative settings transaction with secrets and memory. */
class UiPreferences(context: Context) {

    private val prefs = SecretsStore(context)

    var formFactorOverride: FormFactorOverride
        get() = runCatching {
            FormFactorOverride.valueOf(prefs.get(SecretsStore.KEY_FORM_FACTOR) ?: FormFactorOverride.AUTO.name)
        }.getOrDefault(FormFactorOverride.AUTO)
        set(value) {
            prefs.put(SecretsStore.KEY_FORM_FACTOR, value.name)
        }

    /** Показывать ли системный оверлей-пузырь (см. overlay/OverlayBubbleController.kt)
     *  поверх других приложений — как на Windows, только на самом телефоне. */
    var overlayEnabled: Boolean
        get() = prefs.get(SecretsStore.KEY_OVERLAY_ENABLED) == "true"
        set(value) {
            prefs.put(SecretsStore.KEY_OVERLAY_ENABLED, value.toString())
        }

    /** false (по умолчанию) — пузырь скрыт и появляется только по будильному
     *  слову, затем сам прячется через паузу бездействия. true — старое
     *  поведение: висит на экране постоянно, пока включён. */
    var overlayAlwaysVisible: Boolean
        get() = prefs.get(SecretsStore.KEY_OVERLAY_ALWAYS_VISIBLE) == "true"
        set(value) {
            prefs.put(SecretsStore.KEY_OVERLAY_ALWAYS_VISIBLE, value.toString())
        }

}

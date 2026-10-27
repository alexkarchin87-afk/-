package com.voiceagent.oneplus13.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

/**
 * Форм-фактор экрана, на котором сейчас показывается приложение.
 *
 * PHONE             — обычный телефон в портретной ориентации (Compact width).
 * TABLET_OR_DESKTOP — планшет, разложенный фолдабл, режим DeX/широкое окно:
 *                     постоянная боковая колонка чата рядом с основным экраном.
 * WINDOWS           — приложение запущено как отдельное плавающее окно на
 *                     Windows (Windows Subsystem for Android, эмулятор с
 *                     произвольным размером окна) или пользователь вручную
 *                     включил "виджет как на ПК". Вместо боковой колонки —
 *                     компактная всплывающая карточка в правом нижнем углу,
 *                     без затемнения остального экрана (как Siri/Cortana).
 */
enum class DeviceFormFactor {
    PHONE,
    TABLET_OR_DESKTOP,
    WINDOWS
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun WindowSizeClass.toFormFactor(
    override: FormFactorOverride = FormFactorOverride.AUTO,
    isWindowsHost: Boolean = false
): DeviceFormFactor {
    return when (override) {
        FormFactorOverride.PHONE -> DeviceFormFactor.PHONE
        FormFactorOverride.DESKTOP -> DeviceFormFactor.TABLET_OR_DESKTOP
        FormFactorOverride.WINDOWS -> DeviceFormFactor.WINDOWS
        FormFactorOverride.AUTO -> when {
            isWindowsHost -> DeviceFormFactor.WINDOWS
            widthSizeClass == WindowWidthSizeClass.Compact -> DeviceFormFactor.PHONE
            else -> DeviceFormFactor.TABLET_OR_DESKTOP
        }
    }
}

/**
 * Best-effort догадка о том, что хост — Windows. Ничего не сломает, если
 * ошибётся: это только сигнал по умолчанию, пользователь всегда может
 * переопределить его вручную через [FormFactorOverride.WINDOWS] в диалоге
 * "Агент и разрешения".
 *
 * Признаки:
 *  - Windows Subsystem for Android представляет устройство характерными
 *    строками вида "Subsystem for Android(TM)" в Build.MODEL/MANUFACTURER;
 *  - системная фича "android.hardware.type.pc" встречается на ПК-подобных
 *    образах (в т.ч. некоторых эмуляторах и WSA-сборках).
 */
fun isLikelyWindowsHost(context: Context): Boolean {
    val identifiers = listOf(
        Build.MODEL.orEmpty(),
        Build.MANUFACTURER.orEmpty(),
        Build.BRAND.orEmpty(),
        Build.PRODUCT.orEmpty()
    )
    val looksLikeWsa = identifiers.any {
        it.contains("Subsystem for Android", ignoreCase = true) || it.contains("Windows", ignoreCase = true)
    }
    val looksLikePc = runCatching {
        context.packageManager.hasSystemFeature("android.hardware.type.pc")
    }.getOrDefault(false)
    return looksLikeWsa || looksLikePc
}

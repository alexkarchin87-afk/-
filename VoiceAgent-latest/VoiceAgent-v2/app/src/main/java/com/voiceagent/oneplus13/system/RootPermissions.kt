package com.voiceagent.oneplus13.system

import android.content.ComponentName
import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Выдача САМОМУ ЭТОМУ приложению разрешений через root (KernelSU/Magisk) — для владельца
 * рутованного телефона, у которого Android 13+ блокирует «ограниченные настройки» для APK не из
 * магазина, а спец. доступы иначе включаются только руками в настройках.
 *
 * Как и в [RootActions], список команд ФИКСИРОВАННЫЙ: никаких строк от LLM и никакого действия,
 * которое модель могла бы вызвать сама. Запускается только на явную фразу пользователя с
 * подтверждением "да" (см. isGrantPermissionsRequest в AgentParsing.kt).
 */
object RootPermissions {

    data class Report(val done: List<String>, val failed: List<String>, val noRoot: Boolean)

    private val SAFE_LIST = Regex("^[A-Za-z0-9._/:]+$")

    fun grantAll(context: Context): Report {
        val probe = run("id")
        if (probe == null || probe.first != 0) return Report(emptyList(), emptyList(), noRoot = true)

        val pkg = context.packageName
        val accessibility = ComponentName(context, AccessibilityAgent::class.java).flattenToString()
        val listener = ComponentName(context, NotificationReader::class.java).flattenToString()

        val steps = buildList {
            add("микрофон" to "pm grant $pkg android.permission.RECORD_AUDIO")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("уведомления" to "pm grant $pkg android.permission.POST_NOTIFICATIONS")
                add("ограниченные настройки" to "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            }
            add("поверх других окон" to "appops set $pkg SYSTEM_ALERT_WINDOW allow")
            add("чтение уведомлений" to "cmd notification allow_listener $listener")
            add("работа в фоне" to "dumpsys deviceidle whitelist +$pkg")
        }

        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((label, command) in steps) {
            if (run(command)?.first == 0) done.add(label) else failed.add(label)
        }

        // Спец. возможности: ДОБАВЛЯЕМ наш сервис к уже включённым, а не затираем список
        // (иначе отвалятся другие сервисы доступности пользователя).
        val current = run("settings get secure enabled_accessibility_services")?.second?.trim().orEmpty()
        val services = if (current.isBlank() || current == "null") emptyList() else current.split(":")
        val label = "спец. возможности"
        when {
            accessibility in services -> done.add(label)
            current.isNotBlank() && current != "null" && !SAFE_LIST.matches(current) -> failed.add(label)
            else -> {
                val merged = (services + accessibility).joinToString(":")
                val ok = run("settings put secure enabled_accessibility_services $merged")?.first == 0 &&
                    run("settings put secure accessibility_enabled 1")?.first == 0
                if (ok) done.add(label) else failed.add(label)
            }
        }
        return Report(done, failed, noRoot = false)
    }

    /** Код выхода и вывод; null — если su не найден (телефон не рутован / доступ не выдан). */
    private fun run(command: String): Pair<Int, String>? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }.also { it.start() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy()
            reader.join(500)
            -1 to out.toString()
        } else {
            reader.join(1000)
            process.exitValue() to out.toString()
        }
    } catch (e: java.io.IOException) {
        null
    }
}

package com.voiceagent.oneplus13.system

/**
 * Root-переключатели для конкретных, заранее перечисленных системных настроек
 * (Wi-Fi, Bluetooth, мобильный интернет) — через `su -c "svc ..."`.
 *
 * СОЗНАТЕЛЬНОЕ ОГРАНИЧЕНИЕ: здесь нет метода "выполнить произвольную строку
 * от LLM как root-команду". AgentOrchestrator может дёрнуть только конкретные
 * перечисленные ниже функции с фиксированными аргументами (enable/disable)
 * — LLM выбирает ОДНО ИЗ действия по имени action, а не пишет shell-команду
 * сам. Если открыть свободный `su -c "$llmGeneratedString"`, то любая
 * инъекция в то, что попадает в промпт (текст уведомления, содержимое
 * страницы, за которую попросили прочитать экран) в теории превращается в
 * произвольное выполнение кода с root-правами — а это уже не "удобный
 * ассистент", а дыра. Функции ниже — это весь список того, что вообще можно
 * запросить, и он останется таким же, даже если промпт напишет что-то другое.
 */
object RootActions {

    enum class Toggle(val serviceName: String) {
        WIFI("wifi"),
        BLUETOOTH("bluetooth"),
        MOBILE_DATA("data")
    }

    sealed class Result {
        object Success : Result()
        object NoRoot : Result()
        data class Error(val message: String) : Result()
    }

    /** true -> включить, false -> выключить. */
    fun setToggle(toggle: Toggle, enabled: Boolean): Result {
        val cmd = "svc ${toggle.serviceName} ${if (enabled) "enable" else "disable"}"
        return runAsRoot(cmd)
    }

    private fun runAsRoot(command: String): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val exit = process.waitFor()
            if (exit == 0) Result.Success else Result.Error("Команда завершилась с кодом $exit")
        } catch (e: java.io.IOException) {
            // Обычно означает, что "su" не найден вообще — телефон не рутован
            // или root-доступ этому приложению не выдан (KernelSU/Magisk спросит
            // разрешение при первом вызове — отказ тоже попадёт сюда).
            Result.NoRoot
        }
    }
}

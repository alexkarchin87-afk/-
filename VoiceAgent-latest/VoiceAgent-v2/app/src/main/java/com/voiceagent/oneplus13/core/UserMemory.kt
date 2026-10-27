package com.voiceagent.oneplus13.core

import android.content.Context
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.core.security.SettingsLock
import org.json.JSONArray
import org.json.JSONObject

/** Приоритет факта — по спецификации "Ваня": P1 навсегда (снять может только явное
 *  "забудь"), P2 долгосрочно (может быть заменён новым фактом на ту же тему), P3
 *  временно (само сжимается, когда фактов становится слишком много).
 *  rank выше = более постоянный факт — используем при слиянии дублей. */
enum class MemoryTier(val rank: Int) { P3(1), P2(2), P1(3) }

data class MemoryFact(val text: String, val tier: MemoryTier, val timestampMillis: Long)

/**
 * Лёгкое (не векторное, не LLM-суммаризация) кэширование фактов о пользователе
 * между разговорами — список коротких строк с приоритетом, который целиком
 * подставляется в системный промпт каждый раз (см. AgentOrchestrator.buildSystemPrompt).
 *
 * Приоритеты обеспечиваются кодом, а не просьбой к модели в промпте: LLM решает,
 * *какой* факт запомнить и с каким приоритетом, но правило "P1 нельзя стереть
 * автосжатием" гарантированно соблюдается здесь — ошибиться в подсчёте "не
 * превысил ли я лимит в 200 слов" в коде намного маловероятнее, чем понадеяться,
 * что модель всегда аккуратно перепишет markdown-файл по правилам.
 *
 * Хранится локально в SharedPreferences на самом телефоне — никуда не
 * отправляется, кроме как в промпт локально выбранной LLM.
 */
class UserMemory(context: Context) {

    private val prefs = SecretsStore(context)

    /** Совместимость со старым API (BackupManager и старые вызовы) — по умолчанию P2. */
    fun remember(fact: String) = remember(fact, MemoryTier.P2)

    fun remember(fact: String, tier: MemoryTier) {
        synchronized(SettingsLock.monitor) { rememberLocked(fact, tier) }
    }

    private fun rememberLocked(fact: String, tier: MemoryTier) {
        save(mergeImportedFacts(allDetailed(), listOf(fact to tier.name)))
    }

    /** Убрать факт(ы), текст которых содержит [query] — например "забудь про возраст".
     *  Единственный способ убрать P1-факт, как и требует спецификация. */
    fun forget(query: String): Int = synchronized(SettingsLock.monitor) { forgetLocked(query) }

    private fun forgetLocked(query: String): Int {
        val q = query.trim().lowercase()
        if (q.isBlank()) return 0
        val current = allDetailed().toMutableList()
        val before = current.size
        current.removeAll { it.text.lowercase().contains(q) }
        save(current)
        return before - current.size
    }

    fun clear() = save(emptyList())
    internal fun restore(facts: List<MemoryFact>) = save(facts)

    fun allFacts(): List<String> = allDetailed().map { it.text }

    fun allDetailed(): List<MemoryFact> = synchronized(SettingsLock.monitor) { allDetailedLocked() }

    private fun allDetailedLocked(): List<MemoryFact> {
        val raw = prefs.get(SecretsStore.KEY_MEMORY_FACTS)
        if (raw != null) {
            return run {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MemoryFact(
                        text = o.getString("text"),
                        tier = runCatching { MemoryTier.valueOf(o.optString("tier", "P2")) }.getOrDefault(MemoryTier.P2),
                        timestampMillis = o.optLong("ts", 0L)
                    )
                }
            }
        }
        // Миграция со старой (до приоритетов) плоской версии хранилища, если она есть.
        val legacy = prefs.get(SecretsStore.KEY_MEMORY_LEGACY) ?: return emptyList()
        val migrated = run {
            val arr = JSONArray(legacy)
            (0 until arr.length()).map { MemoryFact(arr.getString(it), MemoryTier.P2, System.currentTimeMillis()) }
        }
        if (migrated.isNotEmpty()) save(migrated)
        return migrated
    }

    /** Готовый кусок для системного промпта — пусто, если ничего ещё не запомнено.
     *  Группируем по приоритету, чтобы модель понимала, что можно менять/забывать,
     *  а что — нет (P1 меняется только по прямой просьбе пользователя). */
    fun factsBlock(): String {
        val detailed = allDetailed()
        if (detailed.isEmpty()) return ""
        val p1 = detailed.filter { it.tier == MemoryTier.P1 }
        val p2 = detailed.filter { it.tier == MemoryTier.P2 }
        val p3 = detailed.filter { it.tier == MemoryTier.P3 }

        val sb = StringBuilder("Известные факты о пользователе (можно использовать в ответах):\n")
        if (p1.isNotEmpty()) sb.append("Навсегда: ").append(p1.joinToString("; ") { it.text }).append("\n")
        if (p2.isNotEmpty()) sb.append("Долгосрочно: ").append(p2.joinToString("; ") { it.text }).append("\n")
        if (p3.isNotEmpty()) sb.append("Сейчас актуально: ").append(p3.joinToString("; ") { it.text })
        return sb.toString().trim()
    }

    private fun save(facts: List<MemoryFact>) {
        prefs.put(SecretsStore.KEY_MEMORY_FACTS, encodeMemoryFacts(facts))
    }
}

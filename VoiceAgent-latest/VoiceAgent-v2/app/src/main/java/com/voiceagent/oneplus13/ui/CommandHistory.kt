package com.voiceagent.oneplus13.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val text: String, val timestamp: Long)

/**
 * Локальная история запросов (то, что сказали голосом или написали) для блока
 * «Недавние» на главном экране. Только на телефоне, в обычных SharedPreferences —
 * секретов тут нет. Хранит последние [MAX_ITEMS] уникальных запросов.
 */
class CommandHistory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE)

    fun all(): List<HistoryItem> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HistoryItem(o.getString("t"), o.optLong("ts", 0L))
        }
    }.getOrDefault(emptyList())

    /** Добавляет запрос наверх списка (дубликаты схлопываются) и возвращает свежий список. */
    fun add(text: String): List<HistoryItem> {
        val clean = text.trim()
        if (clean.isEmpty()) return all()
        val updated = (listOf(HistoryItem(clean, System.currentTimeMillis())) +
            all().filterNot { it.text.equals(clean, ignoreCase = true) }).take(MAX_ITEMS)
        save(updated)
        return updated
    }

    fun clear(): List<HistoryItem> {
        prefs.edit().remove(KEY).apply()
        return emptyList()
    }

    private fun save(items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.text).put("ts", it.timestamp)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX_ITEMS = 30
    }
}

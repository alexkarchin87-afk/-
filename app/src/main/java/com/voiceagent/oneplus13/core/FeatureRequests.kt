package com.voiceagent.oneplus13.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Очередь просьб "пришей эту утилиту к Ване как новую команду". VoiceAgent —
 * скомпилированное Android-приложение и не может сам переписать/пересобрать
 * свой собственный APK во время работы — так что это НЕ мгновенное действие,
 * а список, который сохраняется на телефоне и ждёт, пока разработчик (сам
 * пользователь или сессия Claude Code) не сядет за реальное обновление кода
 * и не превратит записанный сюда проект в настоящее действие оркестратора.
 *
 * Ровно поэтому правило "запрет на авто-вшивание" не нарушается: агент никогда
 * не пишет и не подключает сторонний код в себя сам — он только запоминает,
 * что об этом попросили.
 */
class FeatureRequests(context: Context) {

    data class Request(val projectName: String, val description: String, val timestampMs: Long)

    private val prefs = context.applicationContext
        .getSharedPreferences("voice_agent_feature_requests", Context.MODE_PRIVATE)

    fun add(projectName: String, description: String) {
        val current = all().toMutableList()
        current.add(Request(projectName, description, System.currentTimeMillis()))
        save(current)
    }

    fun all(): List<Request> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Request(obj.getString("project"), obj.getString("description"), obj.getLong("ts"))
            }
        }.getOrDefault(emptyList())
    }

    fun remove(projectName: String) {
        save(all().filterNot { it.projectName == projectName })
    }

    private fun save(requests: List<Request>) {
        val arr = JSONArray()
        requests.forEach { r ->
            arr.put(JSONObject().put("project", r.projectName).put("description", r.description).put("ts", r.timestampMs))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "requests"
    }
}

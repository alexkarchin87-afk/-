package com.voiceagent.oneplus13.agent

import org.json.JSONObject

// Чистые функции разбора ответов/команд — вынесены из AgentOrchestrator, чтобы их можно было
// тестировать без Android и чтобы сам оркестратор оставался только диспетчером.

/**
 * Ответ "да/нет" на подтверждающий вопрос. Сравниваем по СЛОВАМ, а не по подстрокам, и
 * отрицание приоритетнее согласия: раньше "нет, не используй" давало true (находилось
 * "используй"), а "никогда" — тоже true (подстрока "да"). Для подтверждений, которые что-то
 * применяют (токен бота и т.п.), безопаснее ошибиться в сторону "нет".
 */
internal fun classifyYesNo(text: String): Boolean? {
    val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    val no = setOf("нет", "не", "отмена", "отмени", "стоп", "no")
    val yes = setOf("да", "ага", "угу", "давай", "используй", "конечно", "yes")
    return when {
        words.any { it in no } -> false
        words.isNotEmpty() && words.all { it in yes || it == "пожалуйста" } -> true
        else -> null
    }
}

/** "Спроси Клода, ..." / "спроси у Клода ..." — возвращает саму суть вопроса
 *  без этого префикса, либо null, если фраза не про это. */
internal fun extractAskClaudeQuery(text: String): String? {
    val trigger = Regex("(?iu)^спроси\\s+(у\\s+)?клод[аеу]\\s*[,:]?\\s*")
    val match = trigger.find(text) ?: return null
    return text.substring(match.range.last + 1).trim().ifBlank { null }
}

internal fun tryParseAction(reply: String): Pair<String, Map<String, String>>? {
    val candidate = reply.trim()
    if (!candidate.startsWith("{") || !candidate.endsWith("}")) return null
    return try {
        val tokener = org.json.JSONTokener(candidate)
        val json = tokener.nextValue() as? JSONObject ?: return null
        if (tokener.nextClean() != '\u0000') return null
        if (json.keys().asSequence().toSet() != setOf("action", "params")) return null
        val action = json.get("action") as? String ?: return null
        val paramsJson = json.optJSONObject("params") ?: return null
        val params = paramsJson.keys().asSequence().associateWith {
            paramsJson.get(it) as? String ?: return null
        }
        if (!ActionPolicy.valid(action, params)) return null
        action to params
    } catch (_: Exception) {
        null
    }
}

/** "Выдай себе все разрешения" / "настрой права" — явная просьба владельца (не действие из LLM). */
internal fun isGrantPermissionsRequest(text: String): Boolean =
    Regex("(?iu)(выдай|дай|включи|настрой)\\s+(себе\\s+)?(все\\s+)?(разрешени[яе]|права|доступы?)").containsMatchIn(text)

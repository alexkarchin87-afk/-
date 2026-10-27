package com.voiceagent.oneplus13.devassist

/**
 * Имя проекта становится именем папки на диске (см. ProjectStorage) — здесь
 * оно приводится к безопасному виду один раз, сразу при разборе ответа LLM
 * (CodeGenAgent.parse), чтобы дальше по всему коду (AgentOrchestrator,
 * ProjectStorage, "продолжить проект") везде было одно и то же имя, без
 * риска, что где-то остался "сырой" вариант с "/" или "..".
 */
fun sanitizeProjectName(name: String): String {
    val cleaned = name.trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_").trim('_', '.')
    return cleaned.ifBlank { "project_${System.currentTimeMillis()}" }
}

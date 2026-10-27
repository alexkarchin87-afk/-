package com.voiceagent.oneplus13.agent

/** Only proposals from this schema may reach the confirmation UI. Internal actions are excluded. */
internal object ActionPolicy {
    private val schema = mapOf(
        "open_app" to setOf("package"), "open_url" to setOf("url"),
        "set_volume" to setOf("percent"), "tap_text" to setOf("label"),
        "swipe_up" to emptySet(), "lecture_start" to setOf("title"),
        "lecture_stop" to emptySet(), "lecture_summary" to emptySet(), "lecture_retry_summary" to emptySet(),
        "generate_code" to setOf("spec", "project_name", "platform"),
        "continue_code" to setOf("instruction", "project_name"),
        "describe_screen" to setOf("question"), "identify_on_screen" to setOf("question"),
        "now_playing" to emptySet(), "like_current_track" to emptySet(),
        "chats_digest" to emptySet(), "run_diagnostics" to emptySet(),
        "reply_notification" to setOf("name", "text"),
        "toggle_wifi" to setOf("enabled"), "toggle_bluetooth" to setOf("enabled"),
        "toggle_mobile_data" to setOf("enabled"), "install_app" to setOf("query"),
        "notes_build" to setOf("topic", "sources"), "notes_extend" to setOf("addition", "topic"),
        "run_workflow" to setOf("name"), "create_workflow" to setOf("description"),
        "remember_fact" to setOf("fact", "tier"), "forget_fact" to setOf("fact")
    )
    private val required = mapOf(
        "open_app" to "package", "open_url" to "url", "set_volume" to "percent",
        "tap_text" to "label", "generate_code" to "spec", "continue_code" to "instruction",
        "reply_notification" to "name", "install_app" to "query", "notes_build" to "sources",
        "notes_extend" to "addition", "run_workflow" to "name", "create_workflow" to "description",
        "remember_fact" to "fact", "forget_fact" to "fact"
    )
    fun valid(action: String, params: Map<String, String>): Boolean {
        val allowed = schema[action] ?: return false
        if (!allowed.containsAll(params.keys) || params.values.any { it.length > 32000 }) return false
        if (required[action]?.let { params[it].isNullOrBlank() } == true) return false
        if (action == "reply_notification" && params["text"].isNullOrBlank()) return false
        if (action == "set_volume" && params["percent"]?.toIntOrNull() !in 0..100) return false
        if (action.startsWith("toggle_") && params["enabled"] !in setOf("true", "false")) return false
        if (params["platform"]?.let { it !in setOf("android", "windows") } == true) return false
        if (params["tier"]?.let { it !in setOf("P1", "P2", "P3") } == true) return false
        if (action == "open_url") {
            val uri = runCatching { java.net.URI(params.getValue("url")) }.getOrNull() ?: return false
            if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        }
        return true
    }
}

package com.voiceagent.oneplus13.agent

/** Narrow fast path for an existing action, independent of cloud connectivity. Never scans model text. */
internal fun localAppCommand(text: String): Pair<String, Map<String, String>>? {
    if (!Regex("(?iu)^открой\\s+(?:приложение\\s+)?(?:телеграм|телеграмм|telegram)[.!?]?$")
            .matches(text.trim())) return null
    return "open_app" to mapOf("package" to "org.telegram.messenger")
}

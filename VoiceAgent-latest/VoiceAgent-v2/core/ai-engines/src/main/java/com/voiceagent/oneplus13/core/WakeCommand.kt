package com.voiceagent.oneplus13.core

/** null: not addressed to the assistant; empty: wake word alone. Whole final phrase is retained. */
fun commandAfterWake(text: String): String? {
    val prefix = Regex("(?iu)^(?:эй\\s+)?ваня\\b[\\s,.:!?-]*")
    val trimmed = text.trim()
    val match = prefix.find(trimmed) ?: return null
    return trimmed.substring(match.range.last + 1).trim()
}

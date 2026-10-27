package com.voiceagent.oneplus13.ui

/** Keep the draft/history unless the current route can actually accept a command. */
internal fun submitTextIfReady(text: String, ready: () -> Boolean, send: (String) -> Unit): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !ready()) return false
    send(trimmed)
    return true
}

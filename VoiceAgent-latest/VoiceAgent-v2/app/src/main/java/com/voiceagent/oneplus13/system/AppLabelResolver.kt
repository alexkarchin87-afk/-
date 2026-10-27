package com.voiceagent.oneplus13.system

internal data class LaunchableApp(val packageName: String, val label: String)

/** Multiple launcher activities for one package are fine; ambiguous packages are never guessed. */
internal fun resolveAppLabel(query: String, apps: List<LaunchableApp>): String? {
    val key = query.trim()
    if (key.isBlank()) return null
    val exact = apps.filter { it.label.equals(key, ignoreCase = true) }
    val matches = exact.ifEmpty { apps.filter { it.label.contains(key, ignoreCase = true) } }
    return matches.map { it.packageName }.distinct().singleOrNull()
}

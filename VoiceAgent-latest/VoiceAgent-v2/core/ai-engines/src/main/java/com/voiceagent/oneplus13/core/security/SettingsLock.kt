package com.voiceagent.oneplus13.core.security

/** One process, one authoritative encrypted preferences file. Multi-field operations hold this lock. */
object SettingsLock {
    val monitor = Any()
}

/** Snapshot and failed-commit restoration. The backend commits all supplied keys in ONE file edit. */
internal class SettingsCommitter(
    private val read: (String) -> String?,
    private val commit: (Map<String, String?>) -> Boolean
) {
    fun migrate(schemaKey: String, schemaVersion: String, legacyValues: () -> Map<String, String?>) {
        val current = read(schemaKey)
        if (current == schemaVersion) return
        check(current == null) { "Unsupported settings schema" }
        putAll(legacyValues() + (schemaKey to schemaVersion))
    }

    fun putAll(values: Map<String, String?>) {
        if (values.isEmpty()) return
        val before = values.keys.associateWith(read)
        try {
            check(commit(values)) { "Settings commit failed" }
        } catch (failure: Exception) {
            // SharedPreferences can update its memory cache even when disk commit returns false.
            try {
                check(commit(before)) { "Settings rollback failed" }
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
                throw IllegalStateException("Settings write and rollback failed; persisted snapshot requires verification", failure)
            }
            throw failure
        }
    }
}

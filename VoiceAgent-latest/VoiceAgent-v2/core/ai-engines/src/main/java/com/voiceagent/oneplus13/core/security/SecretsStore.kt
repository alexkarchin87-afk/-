package com.voiceagent.oneplus13.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value store for secrets added AT RUNTIME (API keys typed into
 * Settings, bot tokens captured from Telegram notifications) — as opposed to
 * BuildConfig fields, which only cover secrets baked in at compile time via
 * local.properties.
 *
 * Backed by Jetpack Security's EncryptedSharedPreferences (AES-256-GCM), so
 * values are encrypted at rest. A compromised running device can still access them.
 */
class SecretsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = synchronized(SettingsLock.monitor) { openEncryptedPrefs(appContext) }
    private val committer = SettingsCommitter(
        read = { key -> prefs.getString(key, null) },
        commit = { values ->
            val editor = prefs.edit()
            values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            editor.commit()
        }
    )

    init { synchronized(SettingsLock.monitor) { migrateLegacySettings(); refreshBackupMirrors() } }

    fun put(key: String, value: String) = putAll(mapOf(key to value))

    /** Secrets, memory and UI settings are now authoritative in the SAME encrypted file. */
    fun putAll(values: Map<String, String?>) = synchronized(SettingsLock.monitor) {
        committer.putAll(values)
        if (values.keys.any { it.startsWith("settings:") }) refreshBackupMirrors()
    }

    fun get(key: String): String? = synchronized(SettingsLock.monitor) { prefs.getString(key, null) }

    fun projectSecretKeys(): List<String> = synchronized(SettingsLock.monitor) {
        // Derive the index from the same transaction; a second index file cannot be atomic with it.
        prefs.all.keys.filter { it.startsWith("project_secret:") }
    }

    fun remove(key: String) = putAll(mapOf(key to null))
    fun getOrDefault(key: String, fallback: String): String = get(key)?.takeIf { it.isNotBlank() } ?: fallback

    private fun migrateLegacySettings() {
        committer.migrate(KEY_SCHEMA, "1") {
            val memory = appContext.getSharedPreferences("voice_agent_user_memory", Context.MODE_PRIVATE)
            val ui = appContext.getSharedPreferences("voice_agent_ui_prefs", Context.MODE_PRIVATE)
            // Marker and values become visible together. A crash never commits a marker alone.
            mapOf(
                KEY_MEMORY_FACTS to memory.getString("facts_v2", null),
                KEY_MEMORY_LEGACY to memory.getString("facts", null),
                KEY_FORM_FACTOR to ui.getString("form_factor_override", "AUTO"),
                KEY_OVERLAY_ENABLED to ui.getBoolean("overlay_bubble_enabled", false).toString(),
                KEY_OVERLAY_ALWAYS_VISIBLE to ui.getBoolean("overlay_always_visible", false).toString()
            )
        }
    }

    /** Non-secret legacy files remain backup mirrors, never runtime sources after migration.
     * Android Backup still carries memory/UI as before; encrypted secrets stay excluded.
     * A failed mirror refresh does not undo a successful authoritative transaction and is retried on open.
     */
    private fun refreshBackupMirrors() {
        try {
            val memory = appContext.getSharedPreferences("voice_agent_user_memory", Context.MODE_PRIVATE).edit()
            val raw = prefs.getString(KEY_MEMORY_FACTS, null)
            val legacy = prefs.getString(KEY_MEMORY_LEGACY, null)
            if (raw == null) memory.remove("facts_v2") else memory.putString("facts_v2", raw)
            if (legacy == null) memory.remove("facts") else memory.putString("facts", legacy)
            check(memory.commit())
            check(appContext.getSharedPreferences("voice_agent_ui_prefs", Context.MODE_PRIVATE).edit()
                .putString("form_factor_override", prefs.getString(KEY_FORM_FACTOR, "AUTO"))
                .putBoolean("overlay_bubble_enabled", prefs.getString(KEY_OVERLAY_ENABLED, "false") == "true")
                .putBoolean("overlay_always_visible", prefs.getString(KEY_OVERLAY_ALWAYS_VISIBLE, "false") == "true")
                .commit())
        } catch (_: Exception) {
            Log.w(TAG, "Settings saved; non-secret Android Backup mirror refresh failed")
        }
    }

    companion object {
        private const val TAG = "SecretsStore"
        private const val FILE_NAME = "voice_agent_secrets"

        /** Never delete unreadable user settings automatically: a temporary Keystore error is not proof of loss. */
        private fun openEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, FILE_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private const val KEY_SCHEMA = "settings:schema"
        const val KEY_MEMORY_FACTS = "settings:memory_v2"
        const val KEY_MEMORY_LEGACY = "settings:memory_legacy"
        const val KEY_FORM_FACTOR = "settings:form_factor"
        const val KEY_OVERLAY_ENABLED = "settings:overlay_enabled"
        const val KEY_OVERLAY_ALWAYS_VISIBLE = "settings:overlay_always_visible"

        const val KEY_DEEPSEEK_MODEL = "deepseek_model"
        const val KEY_DEEPSEEK_REASONING_MODEL = "deepseek_reasoning_model"
        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        const val KEY_CLAUDE_API_KEY = "claude_api_key"
        const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        const val KEY_MAX_SERVICE_TOKEN = "max_service_token"

        /** Prefix for tokens captured automatically from BotFather notifications,
         *  one per generated project: pending_botfather_token:<project_name> */
        fun pendingBotFatherTokenKey(projectName: String) = "pending_botfather_token:$projectName"
    }
}

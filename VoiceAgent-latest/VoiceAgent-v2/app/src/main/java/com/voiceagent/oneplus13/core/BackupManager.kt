package com.voiceagent.oneplus13.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.core.security.SettingsLock
import com.voiceagent.oneplus13.ui.UiPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Portable encrypted settings export; legacy JSON is accepted only on import. */
class BackupManager(private val context: Context) {

    private val secrets = SecretsStore(context)

    fun exportBackup(password: CharArray): File {
        cleanup(context)
        val root = synchronized(SettingsLock.monitor) {
            val root = JSONObject()
            root.put("format_version", 2)
            root.put("exported_at", System.currentTimeMillis())

            val secretsJson = JSONObject()
            listOf(
                SecretsStore.KEY_DEEPSEEK_API_KEY,
                SecretsStore.KEY_CLAUDE_API_KEY,
                SecretsStore.KEY_TELEGRAM_BOT_TOKEN,
                SecretsStore.KEY_DEEPSEEK_MODEL,
                SecretsStore.KEY_DEEPSEEK_REASONING_MODEL,
                SecretsStore.KEY_MAX_SERVICE_TOKEN
            ).forEach { key -> secrets.get(key)?.let { secretsJson.put(key, it) } }
            root.put("secrets", secretsJson)

            val projectSecretsJson = JSONObject()
            secrets.projectSecretKeys().forEach { key -> secrets.get(key)?.let { projectSecretsJson.put(key, it) } }
            root.put("project_secrets", projectSecretsJson)

            // v2: факты с приоритетом (было — просто массив строк в "memory_facts";
            // тот старый формат по-прежнему читается при импорте, см. ниже).
            val factsJson = JSONArray()
            UserMemory(context).allDetailed().forEach { fact ->
                factsJson.put(
                    JSONObject()
                        .put("text", fact.text)
                        .put("tier", fact.tier.name)
                        .put("ts", fact.timestampMillis)
                )
            }
            root.put("memory_facts_v2", factsJson)

            val prefs = UiPreferences(context)
            val uiJson = JSONObject()
            uiJson.put("form_factor_override", prefs.formFactorOverride.name)
            uiJson.put("overlay_enabled", prefs.overlayEnabled)
            uiJson.put("overlay_always_visible", prefs.overlayAlwaysVisible)
            root.put("ui_preferences", uiJson)
            root
        }

        val dir = File(context.cacheDir, "backup_exports").apply { mkdirs() }
        val file = File.createTempFile("voiceagent_", ".json", dir)
        try { file.writeText(BackupCodec.encrypt(root.toString(), password)) }
        catch (e: Exception) { file.delete(); throw e }
        finally { password.fill('\u0000') }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ file.delete() }, EXPORT_TTL_MS)
        return file
    }

    /** Validate first; publish secrets, memory and UI together through one encrypted file commit. */
    fun importBackup(file: File, password: CharArray = charArrayOf()): Boolean {
        val data = try {
            require(file.length() in 1..MAX_IMPORT_BYTES)
            val root = JSONObject(file.readText())
            val plain = if (root.optInt("format_version") == 3)
                JSONObject(BackupCodec.decrypt(root, password)) else root
            validateBackup(plain)
        } catch (_: Exception) { return false }
        finally { password.fill('\u0000') }
        return synchronized(SettingsLock.monitor) {
            val updates = prepareSettingsImport(data, UserMemory(context).allDetailed())
            // Exactly one encrypted SharedPreferences Editor.commit for ALL authoritative values.
            secrets.putAll(updates)
            true
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 2L * 1024 * 1024
        private const val EXPORT_TTL_MS = 60 * 60 * 1000L
        fun cleanup(context: Context) {
            // Migration: remove the previous plaintext export, even if sharing had failed.
            File(context.filesDir, "voiceagent_backup.json").delete()
            File(context.cacheDir, "voiceagent_import.json").delete()
            File(context.cacheDir, "backup_exports").listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() >= EXPORT_TTL_MS) it.delete()
            }
        }
    }

    fun shareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

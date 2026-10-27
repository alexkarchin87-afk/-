package com.voiceagent.oneplus13.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable password-encrypted backups. No password or derived key is persisted. */
internal object BackupCodec {
    private const val ITERATIONS = 210_000
    private val aad = "VoiceAgent-backup-v3".toByteArray(Charsets.UTF_8)
    fun encrypt(plain: String, password: CharArray): String {
        require(password.size >= 12) { "Пароль должен содержать не менее 12 символов" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return JSONObject().put("format_version", 3).put("kdf", "PBKDF2-HMAC-SHA256")
            .put("iterations", ITERATIONS).put("salt", encode(salt)).put("iv", encode(iv))
            .put("ciphertext", encode(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))).toString()
    }
    fun decrypt(envelope: JSONObject, password: CharArray): String {
        require(envelope.getInt("format_version") == 3 && envelope.getString("kdf") == "PBKDF2-HMAC-SHA256")
        require(envelope.getInt("iterations") == ITERATIONS) // do not accept attacker-controlled KDF cost
        val salt = decode(envelope.getString("salt")); val iv = decode(envelope.getString("iv"))
        require(salt.size == 16 && iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(decode(envelope.getString("ciphertext"))).toString(Charsets.UTF_8)
    }
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try { SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
        } finally { spec.clearPassword() }
    }
    private fun encode(data: ByteArray) = Base64.getEncoder().encodeToString(data)
    private fun decode(data: String) = Base64.getDecoder().decode(data)
}

internal data class ValidatedBackup(
    val secrets: Map<String, String>, val facts: List<Pair<String, String>>,
    val formFactor: String?, val overlayEnabled: Boolean?, val overlayAlwaysVisible: Boolean?
)

/** Validate the WHOLE document before touching any store. Legacy v1/v2 imports remain supported. */
internal fun validateBackup(root: JSONObject): ValidatedBackup {
    require(root.get("format_version") in listOf(1, 2)) { "Unsupported backup version" }
    val secrets = mutableMapOf<String, String>()
    val known = setOf("deepseek_api_key", "claude_api_key", "telegram_bot_token", "max_service_token",
        "deepseek_model", "deepseek_reasoning_model")
    for (field in listOf("secrets", "project_secrets")) {
        if (!root.has(field)) continue
        val obj = root.getJSONObject(field)
        for (key in obj.keys()) {
            require(if (field == "secrets") key in known else key.matches(Regex("project_secret:[A-Za-z0-9_-]+:[A-Za-z0-9_]+")))
            val value = obj.get(key)
            require(value is String && value.length <= 8192)
            secrets[key] = value
        }
    }
    val facts = mutableListOf<Pair<String, String>>()
    if (root.has("memory_facts_v2")) {
        val arr = root.getJSONArray("memory_facts_v2"); require(arr.length() <= 1000)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val text = obj.get("text"); val tier = obj.get("tier")
            require(text is String && text.isNotBlank() && text.length <= 200)
            require(tier is String && tier in setOf("P1", "P2", "P3"))
            facts += text to tier
        }
    } else if (root.has("memory_facts")) {
        val arr = root.getJSONArray("memory_facts"); require(arr.length() <= 1000)
        for (i in 0 until arr.length()) {
            val text = arr.get(i); require(text is String && text.isNotBlank() && text.length <= 200)
            facts += text to "P2"
        }
    }
    val ui = if (root.has("ui_preferences")) root.getJSONObject("ui_preferences") else JSONObject()
    val form = if (ui.has("form_factor_override")) ui.get("form_factor_override") else null
    require(form == null || form is String && form in setOf("AUTO", "PHONE", "DESKTOP", "WINDOWS"))
    fun flag(key: String): Boolean? {
        if (!ui.has(key)) return null
        val value = ui.get(key); require(value is Boolean); return value
    }
    return ValidatedBackup(secrets.toMap(), facts.toList(), form as? String,
        flag("overlay_enabled"), flag("overlay_always_visible"))
}

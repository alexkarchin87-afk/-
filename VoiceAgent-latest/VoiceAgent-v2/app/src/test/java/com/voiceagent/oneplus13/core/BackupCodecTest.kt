package com.voiceagent.oneplus13.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val password get() = "test-only-passphrase".toCharArray()
    private val plain = """{"format_version":2,"secrets":{"deepseek_api_key":"synthetic-test-value"},"memory_facts_v2":[{"text":"test","tier":"P2"}],"ui_preferences":{"overlay_enabled":false}}"""
    @Test fun authenticatedEncryptionRoundTrip() {
        val encrypted = BackupCodec.encrypt(plain, password)
        assertFalse(encrypted.contains("synthetic-test-value"))
        assertEquals(plain, BackupCodec.decrypt(JSONObject(encrypted), password))
    }
    @Test fun randomSaltAndNonce() { assertNotEquals(BackupCodec.encrypt(plain, password), BackupCodec.encrypt(plain, password)) }
    @Test fun wrongPasswordFails() {
        val encrypted = JSONObject(BackupCodec.encrypt(plain, password))
        assertThrows(Exception::class.java) { BackupCodec.decrypt(encrypted, "wrong-passphrase".toCharArray()) }
    }
    @Test fun tamperingFailsAuthentication() {
        val encrypted = JSONObject(BackupCodec.encrypt(plain, password))
        val cipher = java.util.Base64.getDecoder().decode(encrypted.getString("ciphertext"))
        cipher[0] = (cipher[0].toInt() xor 1).toByte()
        encrypted.put("ciphertext", java.util.Base64.getEncoder().encodeToString(cipher))
        assertThrows(Exception::class.java) { BackupCodec.decrypt(encrypted, password) }
    }
    @Test fun rejectsAttackerKdfCost() {
        val encrypted = JSONObject(BackupCodec.encrypt(plain, password)).put("iterations", Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decrypt(encrypted, password) }
    }
    @Test fun rejectsWeakExportPassword() { assertThrows(IllegalArgumentException::class.java) { BackupCodec.encrypt(plain, charArrayOf('x')) } }
    @Test fun validatesCompleteBackupBeforeMutation() {
        val valid = validateBackup(JSONObject(plain)); assertEquals(1, valid.secrets.size)
        val broken = JSONObject(plain).put("memory_facts_v2", org.json.JSONArray().put(JSONObject().put("text", 42).put("tier", "P2")))
        assertThrows(IllegalArgumentException::class.java) { validateBackup(broken) }
    }
    @Test fun rejectsUnknownVersionsKeysAndBadTypes() {
        for (text in listOf("{}", """{"format_version":99}""", """{"format_version":2,"secrets":{"arbitrary":"x"}}""",
            """{"format_version":2,"ui_preferences":{"overlay_enabled":"true"}}""")) {
            assertThrows(Exception::class.java) { validateBackup(JSONObject(text)) }
        }
    }
    @Test fun acceptsLegacyFacts() {
        assertEquals(listOf("old fact" to "P2"), validateBackup(JSONObject("""{"format_version":1,"memory_facts":["old fact"]}""")).facts)
    }
}

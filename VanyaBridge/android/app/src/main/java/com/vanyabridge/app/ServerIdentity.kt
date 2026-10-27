package com.vanyabridge.app

import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate

object ServerIdentity {
    fun normalizeFingerprint(value: String): String {
        val normalized = value.replace(":", "").replace(" ", "").uppercase()
        require(normalized.matches(Regex("[0-9A-F]{64}"))) { "Нужен полный SHA-256 с экрана ПК (64 символа)" }
        return normalized
    }

    fun address(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        val octets = (uri.host ?: "").split('.').map { it.toIntOrNull() ?: -1 }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "Введите частный IPv4 компьютера" }
        require(octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31) || octets[0] == 127) { "Разрешена только локальная сеть" }
        require(uri.port == -1 || uri.port in 1..65535)
        return value.trim().trimEnd('/')
    }

    fun verify(cert: X509Certificate, fingerprint: String) {
        cert.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val expected = normalizeFingerprint(fingerprint).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(actual, expected)) { "Сертификат ПК не совпадает" }
    }
}

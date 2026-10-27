package com.vanyabridge.app

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class ServerIdentityTest {
    @Test fun acceptsPrivateHttps() { assertEquals("https://192.168.1.8:8765", ServerIdentity.address("https://192.168.1.8:8765/")) }
    @Test fun rejectsInsecureOrPublic() {
        listOf("http://192.168.1.2", "https://8.8.8.8", "https://user@192.168.1.2", "https://192.168.1.2/a", "https://192.168.1.2?token=x", "https://localhost", "https://0.0.0.0").forEach {
            assertTrue(it, runCatching { ServerIdentity.address(it) }.isFailure)
        }
    }
    @Test fun validatesFullFingerprint() {
        assertEquals("AB".repeat(32), ServerIdentity.normalizeFingerprint("ab:".repeat(31) + "ab"))
        assertTrue(runCatching { ServerIdentity.normalizeFingerprint("AB".repeat(31)) }.isFailure)
        assertTrue(runCatching { ServerIdentity.normalizeFingerprint("GG".repeat(32)) }.isFailure)
    }
    @Test fun verifiesCertificateAndRejectsWrongPin() {
        val certificate = javaClass.getResourceAsStream("/certificate.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }
        ServerIdentity.verify(certificate, pin)
        assertTrue(runCatching { ServerIdentity.verify(certificate, "00".repeat(32)) }.isFailure)
    }
}

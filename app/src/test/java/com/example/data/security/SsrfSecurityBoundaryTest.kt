package com.example.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SsrfSecurityBoundaryTest {

    @Test
    fun testRejectsIpv4LoopbackAddresses() {
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("127.0.0.254")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("127.255.255.255")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://127.0.0.1:8080/admin"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://localhost:8080/api"))
    }

    @Test
    fun testRejectsIpv6LoopbackAddresses() {
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("::1")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://[::1]:8080/"))
    }

    @Test
    fun testRejectsRfc1918PrivateSubnets() {
        // 10.0.0.0/8
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("10.0.0.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("10.254.100.5")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://10.0.0.1/status"))

        // 172.16.0.0/12
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("172.16.0.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("172.31.255.255")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://172.16.0.1:9000/internal"))

        // 192.168.0.0/16
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("192.168.254.254")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://192.168.1.1/router"))
    }

    @Test
    fun testRejectsCloudMetadataEndpoints() {
        // AWS/GCP 169.254.169.254
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("169.254.169.254")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://metadata.google.internal/computeMetadata/v1/"))

        // Alibaba Cloud Metadata (100.100.100.200)
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("100.100.100.200")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://100.100.100.200/latest/meta-data/"))
    }

    @Test
    fun testRejectsCarrierGradeNatAndReservedRanges() {
        // Carrier-Grade NAT (100.64.0.0/10)
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("100.127.255.255")))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://100.64.0.1:8080/"))

        // Documentation/Test nets
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("192.0.2.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("198.51.100.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("203.0.113.1")))

        // Multicast
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("224.0.0.1")))
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("239.255.255.250")))

        // Class E Reserved
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("240.0.0.1")))
    }

    @Test
    fun testRejectsIpv4MappedIpv6PrivateAddresses() {
        // ::ffff:127.0.0.1
        val mappedLoopback = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            127, 0, 0, 1
        )
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByAddress(mappedLoopback)))

        // ::ffff:10.0.0.1
        val mappedPrivate = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            10, 0, 0, 1
        )
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByAddress(mappedPrivate)))

        // ::ffff:169.254.169.254
        val mappedMetadata = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()
        )
        assertFalse(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByAddress(mappedMetadata)))
    }

    @Test
    fun testRejectsDisallowedSchemes() {
        assertFalse(SsrfSecurityBoundary.isSafeUrl("file:///etc/passwd"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("gopher://127.0.0.1:70/"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("ftp://192.168.1.1/"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("data:text/html,Hello"))
    }

    @Test
    fun testRejectsEmbeddedCredentialsInUrls() {
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://user:password@example.com/"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("https://admin:secret@8.8.8.8/api"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://guest:@example.com/resource"))
    }

    @Test
    fun testRejectsMalformedAndEmptyUrls() {
        assertFalse(SsrfSecurityBoundary.isSafeUrl(null))
        assertFalse(SsrfSecurityBoundary.isSafeUrl(""))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("   "))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("not a valid url"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("http://"))
        assertFalse(SsrfSecurityBoundary.isSafeUrl("https://"))
    }

    @Test
    fun testAllowsPublicInternetAddresses() {
        assertTrue(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(SsrfSecurityBoundary.isSafePublicAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun testSafeDnsBlocksMetadataHostnames() {
        try {
            SsrfSecurityBoundary.safeDns.lookup("metadata.google.internal")
            org.junit.Assert.fail("Expected IOException for metadata hostname resolution")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("SSRF Security Violation") == true)
        }

        try {
            SsrfSecurityBoundary.safeDns.lookup("169.254.169.254")
            org.junit.Assert.fail("Expected IOException for metadata IP resolution")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("SSRF Security Violation") == true)
        }
    }

    @Test
    fun testOpenSafeConnectionThrowsOnUnsafeDestination() {
        try {
            SsrfSecurityBoundary.openSafeConnection("http://127.0.0.1:8080/secret")
            org.junit.Assert.fail("Expected SecurityException when opening unsafe connection")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("SSRF Security Violation") == true)
        }

        try {
            SsrfSecurityBoundary.openSafeConnection("http://169.254.169.254/latest/meta-data")
            org.junit.Assert.fail("Expected SecurityException when opening metadata connection")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("SSRF Security Violation") == true)
        }
    }

    @Test
    fun testExecuteSafeHttpGetThrowsOnUnsafeDestination() {
        try {
            SsrfSecurityBoundary.executeSafeHttpGet("http://192.168.1.1/admin")
            org.junit.Assert.fail("Expected SecurityException when executing unsafe HTTP GET")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("SSRF Security Violation") == true)
        }

        try {
            SsrfSecurityBoundary.executeSafeHttpGet("http://user:pass@example.com")
            org.junit.Assert.fail("Expected SecurityException when executing credentialed URL")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("SSRF Security Violation") == true)
        }
    }

    @Test
    fun testModelDownloaderSecureUrlValidation() {
        // Allowed domains via HTTPS
        assertTrue(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen.gguf"))
        assertTrue(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen.gguf"))
        assertTrue(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://github.com/ggerganov/llama.cpp/releases/download/b1234/model.gguf"))
        assertTrue(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://raw.githubusercontent.com/user/models/main/model.gguf"))

        // Disallowed domains
        assertFalse(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://untrusted-host.com/model.gguf"))
        assertFalse(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://evil.org/model.gguf"))

        // Disallowed non-HTTPS schemes
        assertFalse(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("http://huggingface.co/model.gguf"))
        assertFalse(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("ftp://github.com/model.gguf"))

        // Disallowed private / loopback / metadata destinations
        assertFalse(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://127.0.0.1/model.gguf"))
        assertFalse(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://169.254.169.254/model.gguf"))
        assertFalse(com.example.data.ai.runtime.WastiModelDownloader.isSecureDownloadUrl("https://10.0.0.1/model.gguf"))
    }
}

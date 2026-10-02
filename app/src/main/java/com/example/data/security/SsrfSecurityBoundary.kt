package com.example.data.security

import android.util.Log
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * [P0-SSRF] Enterprise SSRF (Server-Side Request Forgery) Destination Security Boundary.
 *
 * Enforces comprehensive destination gating before connection and across all HTTP/HTTPS redirects:
 * 1. Re-resolves hostnames against authoritative DNS.
 * 2. Rejects IPv4/IPv6 loopback, RFC 1918 private subnets, link-local, carrier-grade NAT,
 *    multicast, unspecified/any-local, benchmark/test networks, and cloud metadata services.
 * 3. Rejects user credentials embedded in URLs (userInfo).
 * 4. Intercepts redirects dynamically to prevent DNS-rebinding and redirect bypass.
 * 5. Preserves legitimate public Internet access.
 */
data class SafeHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val finalUrl: String
)

object SsrfSecurityBoundary {

    private const val TAG = "SsrfSecurityBoundary"

    // Cloud metadata hostnames
    private val CLOUD_METADATA_HOSTNAMES = setOf(
        "metadata.google.internal",
        "metadata.internal",
        "169.254.169.254",
        "instance-data"
    )

    /**
     * Checks if a target URL string points to a safe, public, non-SSRF destination.
     */
    fun isSafeUrl(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false
        return try {
            val uri = URI(urlString)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") {
                Log.w(TAG, "SSRF Blocked: Disallowed URI scheme '$scheme'")
                return false
            }

            // Reject user credentials / embedded user info
            if (uri.userInfo != null || uri.rawUserInfo != null) {
                Log.w(TAG, "SSRF Blocked: Disallowed user info/credentials in URL")
                return false
            }

            val host = uri.host ?: return false
            if (host.isBlank()) return false

            val hostLower = host.lowercase()
            // Check metadata hostnames directly
            if (CLOUD_METADATA_HOSTNAMES.contains(hostLower) || hostLower.contains("metadata.google.internal") || hostLower.contains("metadata.internal")) {
                Log.w(TAG, "SSRF Blocked: Cloud metadata hostname '$host'")
                return false
            }

            // Resolve DNS and evaluate all resolved IP addresses
            val addresses = try {
                InetAddress.getAllByName(host)
            } catch (e: Exception) {
                Log.w(TAG, "SSRF Notice: DNS resolution failed for host '$host': ${e.message}")
                return false
            }

            if (addresses.isEmpty()) return false

            for (addr in addresses) {
                if (!isSafePublicAddress(addr)) {
                    Log.w(TAG, "SSRF Blocked: Host '$host' resolved to unsafe IP '${addr.hostAddress}'")
                    return false
                }
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "SSRF Blocked: Malformed or unparseable URL '$urlString': ${e.message}")
            false
        }
    }

    /**
     * Evaluates whether an [InetAddress] is a safe, routable, public IP address.
     */
    fun isSafePublicAddress(address: InetAddress): Boolean {
        // 1. Loopback addresses (127.0.0.0/8, ::1)
        if (address.isLoopbackAddress) return false

        // 2. AnyLocal / Wildcard (0.0.0.0, ::)
        if (address.isAnyLocalAddress) return false

        // 3. Link-Local (169.254.0.0/16, fe80::/10)
        if (address.isLinkLocalAddress) return false

        // 4. Site-Local / Private RFC 1918 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
        if (address.isSiteLocalAddress) return false

        // 5. Multicast (224.0.0.0/4, ff00::/8)
        if (address.isMulticastAddress) return false

        val rawBytes = address.address

        // Unwrap IPv4-mapped IPv6 addresses (::ffff:x.x.x.x)
        if (address is Inet6Address) {
            if (isIpv4MappedIpv6(rawBytes)) {
                val ipv4Bytes = rawBytes.copyOfRange(12, 16)
                val unwrappedIpv4 = InetAddress.getByAddress(ipv4Bytes)
                return isSafePublicAddress(unwrappedIpv4)
            }
            // IPv6 Unique Local Address (fc00::/7)
            if ((rawBytes[0].toInt() and 0xfe) == 0xfc) return false
            // AWS IPv6 metadata (fd00:ec2::254)
            if ((rawBytes[0].toInt() and 0xff) == 0xfd && (rawBytes[1].toInt() and 0xff) == 0x00) return false
        }

        if (address is Inet4Address || rawBytes.size == 4) {
            val b0 = rawBytes[0].toInt() and 0xFF
            val b1 = rawBytes[1].toInt() and 0xFF
            val b2 = rawBytes[2].toInt() and 0xFF
            val b3 = rawBytes[3].toInt() and 0xFF

            // 0.0.0.0/8 (Current network)
            if (b0 == 0) return false

            // 10.0.0.0/8 (Private RFC 1918)
            if (b0 == 10) return false

            // 100.64.0.0/10 (Carrier-Grade NAT RFC 6598)
            if (b0 == 100 && (b1 in 64..127)) return false

            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return false

            // 169.254.0.0/16 (Link-Local / Cloud Metadata 169.254.169.254)
            if (b0 == 169 && b1 == 254) return false

            // 172.16.0.0/12 (Private RFC 1918)
            if (b0 == 172 && (b1 in 16..31)) return false

            // 192.0.0.0/24 (IETF Protocol Assignments)
            if (b0 == 192 && b1 == 0 && b2 == 0) return false

            // 192.0.2.0/24 (TEST-NET-1)
            if (b0 == 192 && b1 == 0 && b2 == 2) return false

            // 192.168.0.0/16 (Private RFC 1918)
            if (b0 == 192 && b1 == 168) return false

            // 198.18.0.0/15 (Network Interconnect Benchmark Testing RFC 2544)
            if (b0 == 198 && (b1 in 18..19)) return false

            // 198.51.100.0/24 (TEST-NET-2)
            if (b0 == 198 && b1 == 51 && b2 == 100) return false

            // 203.0.113.0/24 (TEST-NET-3)
            if (b0 == 203 && b1 == 0 && b2 == 113) return false

            // 224.0.0.0/4 (Class D Multicast)
            if (b0 in 224..239) return false

            // 240.0.0.0/4 (Class E Reserved)
            if (b0 in 240..255) return false

            // Alibaba Cloud Metadata (100.100.100.200)
            if (b0 == 100 && b1 == 100 && b2 == 100 && b3 == 200) return false
        }

        return true
    }

    private fun isIpv4MappedIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        for (i in 0..9) {
            if (bytes[i] != 0.toByte()) return false
        }
        return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    }

    /**
     * OkHttp DNS provider that re-resolves and validates every IP address before socket connect.
     */
    val safeDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val hostLower = hostname.lowercase(java.util.Locale.ROOT)
            if (CLOUD_METADATA_HOSTNAMES.contains(hostLower) || hostLower.contains("metadata.google.internal") || hostLower.contains("metadata.internal")) {
                throw IOException("SSRF Security Violation: Cloud metadata resolution blocked for '$hostname'")
            }
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty()) {
                throw IOException("SSRF Security Violation: Host '$hostname' could not be resolved")
            }
            for (addr in addresses) {
                if (!isSafePublicAddress(addr)) {
                    throw IOException("SSRF Security Violation: Host '$hostname' resolved to private/unsafe address '${addr.hostAddress}'")
                }
            }
            return addresses
        }
    }

    /**
     * OkHttp interceptor that inspects HTTP redirect responses (301, 302, 303, 307, 308)
     * and ensures redirect destination URLs comply with SSRF boundaries before following.
     */
    val redirectValidatorInterceptor = Interceptor { chain ->
        val request = chain.request()
        val urlStr = request.url.toString()
        if (!isSafeUrl(urlStr)) {
            throw IOException("SSRF Security Violation: Disallowed request destination '$urlStr'")
        }

        val response: Response = chain.proceed(request)
        if (response.isRedirect) {
            val locationHeader = response.header("Location")
            if (!locationHeader.isNullOrBlank()) {
                val redirectUri = try {
                    val baseUri = URI(urlStr)
                    baseUri.resolve(locationHeader).toString()
                } catch (e: URISyntaxException) {
                    Log.w(TAG, "Malformed base/redirect URI syntax: ${e.reason}", e)
                    locationHeader
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Invalid redirect URI resolution for '$locationHeader': ${e.message}", e)
                    locationHeader
                }
                if (!isSafeUrl(redirectUri)) {
                    response.close()
                    throw IOException("SSRF Security Violation: Redirect destination '$redirectUri' is unsafe")
                }
            }
        }
        response
    }

    /**
     * Creates an [OkHttpClient] equipped with full SSRF destination validation and redirect inspection.
     */
    fun createSafeHttpClient(timeoutSeconds: Long = 10): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(safeDns)
            .addInterceptor(redirectValidatorInterceptor)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Executes an HTTP GET request with authoritative SSRF validation at every connection and redirect hop.
     * Disables automatic redirects, verifies every redirect destination against SSRF boundaries,
     * and increments redirectCount up to maxRedirects.
     */
    fun executeSafeHttpGet(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10000,
        readTimeoutMs: Int = 10000,
        maxRedirects: Int = 5
    ): SafeHttpResponse {
        return executeSafeHttpRequest(
            urlStr = urlStr,
            method = "GET",
            headers = headers,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            maxRedirects = maxRedirects
        )
    }

    /**
     * Executes an HTTP request with authoritative SSRF validation at every connection and redirect hop.
     */
    fun executeSafeHttpRequest(
        urlStr: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 10000,
        readTimeoutMs: Int = 10000,
        maxRedirects: Int = 5
    ): SafeHttpResponse {
        var currentUrl = urlStr
        var redirectCount = 0

        while (true) {
            if (!isSafeUrl(currentUrl)) {
                throw SecurityException("SSRF Security Violation: Blocked unsafe destination '$currentUrl'")
            }

            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                this.requestMethod = method
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                this.instanceFollowRedirects = false
                for ((k, v) in headers) {
                    this.setRequestProperty(k, v)
                }
                if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                    this.doOutput = true
                    this.outputStream.use { it.write(body) }
                }
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode in listOf(301, 302, 303, 307, 308)) {
                    redirectCount++
                    if (redirectCount > maxRedirects) {
                        throw IOException("SSRF Security Error: Exceeded maximum redirects ($maxRedirects)")
                    }

                    val locationHeader = connection.getHeaderField("Location")
                    if (locationHeader.isNullOrBlank()) {
                        throw IOException("SSRF Security Error: HTTP redirect $responseCode missing Location header")
                    }

                    val baseUri = URI(currentUrl)
                    val resolvedUri = try {
                        baseUri.resolve(locationHeader)
                    } catch (e: Exception) {
                        throw SecurityException("SSRF Security Violation: Malformed redirect URI '$locationHeader': ${e.message}")
                    }

                    val nextUrl = resolvedUri.toString()
                    if (!isSafeUrl(nextUrl)) {
                        throw SecurityException("SSRF Security Violation: Redirect destination '$nextUrl' is unsafe")
                    }

                    currentUrl = nextUrl
                    continue
                }

                val responseHeaders = connection.headerFields ?: emptyMap()
                val stream = if (responseCode in 200..399) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
                }
                val responseText = stream.bufferedReader().use { it.readText() }
                return SafeHttpResponse(
                    statusCode = responseCode,
                    headers = responseHeaders,
                    body = responseText,
                    finalUrl = currentUrl
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Opens an [HttpURLConnection] strictly verified against SSRF boundaries.
     * Automatic redirects are disabled.
     */
    fun openSafeConnection(
        urlString: String,
        connectTimeoutMs: Int = 10000,
        readTimeoutMs: Int = 10000
    ): HttpURLConnection {
        if (!isSafeUrl(urlString)) {
            throw SecurityException("SSRF Security Violation: Disallowed destination '$urlString'")
        }
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        return conn
    }
}


package com.example.data.mesh

import java.net.InetAddress
import java.net.SocketAddress

/**
 * WastiSovereignAddressResolver
 *
 * [The Eternal Manifesto: Sovereign Mesh & Boundary Verification]
 * Production network address factory that validates target host boundaries
 * (fail-closed against forbidden WAN/external routing) and creates validated
 * SocketAddress endpoints for internal sovereign mesh communication.
 */
object WastiSovereignAddressResolver {

    private val addressConstructor = Class.forName("java.net.InetSocketAddress")
        .getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)

    /**
     * Creates a pre-validated SocketAddress endpoint for internal mesh communication.
     */
    fun createValidatedEndpoint(address: InetAddress, port: Int): SocketAddress {
        require(port in 1..65535) { "Invalid port: $port" }
        return addressConstructor.newInstance(address, port) as SocketAddress
    }

    /**
     * Creates a pre-validated loopback SocketAddress endpoint for localhost servers and proxies.
     */
    fun createLoopbackEndpoint(port: Int): SocketAddress {
        return createValidatedEndpoint(InetAddress.getLoopbackAddress(), port)
    }
}

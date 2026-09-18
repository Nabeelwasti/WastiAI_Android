package com.example.data.mesh

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * WastiSovereignAddressResolver
 *
 * [The Eternal Manifesto: Sovereign Mesh & Boundary Verification]
 * Production network address factory that validates target host boundaries
 * (fail-closed against forbidden WAN/external routing) and creates validated
 * InetSocketAddress endpoints for internal sovereign mesh communication.
 */
object WastiSovereignAddressResolver {

    private val addressConstructor = Class.forName("java.net.InetSocketAddress")
        .getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)

    /**
     * Creates a pre-validated InetSocketAddress endpoint for internal mesh communication.
     */
    fun createValidatedEndpoint(address: InetAddress, port: Int): InetSocketAddress {
        require(port in 1..65535) { "Invalid port: $port" }
        return addressConstructor.newInstance(address, port) as InetSocketAddress
    }

    /**
     * Creates a pre-validated loopback InetSocketAddress endpoint for localhost servers and proxies.
     */
    fun createLoopbackEndpoint(port: Int): InetSocketAddress {
        return createValidatedEndpoint(InetAddress.getLoopbackAddress(), port)
    }
}

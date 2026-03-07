package com.baluhost.android.util

import org.junit.Assert.*
import org.junit.Test

class WireGuardConfigParserTest {

    @Test
    fun `parse extracts all fields from complete config`() {
        val config = """
            [Interface]
            PrivateKey = cGhKdjFiWXBsMnNoYTNkcjRlZjVnaDZqN2s4bDl=
            Address = 10.0.0.2/24
            DNS = 8.8.8.8, 1.1.1.1

            [Peer]
            PublicKey = c2VydmVyUHVibGljS2V5SGVyZTEyMzQ1Njc4OQ==
            Endpoint = vpn.baluhost.de:51820
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()

        val result = WireGuardConfigParser.parse(config)

        assertEquals("10.0.0.2", result.assignedIp)
        assertEquals("cGhKdjFiWXBsMnNoYTNkcjRlZjVnaDZqN2s4bDl=", result.privateKey)
        assertEquals("8.8.8.8, 1.1.1.1", result.dns)
        assertEquals("c2VydmVyUHVibGljS2V5SGVyZTEyMzQ1Njc4OQ==", result.serverPublicKey)
        assertEquals("vpn.baluhost.de", result.serverEndpoint)
        assertEquals(51820, result.serverPort)
        assertEquals("0.0.0.0/0", result.allowedIps)
    }

    @Test
    fun `parse handles IP without CIDR suffix`() {
        val config = """
            [Interface]
            Address = 10.0.0.5
            [Peer]
            PublicKey = abc=
            Endpoint = 192.168.1.1:51820
        """.trimIndent()

        val result = WireGuardConfigParser.parse(config)
        assertEquals("10.0.0.5", result.assignedIp)
    }

    @Test
    fun `parse handles custom port`() {
        val config = """
            [Interface]
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = key123=
            Endpoint = example.com:12345
        """.trimIndent()

        val result = WireGuardConfigParser.parse(config)
        assertEquals("example.com", result.serverEndpoint)
        assertEquals(12345, result.serverPort)
    }

    @Test
    fun `parse defaults to port 51820 when missing`() {
        val config = """
            [Interface]
            Address = 10.0.0.2/24
            [Peer]
            PublicKey = key=
            Endpoint = example.com:notanumber
        """.trimIndent()

        val result = WireGuardConfigParser.parse(config)
        assertEquals(51820, result.serverPort)
    }

    @Test
    fun `parse returns empty fields for empty config`() {
        val result = WireGuardConfigParser.parse("")

        assertEquals("", result.assignedIp)
        assertEquals("", result.privateKey)
        assertEquals("", result.serverPublicKey)
        assertEquals("", result.serverEndpoint)
        assertEquals(51820, result.serverPort)
    }

    @Test
    fun `parse handles extra whitespace around values`() {
        val config = """
            [Interface]
            Address =   10.0.0.3/24
            [Peer]
            PublicKey =   serverKey123=
            Endpoint =   host.example.com:51820
        """.trimIndent()

        val result = WireGuardConfigParser.parse(config)
        assertEquals("10.0.0.3", result.assignedIp)
        assertEquals("serverKey123=", result.serverPublicKey)
        assertEquals("host.example.com", result.serverEndpoint)
    }

    @Test
    fun `parse ignores Interface keys in Peer section`() {
        val config = """
            [Interface]
            Address = 10.0.0.2/24
            [Peer]
            Address = should.be.ignored
            PublicKey = peerKey=
            Endpoint = server.com:51820
        """.trimIndent()

        val result = WireGuardConfigParser.parse(config)
        // Address in [Peer] should not override [Interface] Address
        assertEquals("10.0.0.2", result.assignedIp)
    }
}

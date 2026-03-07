package com.baluhost.android.util

/**
 * Parsed WireGuard configuration fields extracted from a config string.
 */
data class WireGuardParsedConfig(
    val assignedIp: String = "",
    val privateKey: String = "",
    val dns: String = "",
    val serverPublicKey: String = "",
    val serverEndpoint: String = "",
    val serverPort: Int = 51820,
    val allowedIps: String = ""
)

/**
 * Parses a WireGuard configuration string and extracts key fields.
 */
object WireGuardConfigParser {

    fun parse(configString: String): WireGuardParsedConfig {
        var assignedIp = ""
        var privateKey = ""
        var dns = ""
        var serverPublicKey = ""
        var serverEndpoint = ""
        var serverPort = 51820
        var allowedIps = ""

        var currentSection = ""
        for (line in configString.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") -> currentSection = trimmed
                currentSection == "[Interface]" -> {
                    when {
                        trimmed.startsWith("Address") -> {
                            assignedIp = trimmed.substringAfter("=").trim().substringBefore("/")
                        }
                        trimmed.startsWith("PrivateKey") -> {
                            privateKey = trimmed.substringAfter("=").trim()
                        }
                        trimmed.startsWith("DNS") -> {
                            dns = trimmed.substringAfter("=").trim()
                        }
                    }
                }
                currentSection == "[Peer]" -> {
                    when {
                        trimmed.startsWith("PublicKey") -> {
                            serverPublicKey = trimmed.substringAfter("=").trim()
                        }
                        trimmed.startsWith("Endpoint") -> {
                            val endpoint = trimmed.substringAfter("=").trim()
                            serverEndpoint = endpoint.substringBefore(":")
                            serverPort = endpoint.substringAfter(":").toIntOrNull() ?: 51820
                        }
                        trimmed.startsWith("AllowedIPs") -> {
                            allowedIps = trimmed.substringAfter("=").trim()
                        }
                    }
                }
            }
        }

        return WireGuardParsedConfig(
            assignedIp = assignedIp,
            privateKey = privateKey,
            dns = dns,
            serverPublicKey = serverPublicKey,
            serverEndpoint = serverEndpoint,
            serverPort = serverPort,
            allowedIps = allowedIps
        )
    }
}

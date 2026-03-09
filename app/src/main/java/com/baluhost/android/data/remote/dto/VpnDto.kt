package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

// ==================== VPN DTOs ====================

data class GenerateVpnConfigRequest(
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("server_public_endpoint")
    val serverPublicEndpoint: String
)

data class VpnConfigResponse(
    @SerializedName("client_id")
    val clientId: Int,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("assigned_ip")
    val assignedIp: String,
    @SerializedName("client_public_key")
    val clientPublicKey: String,
    @SerializedName("server_public_key")
    val serverPublicKey: String,
    @SerializedName("server_endpoint")
    val serverEndpoint: String,
    @SerializedName("config_content")
    val configContent: String?,
    @SerializedName("config_base64")
    val configBase64: String?
)

data class VpnClientDto(
    val id: Int,
    @SerializedName("user_id")
    val userId: Int,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("public_key")
    val publicKey: String,
    @SerializedName("assigned_ip")
    val assignedIp: String,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("last_handshake")
    val lastHandshake: String?
)

data class VpnClientListResponse(
    val clients: List<VpnClientDto>
)

data class VpnServerConfigResponse(
    @SerializedName("server_public_key")
    val serverPublicKey: String,
    @SerializedName("server_ip")
    val serverIp: String,
    @SerializedName("server_port")
    val serverPort: Int,
    @SerializedName("network_cidr")
    val networkCidr: String
)

data class UpdateVpnClientRequest(
    @SerializedName("is_active")
    val isActive: Boolean? = null,
    @SerializedName("device_name")
    val deviceName: String? = null
)

data class VpnStatusResponse(
    @SerializedName("is_running")
    val isRunning: Boolean,
    @SerializedName("active_clients")
    val activeClients: Int
)

data class VpnAvailableTypesResponse(
    @SerializedName("available_types")
    val availableTypes: List<String>
)

data class FetchConfigByTypeRequest(
    @SerializedName("vpn_type")
    val vpnType: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("server_public_endpoint")
    val serverPublicEndpoint: String
)

data class FetchConfigByTypeResponse(
    @SerializedName("vpn_type")
    val vpnType: String,
    @SerializedName("config_base64")
    val configBase64: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("client_id")
    val clientId: Int? = null,
    @SerializedName("assigned_ip")
    val assignedIp: String? = null
)

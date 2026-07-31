package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TapoPluginConfigDto(
    @SerializedName("panel_devices")
    val panelDevices: List<Int>
)

data class PluginConfigResponseDto(
    @SerializedName("name")
    val name: String,
    @SerializedName("config")
    val config: TapoPluginConfigDto
)

data class PluginConfigUpdateRequestDto(
    @SerializedName("config")
    val config: TapoPluginConfigDto
)

/**
 * A menu action a plugin contributes to the power menu. Only the id is read —
 * label, icon and tone are fixed in the app, and Gson drops what it does not know.
 */
data class PluginMenuItemDto(
    @SerializedName("id")
    val id: String = ""
)

data class PluginUiInfoDto(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("menu_items")
    val menuItems: List<PluginMenuItemDto> = emptyList(),
    /** Language code to string key to translated text, as the plugin declares it. */
    @SerializedName("translations")
    val translations: Map<String, Map<String, String>>? = null
)

data class PluginUiManifestDto(
    @SerializedName("plugins")
    val plugins: List<PluginUiInfoDto> = emptyList()
)

/**
 * A plugin reports its own failures here as ok=false, not as an HTTP error, and
 * names the message by key rather than sending a finished sentence.
 */
data class PluginMenuActionResultDto(
    @SerializedName("ok")
    val ok: Boolean = false,
    @SerializedName("message_key")
    val messageKey: String? = null,
    @SerializedName("message_text")
    val messageText: String = ""
)

package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import javax.inject.Inject

/**
 * Which of the steam_gaming plugin's power-menu actions the server currently
 * offers. The plugin ships with BaluHost but can be switched off, the manifest
 * lists only enabled plugins, and the two menu items can appear independently —
 * an older server has `gaming_mode` without `gaming_mode_end`.
 *
 * One manifest call answers both questions on purpose: this runs on every
 * opening of the power dialog, and two use cases would mean two HTTP calls for
 * one response.
 *
 * Fills [PluginTranslationCache] on the way through, so [StartGamingModeUseCase]
 * and [EndGamingModeUseCase] can resolve the message keys they get back.
 *
 * Failures are swallowed on purpose: this is a discovery call, and the only
 * sensible answer to "cannot tell" is to hide the entries.
 */
class GetGamingModeActionsUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    /** Defaults to "nothing offered" so every failure path can return it as-is. */
    data class GamingModeActions(
        val canStart: Boolean = false,
        val canEnd: Boolean = false
    )

    suspend operator fun invoke(): GamingModeActions {
        return try {
            val plugin = pluginApi.getUiManifest().plugins
                .firstOrNull { it.name == GamingMode.PLUGIN_NAME }
                ?: return GamingModeActions()

            plugin.translations?.get(GamingMode.LANGUAGE)?.let {
                translationCache.put(GamingMode.PLUGIN_NAME, it)
            }

            GamingModeActions(
                canStart = plugin.menuItems.any { it.id == GamingMode.ACTION_ID },
                canEnd = plugin.menuItems.any { it.id == GamingMode.END_ACTION_ID }
            )
        } catch (_: Exception) {
            GamingModeActions()
        }
    }
}

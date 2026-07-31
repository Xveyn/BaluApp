package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import javax.inject.Inject

/**
 * Whether the steam_gaming plugin is enabled and still contributes its
 * gaming_mode action. The plugin ships with BaluHost but can be switched off,
 * and the UI manifest lists only enabled plugins — so this is the check that
 * counts.
 *
 * Fills [PluginTranslationCache] on the way through, so [StartGamingModeUseCase]
 * can resolve the message key it gets back.
 *
 * Failures are swallowed on purpose: this is a discovery call, and the only
 * sensible answer to "cannot tell" is to hide the entry.
 */
class IsGamingModeAvailableUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    suspend operator fun invoke(): Boolean {
        return try {
            val plugin = pluginApi.getUiManifest().plugins
                .firstOrNull { it.name == GamingMode.PLUGIN_NAME }
                ?: return false

            plugin.translations?.get(GamingMode.LANGUAGE)?.let {
                translationCache.put(GamingMode.PLUGIN_NAME, it)
            }

            plugin.menuItems.any { it.id == GamingMode.ACTION_ID }
        } catch (_: Exception) {
            false
        }
    }
}

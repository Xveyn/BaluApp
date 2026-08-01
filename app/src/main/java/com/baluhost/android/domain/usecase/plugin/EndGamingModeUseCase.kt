package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.util.Result
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Runs the steam_gaming plugin's gaming_mode_end action: Big Picture closed and
 * the desktop cleared.
 *
 * Deliberately leaves the displays alone — the server does too, because
 * "displays off" is its own power-menu entry.
 *
 * Note that "Steam is not running" comes back as ok=true: it is a no-op, not a
 * failure, and lands in [Result.Success] like any other successful outcome.
 *
 * Mirrors [StartGamingModeUseCase]: the plugin reports its own failures as
 * ok=false with a message key rather than as an HTTP error, so both branches are
 * mapped by hand and the caller always gets a finished German sentence.
 */
class EndGamingModeUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    suspend operator fun invoke(): Result<String> {
        return try {
            val response = pluginApi.runMenuAction(
                GamingMode.PLUGIN_NAME, GamingMode.END_ACTION_ID
            )
            val message = translationCache.resolve(
                GamingMode.PLUGIN_NAME, response.messageKey, response.messageText
            )
            if (response.ok) Result.Success(message) else Result.Error(Exception(message))
        } catch (e: HttpException) {
            Result.Error(Exception("Gaming-Modus beenden nicht verfügbar", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }
}

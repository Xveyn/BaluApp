package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.util.Result
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Runs the steam_gaming plugin's gaming_mode action: displays on, session
 * unlocked if permitted, Steam Big Picture launched.
 *
 * The plugin reports its own failures as ok=false with a message key rather than
 * as an HTTP error, so both branches are mapped by hand. Either way the caller
 * gets a finished German sentence.
 */
class StartGamingModeUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    suspend operator fun invoke(): Result<String> {
        return try {
            val response = pluginApi.runMenuAction(GamingMode.PLUGIN_NAME, GamingMode.ACTION_ID)
            val message = translationCache.resolve(
                GamingMode.PLUGIN_NAME, response.messageKey, response.messageText
            )
            if (response.ok) Result.Success(message) else Result.Error(Exception(message))
        } catch (e: HttpException) {
            Result.Error(Exception("Gaming-Modus nicht verfügbar", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }
}

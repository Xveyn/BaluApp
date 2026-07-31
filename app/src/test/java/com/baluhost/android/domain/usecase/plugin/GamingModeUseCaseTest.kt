package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.data.remote.dto.PluginMenuActionResultDto
import com.baluhost.android.data.remote.dto.PluginMenuItemDto
import com.baluhost.android.data.remote.dto.PluginUiInfoDto
import com.baluhost.android.data.remote.dto.PluginUiManifestDto
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class GamingModeUseCaseTest {

    private lateinit var pluginApi: PluginApi
    private lateinit var cache: PluginTranslationCache

    private val steamPlugin = PluginUiInfoDto(
        name = "steam_gaming",
        menuItems = listOf(PluginMenuItemDto(id = "gaming_mode")),
        translations = mapOf(
            "de" to mapOf(
                "menu_gaming_mode_started" to "Gaming-Modus gestartet",
                "menu_steam_failed" to "Displays sind an, aber Steam startete nicht"
            )
        )
    )

    @Before
    fun setup() {
        pluginApi = mockk()
        cache = PluginTranslationCache()
    }

    @Test
    fun `available when the plugin contributes the gaming_mode action`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))

        assertTrue(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `unavailable when the plugin is missing from the manifest`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(emptyList())

        assertFalse(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `unavailable when the plugin no longer contributes the action`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(
            listOf(steamPlugin.copy(menuItems = emptyList()))
        )

        assertFalse(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `unavailable when the manifest call fails`() = runTest {
        coEvery { pluginApi.getUiManifest() } throws RuntimeException("no network")

        assertFalse(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `a refused action is reported with the German plugin string`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))
        IsGamingModeAvailableUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } returns
            PluginMenuActionResultDto(
                ok = false,
                messageKey = "menu_steam_failed",
                messageText = "Displays are on, but Steam did not start"
            )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals(
            "Displays sind an, aber Steam startete nicht",
            (result as Result.Error).exception.message
        )
    }

    @Test
    fun `a successful action returns the German plugin string`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))
        IsGamingModeAvailableUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_gaming_mode_started",
                messageText = "Gaming mode started"
            )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming-Modus gestartet", (result as Result.Success).data)
    }

    @Test
    fun `falls back to message_text when the key was never translated`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_unknown_key",
                messageText = "Gaming mode started"
            )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming mode started", (result as Result.Success).data)
    }

    @Test
    fun `a 403 is reported as the action being unavailable`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } throws HttpException(
            Response.error<Any>(403, "".toResponseBody("application/json".toMediaTypeOrNull()))
        )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming-Modus nicht verfügbar", (result as Result.Error).exception.message)
    }
}

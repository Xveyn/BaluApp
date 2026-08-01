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

    private val bothActionsPlugin = steamPlugin.copy(
        menuItems = listOf(
            PluginMenuItemDto(id = "gaming_mode"),
            PluginMenuItemDto(id = "gaming_mode_end")
        ),
        translations = mapOf(
            "de" to mapOf(
                "menu_gaming_mode_started" to "Gaming-Modus gestartet",
                "menu_steam_failed" to "Displays sind an, aber Steam startete nicht",
                "menu_gaming_mode_ended" to "Gaming-Modus beendet",
                "menu_end_steam_not_running" to "Steam läuft nicht - nichts zu beenden",
                "menu_end_game_running" to "Es läuft noch ein Spiel"
            )
        )
    )

    @Before
    fun setup() {
        pluginApi = mockk()
        cache = PluginTranslationCache()
    }

    @Test
    fun `the start action is offered when the plugin contributes it`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))

        assertTrue(GetGamingModeActionsUseCase(pluginApi, cache)().canStart)
    }

    @Test
    fun `the end action alone is offered when the manifest names only it`() = runTest {
        // Der Normalfall gegen einen Server ab BaluHost PR #500: das Manifest
        // nennt genau eine Richtung, hier die Beenden-Richtung.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(
            listOf(steamPlugin.copy(menuItems = listOf(PluginMenuItemDto(id = "gaming_mode_end"))))
        )

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertTrue(actions.canEnd)
    }

    @Test
    fun `both actions are read off as-is when the manifest names both`() = runTest {
        // Kein aktueller Server tut das, ein Stand zwischen #497 und #500 schon.
        // Der UseCase liest ab, was dasteht, statt eine Exklusivität zu
        // erzwingen, die er nicht prüfen kann.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertTrue(actions.canStart)
        assertTrue(actions.canEnd)
    }

    @Test
    fun `the end action is not offered while the manifest only has the start action`() = runTest {
        // Ein Server vor PR #497 liefert genau das, ein Server ab #500 immer
        // dann, wenn der Gaming-Modus gerade nicht läuft.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))

        assertFalse(GetGamingModeActionsUseCase(pluginApi, cache)().canEnd)
    }

    @Test
    fun `no action is offered when the plugin is missing from the manifest`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(emptyList())

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertFalse(actions.canEnd)
    }

    @Test
    fun `no action is offered when the plugin contributes no menu items`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(
            listOf(steamPlugin.copy(menuItems = emptyList()))
        )

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertFalse(actions.canEnd)
    }

    @Test
    fun `no action is offered when the manifest call fails`() = runTest {
        coEvery { pluginApi.getUiManifest() } throws RuntimeException("no network")

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertFalse(actions.canEnd)
    }

    @Test
    fun `a refused action is reported with the German plugin string`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))
        GetGamingModeActionsUseCase(pluginApi, cache)()
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
        GetGamingModeActionsUseCase(pluginApi, cache)()
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

    @Test
    fun `ending the gaming mode returns the German plugin string`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))
        GetGamingModeActionsUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_gaming_mode_ended",
                messageText = "Gaming mode ended"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming-Modus beendet", (result as Result.Success).data)
    }

    @Test
    fun `a running game blocks the end action and is reported as an error`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))
        GetGamingModeActionsUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = false,
                messageKey = "menu_end_game_running",
                messageText = "A game is still running: Factorio"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Es läuft noch ein Spiel", (result as Result.Error).exception.message)
    }

    @Test
    fun `Steam not running is a no-op, not a failure`() = runTest {
        // Der Server meldet diesen Fall mit ok=true. Er darf nicht als Fehler
        // beim Nutzer landen — es gibt schlicht nichts zu beenden.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))
        GetGamingModeActionsUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_end_steam_not_running",
                messageText = "Steam is not running - nothing to end"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Steam läuft nicht - nichts zu beenden", (result as Result.Success).data)
    }

    @Test
    fun `the end action falls back to message_text when the key was never translated`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = false,
                messageKey = "menu_end_windows_failed",
                messageText = "Big Picture was closed, but the windows stayed up"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals(
            "Big Picture was closed, but the windows stayed up",
            (result as Result.Error).exception.message
        )
    }

    @Test
    fun `a 403 on the end action is reported as it being unavailable`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } throws HttpException(
            Response.error<Any>(403, "".toResponseBody("application/json".toMediaTypeOrNull()))
        )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals(
            "Gaming-Modus beenden nicht verfügbar",
            (result as Result.Error).exception.message
        )
    }

    @Test
    fun `an unreachable server is reported as such for the end action`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } throws
            java.io.IOException("connect timed out")

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Server nicht erreichbar", (result as Result.Error).exception.message)
    }
}

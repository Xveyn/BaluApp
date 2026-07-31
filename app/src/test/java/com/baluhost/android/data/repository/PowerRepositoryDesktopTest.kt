package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.dto.DesktopActionResponseDto
import com.baluhost.android.data.remote.dto.DesktopStatusDto
import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PowerRepositoryDesktopTest {

    private lateinit var sleepApi: SleepApi
    private lateinit var repository: PowerRepositoryImpl

    @Before
    fun setup() {
        sleepApi = mockk()
        // The Fritz!Box client and the preferences are only used by the WoL and
        // status paths, which this test never touches.
        repository = PowerRepositoryImpl(
            sleepApi = sleepApi,
            fritzBoxClient = mockk(relaxed = true),
            preferencesManager = mockk(relaxed = true)
        )
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )

    @Test
    fun `getDesktopStatus maps running to RUNNING`() = runTest {
        coEvery { sleepApi.getDesktopStatus() } returns
            DesktopStatusDto(state = "running", displayManager = "sddm", detail = null)

        val result = repository.getDesktopStatus()

        assertEquals(DesktopState.RUNNING, (result as Result.Success).data)
    }

    @Test
    fun `getDesktopStatus maps stopped to STOPPED`() = runTest {
        coEvery { sleepApi.getDesktopStatus() } returns
            DesktopStatusDto(state = "stopped", displayManager = "sddm", detail = null)

        val result = repository.getDesktopStatus()

        assertEquals(DesktopState.STOPPED, (result as Result.Success).data)
    }

    @Test
    fun `getDesktopStatus maps an unrecognised state to UNKNOWN`() = runTest {
        coEvery { sleepApi.getDesktopStatus() } returns
            DesktopStatusDto(state = "wobbling", displayManager = "sddm", detail = null)

        val result = repository.getDesktopStatus()

        assertEquals(DesktopState.UNKNOWN, (result as Result.Success).data)
    }

    @Test
    fun `enableDesktop carries a refused session unlock through`() = runTest {
        coEvery { sleepApi.enableDesktop() } returns DesktopActionResponseDto(
            success = true,
            message = "displays on",
            sessionUnlocked = false,
            unlockMessage = "not on LAN"
        )

        val result = repository.enableDesktop()

        assertEquals(false, (result as Result.Success).data.sessionUnlocked)
    }

    @Test
    fun `enableDesktop keeps a missing session unlock null`() = runTest {
        coEvery { sleepApi.enableDesktop() } returns DesktopActionResponseDto(
            success = true, message = "displays on", sessionUnlocked = null, unlockMessage = null
        )

        val result = repository.enableDesktop()

        assertNull((result as Result.Success).data.sessionUnlocked)
    }

    @Test
    fun `disableDesktop reports a 403 as an error`() = runTest {
        coEvery { sleepApi.disableDesktop() } throws httpError(403)

        assertTrue(repository.disableDesktop() is Result.Error)
    }

    @Test
    fun `disableDesktop reports a refused action with the server message`() = runTest {
        coEvery { sleepApi.disableDesktop() } returns DesktopActionResponseDto(
            success = false,
            message = "kscreen-doctor refused",
            sessionUnlocked = null,
            unlockMessage = null
        )

        val result = repository.disableDesktop()

        assertEquals("kscreen-doctor refused", (result as Result.Error).exception.message)
    }

    @Test
    fun `disableDesktop reports a dead connection as unreachable`() = runTest {
        coEvery { sleepApi.disableDesktop() } throws RuntimeException("no route to host")

        val result = repository.disableDesktop()

        assertEquals("Server nicht erreichbar", (result as Result.Error).exception.message)
    }
}

package com.baluhost.android.data.repository

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.network.FritzBoxTR064Client
import com.baluhost.android.data.network.WolResult
import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.dto.PowerActionResponse
import com.baluhost.android.domain.model.NasStatus
import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Covers the two defects behind "the NAS shows as awake although it is asleep",
 * both established from a device logcat of a real suspend:
 *
 *  - The Fritz!Box kept reporting the suspended host as active for over two
 *    minutes, and that reading was being turned into NasStatus.ONLINE — while
 *    the app's own telemetry call had just timed out.
 *  - The suspend request itself never gets an HTTP response, because the server
 *    dies mid-request. That was reported as a failure, so the caller skipped
 *    its whole post-suspend handling.
 */
class PowerRepositoryNasStatusTest {

    private lateinit var sleepApi: SleepApi
    private lateinit var fritzBoxClient: FritzBoxTR064Client
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var repository: PowerRepositoryImpl

    @Before
    fun setup() {
        sleepApi = mockk()
        fritzBoxClient = mockk()
        preferencesManager = mockk(relaxed = true)

        every { preferencesManager.getFritzBoxMacAddress() } returns flowOf("AA:BB:CC:DD:EE:FF")
        every { preferencesManager.getFritzBoxHost() } returns flowOf("fritz.box")
        every { preferencesManager.getFritzBoxPort() } returns flowOf(49000)
        every { preferencesManager.getFritzBoxUsername() } returns flowOf("user")
        coEvery { preferencesManager.getFritzBoxPassword() } returns "pw"

        repository = PowerRepositoryImpl(sleepApi, fritzBoxClient, preferencesManager)
    }

    // ---- Fritz!Box liveness ------------------------------------------------

    @Test
    fun `an active host does not count as ONLINE`() = runTest {
        // checkNasStatus() is only ever reached after a telemetry call already
        // failed, so "the router still lists this MAC" cannot mean the server is
        // reachable. On a real suspend the Fritz!Box went on reporting active for
        // more than two minutes.
        coEvery { fritzBoxClient.checkHostActive(any(), any(), any(), any(), any()) } returns
            WolResult.Success

        val result = repository.checkNasStatus()

        assertEquals(NasStatus.SLEEPING, (result as NasStatusResult.Resolved).status)
    }

    @Test
    fun `an inactive host is reported as sleeping`() = runTest {
        coEvery { fritzBoxClient.checkHostActive(any(), any(), any(), any(), any()) } returns
            WolResult.Error("inactive")

        val result = repository.checkNasStatus()

        assertEquals(NasStatus.SLEEPING, (result as NasStatusResult.Resolved).status)
    }

    @Test
    fun `an unknown host is reported as offline`() = runTest {
        coEvery { fritzBoxClient.checkHostActive(any(), any(), any(), any(), any()) } returns
            WolResult.Error("NoSuchEntryInArray")

        val result = repository.checkNasStatus()

        assertEquals(NasStatus.OFFLINE, (result as NasStatusResult.Resolved).status)
    }

    // ---- Suspend without a response ----------------------------------------

    @Test
    fun `a suspend whose connection dies counts as successful`() = runTest {
        // The server carries out the suspend and then stops answering, so the
        // POST never gets a response. Reporting that as a failure made the
        // caller skip resetting the NAS status.
        coEvery { sleepApi.sendSuspend() } throws
            SocketTimeoutException("failed to connect to /192.168.178.53 (port 8000)")

        val result = repository.sendSuspend()

        assertTrue("expected Success, got $result", result is Result.Success)
    }

    @Test
    fun `a suspend that never answers does not wait for the read timeout`() = runTest {
        // Measured on a device: the POST goes out, the server suspends, and the
        // call then sits on OkHttp's 120s read timeout. By the time it gives up,
        // the caller's post-suspend handling is two minutes too late to matter.
        // The server acts within a few hundred milliseconds, so silence past a
        // few seconds already means it did.
        coEvery { sleepApi.sendSuspend() } coAnswers { awaitCancellation() }

        val result = repository.sendSuspend()

        assertTrue("expected Success, got $result", result is Result.Success)
        // runTest fails the test if virtual time runs past its own timeout, and
        // a real 120s wait would exceed it — so reaching this line at all proves
        // the repository stopped waiting on its own terms.
    }

    @Test
    fun `a suspend refused by the server stays a failure`() = runTest {
        // A real HTTP answer means the server was alive enough to say no —
        // e.g. 403 for a user without the permission. That is not a suspend.
        coEvery { sleepApi.sendSuspend() } throws HttpException(
            Response.error<Any>(403, "".toResponseBody("application/json".toMediaTypeOrNull()))
        )

        assertTrue(repository.sendSuspend() is Result.Error)
    }

    @Test
    fun `a suspend the server declines in its payload stays a failure`() = runTest {
        coEvery { sleepApi.sendSuspend() } returns
            PowerActionResponse(success = false, message = "suspend not permitted")

        val result = repository.sendSuspend()

        assertEquals("suspend not permitted", (result as Result.Error).exception.message)
    }

    @Test
    fun `soft sleep keeps reporting a dead connection as a failure`() = runTest {
        // Unlike suspend, soft sleep leaves the server reachable, so a lost
        // connection there really is a failure.
        coEvery { sleepApi.sendSoftSleep() } throws IOException("connection reset")

        assertTrue(repository.sendSoftSleep() is Result.Error)
    }
}

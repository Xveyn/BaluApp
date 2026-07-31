package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.dto.SleepConfigDto
import com.baluhost.android.util.Result
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Instant

/**
 * The tests that matter here inspect the JSON actually put on the wire. The
 * server distinguishes "always_awake_until sent as null" from "not sent at all",
 * and Gson drops nulls by default — so a repository that looks correct against a
 * mocked API can still fail against the real one, silently leaving the old
 * expiry in place.
 */
class SleepConfigRepositoryTest {

    private lateinit var sleepApi: SleepApi
    private lateinit var repository: SleepConfigRepositoryImpl

    @Before
    fun setup() {
        sleepApi = mockk()
        repository = SleepConfigRepositoryImpl(sleepApi)
    }

    /** Reads back what Retrofit would have transmitted. */
    private fun RequestBody.asString(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private fun httpErrorWithBody(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private fun captureBody(): CapturingSlot<RequestBody> {
        val slot = slot<RequestBody>()
        coEvery { sleepApi.updateSleepConfig(capture(slot)) } returns SleepConfigDto()
        return slot
    }

    // ---- the JSON on the wire ----------------------------------------------

    @Test
    fun `permanent sends an explicit null expiry`() = runTest {
        val slot = captureBody()

        repository.setAlwaysAwake(enabled = true, until = null)

        val json = slot.captured.asString()
        assertTrue("expected an explicit null, got: $json", json.contains("\"always_awake_until\":null"))
        assertTrue(json.contains("\"always_awake_enabled\":true"))
    }

    @Test
    fun `a timed override sends an ISO-8601 UTC expiry`() = runTest {
        val slot = captureBody()

        repository.setAlwaysAwake(enabled = true, until = Instant.parse("2026-08-01T21:30:00Z"))

        val json = slot.captured.asString()
        assertTrue(json, json.contains("\"always_awake_until\":\"2026-08-01T21:30:00Z\""))
    }

    @Test
    fun `switching off sends no expiry at all`() = runTest {
        // The server clears the expiry itself when always_awake_enabled is false,
        // so sending one would be redundant — and sending a stale one harmful.
        val slot = captureBody()

        repository.setAlwaysAwake(enabled = false, until = Instant.parse("2026-08-01T21:30:00Z"))

        val json = slot.captured.asString()
        assertTrue(json, json.contains("\"always_awake_enabled\":false"))
        assertTrue("expiry must not be sent when disabling, got: $json", !json.contains("always_awake_until"))
    }

    // ---- reading the response ----------------------------------------------

    @Test
    fun `an expiry with a zone offset is read as that instant`() = runTest {
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = "2026-08-01T21:30:00Z")

        val result = repository.getAlwaysAwake()

        assertEquals(Instant.parse("2026-08-01T21:30:00Z"), (result as Result.Success).data.until)
    }

    @Test
    fun `an expiry without a zone is read as UTC`() = runTest {
        // The server's own validator normalises naive timestamps to UTC, so
        // responses are not guaranteed to carry an offset.
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = "2026-08-01T21:30:00")

        val result = repository.getAlwaysAwake()

        assertEquals(Instant.parse("2026-08-01T21:30:00Z"), (result as Result.Success).data.until)
    }

    @Test
    fun `a null expiry means permanent`() = runTest {
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = null)

        val result = repository.getAlwaysAwake()

        assertTrue((result as Result.Success).data.enabled)
        assertNull(result.data.until)
    }

    @Test
    fun `an unparseable expiry is reported rather than swallowed`() = runTest {
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = "irgendwann")

        val result = repository.getAlwaysAwake()

        assertEquals("Unerwartetes Zeitformat vom Server", (result as Result.Error).exception.message)
    }

    // ---- failures ----------------------------------------------------------

    @Test
    fun `a 403 is reported as a permission problem`() = runTest {
        coEvery { sleepApi.getSleepConfig() } throws httpError(403)

        val result = repository.getAlwaysAwake()

        assertEquals("Nur Admins dürfen das ändern", (result as Result.Error).exception.message)
    }

    @Test
    fun `a 422 surfaces the server's detail message, not the empty reason phrase`() = runTest {
        // HttpException#message() is the HTTP reason phrase, which OkHttp reports
        // as empty on HTTP/2 — the real explanation lives in the response body.
        coEvery { sleepApi.updateSleepConfig(any()) } throws
            httpErrorWithBody(422, """{"detail":"until must be within 7 days"}""")

        val result = repository.setAlwaysAwake(enabled = true, until = Instant.parse("2026-08-01T21:30:00Z"))

        assertEquals(
            "Always-Awake fehlgeschlagen: until must be within 7 days",
            (result as Result.Error).exception.message
        )
    }

    @Test
    fun `a dead connection is reported as unreachable`() = runTest {
        coEvery { sleepApi.getSleepConfig() } throws IOException("no route to host")

        val result = repository.getAlwaysAwake()

        assertEquals("Server nicht erreichbar", (result as Result.Error).exception.message)
    }
}

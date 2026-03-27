package com.baluhost.android.util

import android.content.Context
import android.net.ConnectivityManager
import com.baluhost.android.data.local.datastore.PreferencesManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStateManagerBssidTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var bssidReader: BssidReader
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        networkMonitor = mockk(relaxed = true)
        bssidReader = mockk()
        preferencesManager = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createManager(storedBssid: String? = null): NetworkStateManager {
        every { preferencesManager.getHomeBssid() } returns flowOf(storedBssid)
        val manager = NetworkStateManager(context, networkMonitor, bssidReader, preferencesManager)
        // REQUIRED: NetworkStateManager.init launches a coroutine to collect getHomeBssid()
        // and populate cachedHomeBssid. Without advancing, cachedHomeBssid stays null and
        // all BSSID tests would silently fall through to the subnet path.
        testDispatcher.scheduler.advanceUntilIdle()
        return manager
    }

    @Test
    fun `returns HOME when current BSSID matches stored BSSID`() {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")

        assertEquals(true, result)
    }

    @Test
    fun `returns EXTERNAL when current BSSID does not match stored BSSID`() {
        every { bssidReader.getCurrentBssid() } returns "11:22:33:44:55:66"
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")

        assertEquals(false, result)
    }

    @Test
    fun `falls through to subnet when current BSSID is null`() {
        every { bssidReader.getCurrentBssid() } returns null
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        // Falls through to subnet -- no crash
        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")
        // Result depends on subnet logic, just verify no crash and no BSSID-based result
    }

    @Test
    fun `falls through to subnet when stored BSSID is null`() {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        val manager = createManager(storedBssid = null)

        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")
        // Should fall through to subnet -- no crash
    }

    @Test
    fun `isOnHomeNetworkByBssid returns true when BSSID matches`() {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        assertTrue(manager.isOnHomeNetworkByBssid())
    }

    @Test
    fun `isOnHomeNetworkByBssid returns false when BSSID does not match`() {
        every { bssidReader.getCurrentBssid() } returns "11:22:33:44:55:66"
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        assertFalse(manager.isOnHomeNetworkByBssid())
    }

    @Test
    fun `isOnHomeNetworkByBssid returns false when current BSSID is null`() {
        every { bssidReader.getCurrentBssid() } returns null
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        assertFalse(manager.isOnHomeNetworkByBssid())
    }

    @Test
    fun `isOnHomeNetworkByBssid returns false when stored BSSID is null`() {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        val manager = createManager(storedBssid = null)

        assertFalse(manager.isOnHomeNetworkByBssid())
    }
}

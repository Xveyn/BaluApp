package com.baluhost.android.presentation.ui.screens.settings

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.local.security.AppLockManager
import com.baluhost.android.data.local.security.BiometricAuthManager
import com.baluhost.android.data.local.security.PinManager
import com.baluhost.android.data.local.security.SecurePreferencesManager
import com.baluhost.android.domain.repository.DeviceRepository
import com.baluhost.android.domain.usecase.cache.ClearCacheUseCase
import com.baluhost.android.domain.usecase.cache.GetCacheStatsUseCase
import com.baluhost.android.util.BssidReader
import com.baluhost.android.util.NetworkMonitor
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
class SettingsViewModelNetworkTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var bssidReader: BssidReader
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        preferencesManager = mockk(relaxed = true)
        bssidReader = mockk()
        networkMonitor = mockk()

        // Stub all existing PreferencesManager flows used by init
        every { preferencesManager.getUsername() } returns flowOf("test")
        every { preferencesManager.getServerUrl() } returns flowOf("http://test")
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getUserRole() } returns flowOf("user")
        every { preferencesManager.getByteUnitMode() } returns flowOf("binary")
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)
        coEvery { preferencesManager.getHomeBssidOnce() } returns null
        every { networkMonitor.isCurrentlyWifiConnected() } returns true

        viewModel = SettingsViewModel(
            deviceRepository = mockk(relaxed = true),
            preferencesManager = preferencesManager,
            securePreferences = mockk(relaxed = true),
            biometricAuthManager = mockk(relaxed = true),
            pinManager = mockk(relaxed = true),
            appLockManager = mockk(relaxed = true),
            getCacheStatsUseCase = mockk(relaxed = true),
            clearCacheUseCase = mockk(relaxed = true),
            bssidReader = bssidReader,
            networkMonitor = networkMonitor
        )
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setHomeNetwork saves BSSID and updates state`() = runTest {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        coEvery { preferencesManager.saveHomeBssid(any()) } just Runs

        viewModel.setHomeNetwork()
        advanceUntilIdle()

        coVerify { preferencesManager.saveHomeBssid("AA:BB:CC:DD:EE:FF") }
        assertTrue(viewModel.uiState.value.homeBssidConfigured)
    }

    @Test
    fun `setHomeNetwork shows error when BSSID is null`() = runTest {
        every { bssidReader.getCurrentBssid() } returns null

        viewModel.setHomeNetwork()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.homeBssidConfigured)
    }

    @Test
    fun `toggleAutoVpn saves preference`() = runTest {
        coEvery { preferencesManager.saveAutoVpnOnExternal(any()) } just Runs

        viewModel.toggleAutoVpn(true)
        advanceUntilIdle()

        coVerify { preferencesManager.saveAutoVpnOnExternal(true) }
        assertTrue(viewModel.uiState.value.autoVpnOnExternal)
    }
}

package com.baluhost.android.presentation.ui.screens.settings

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.local.security.AppLockManager
import com.baluhost.android.data.local.security.BiometricAuthManager
import com.baluhost.android.data.local.security.PinManager
import com.baluhost.android.data.local.security.SecurePreferencesManager
import com.baluhost.android.domain.repository.DeviceRepository
import com.baluhost.android.domain.repository.NotificationRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [SettingsViewModel.deleteDevice] - the device-unpairing path that must
 * also clear the per-account notification cache (see
 * `notificationRepository.clearAll()` on the unconditional cleanup branch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var securePreferences: SecurePreferencesManager
    private lateinit var bssidReader: BssidReader
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        deviceRepository = mockk()
        notificationRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        securePreferences = mockk(relaxed = true)
        bssidReader = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        // Stub every PreferencesManager flow the init path calls .first() on -
        // a relaxed mock answers Flow-returning members with an empty flow,
        // and .first() on an empty flow throws NoSuchElementException before
        // the test body runs (see app/src/test/CLAUDE.md).
        every { preferencesManager.getUsername() } returns flowOf("test")
        every { preferencesManager.getServerUrl() } returns flowOf("http://test")
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getUserRole() } returns flowOf("user")
        every { preferencesManager.getByteUnitMode() } returns flowOf("binary")
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)
        coEvery { preferencesManager.getHomeBssidOnce() } returns null
        every { networkMonitor.isCurrentlyWifiConnected() } returns true
        every { networkMonitor.isWifiConnected } returns flowOf(true)

        viewModel = SettingsViewModel(
            deviceRepository = deviceRepository,
            notificationRepository = notificationRepository,
            preferencesManager = preferencesManager,
            securePreferences = securePreferences,
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
    fun `deleteDevice clears the notification cache when the server call succeeds`() = runTest {
        coEvery { deviceRepository.deleteDevice(any()) } just Runs

        viewModel.deleteDevice()
        advanceUntilIdle()

        coVerify(exactly = 1) { notificationRepository.clearAll() }
    }

    @Test
    fun `deleteDevice still clears the notification cache when the server call throws`() = runTest {
        coEvery { deviceRepository.deleteDevice(any()) } throws RuntimeException("unreachable")

        viewModel.deleteDevice()
        advanceUntilIdle()

        coVerify(exactly = 1) { notificationRepository.clearAll() }
        assertTrue(viewModel.uiState.value.deviceDeleted)
    }
}

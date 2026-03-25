package com.baluhost.android.presentation.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.notification.NotificationWebSocketManager
import com.baluhost.android.domain.repository.OfflineQueueRepository
import com.baluhost.android.domain.repository.SyncRepository
import com.baluhost.android.domain.usecase.activity.GetRecentFilesUseCase
import com.baluhost.android.domain.usecase.cache.GetCacheStatsUseCase
import com.baluhost.android.domain.usecase.files.GetFilesUseCase
import com.baluhost.android.domain.usecase.power.CheckNasStatusUseCase
import com.baluhost.android.domain.usecase.power.SendSoftSleepUseCase
import com.baluhost.android.domain.usecase.power.SendSuspendUseCase
import com.baluhost.android.domain.usecase.power.SendWolUseCase
import com.baluhost.android.domain.usecase.shares.GetShareStatisticsUseCase
import com.baluhost.android.domain.usecase.system.GetEnergyDashboardUseCase
import com.baluhost.android.domain.usecase.system.GetRaidStatusUseCase
import com.baluhost.android.domain.usecase.system.GetSmartStatusUseCase
import com.baluhost.android.domain.usecase.system.GetSystemTelemetryUseCase
import com.baluhost.android.util.NetworkStateManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelVpnActionTest {

    // Use UnconfinedTestDispatcher so viewModelScope coroutines dispatch eagerly.
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var networkStateManager: NetworkStateManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var notificationWebSocketManager: NotificationWebSocketManager

    // This flow feeds into networkStateManager.observeHomeNetworkStatus(),
    // which the ViewModel's observeHomeNetworkState() collects from.
    private val homeNetworkFlow = MutableSharedFlow<Boolean?>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        networkStateManager = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        notificationWebSocketManager = mockk(relaxed = true)

        every { networkStateManager.observeHomeNetworkStatus(any()) } returns homeNetworkFlow
        every { networkStateManager.isVpnActive() } returns false
        // Stub all PreferencesManager flows called during ViewModel init to avoid
        // NoSuchElementException when .first() is called on empty relaxed-mock flows.
        every { preferencesManager.getServerUrl() } returns flowOf("http://192.168.1.100:3000")
        every { preferencesManager.getUsername() } returns flowOf("testuser")
        every { preferencesManager.getUserRole() } returns flowOf("user")
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getVpnConfig() } returns flowOf(null)
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)
        every { notificationWebSocketManager.unreadCount } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DashboardViewModel {
        return DashboardViewModel(
            getFilesUseCase = mockk(relaxed = true),
            getRecentFilesUseCase = mockk(relaxed = true),
            getCacheStatsUseCase = mockk(relaxed = true),
            getSystemTelemetryUseCase = mockk(relaxed = true),
            getEnergyDashboardUseCase = mockk(relaxed = true),
            getRaidStatusUseCase = mockk(relaxed = true),
            getSmartStatusUseCase = mockk(relaxed = true),
            getShareStatisticsUseCase = mockk(relaxed = true),
            preferencesManager = preferencesManager,
            networkStateManager = networkStateManager,
            offlineQueueRepository = mockk(relaxed = true),
            syncRepository = mockk(relaxed = true),
            notificationWebSocketManager = notificationWebSocketManager,
            sendWolUseCase = mockk(relaxed = true),
            sendSoftSleepUseCase = mockk(relaxed = true),
            sendSuspendUseCase = mockk(relaxed = true),
            checkNasStatusUseCase = mockk(relaxed = true)
        )
    }

    /**
     * Cancel all viewModelScope coroutines (including the infinite polling loop)
     * so that runTest can complete cleanup without hanging.
     */
    private fun clearViewModel(vm: DashboardViewModel) {
        // ViewModel.clear() is package-private in Lifecycle 2.8, accessible via reflection.
        // It calls onCleared() and cancels viewModelScope.
        try {
            val clearMethod = ViewModel::class.java.getDeclaredMethod("clear")
            clearMethod.isAccessible = true
            clearMethod.invoke(vm)
        } catch (_: NoSuchMethodException) {
            // Fallback for older Lifecycle versions: call onCleared() directly
            val onClearedMethod = ViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(vm)
        }
    }

    @Test
    fun `emits ShowPrompt when external detected and auto-vpn off`() = runTest {
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)

        val vm = createViewModel()

        vm.vpnActionEvent.test {
            homeNetworkFlow.emit(false)

            assertEquals(DashboardViewModel.VpnAction.ShowPrompt, awaitItem())
        }

        clearViewModel(vm)
    }

    @Test
    fun `emits AutoConnect when external detected and auto-vpn on`() = runTest {
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(true)

        val vm = createViewModel()

        vm.vpnActionEvent.test {
            homeNetworkFlow.emit(false)

            assertEquals(DashboardViewModel.VpnAction.AutoConnect, awaitItem())
        }

        clearViewModel(vm)
    }

    @Test
    fun `does not emit VPN action when VPN already active`() = runTest {
        every { networkStateManager.isVpnActive() } returns true

        val vm = createViewModel()

        vm.vpnActionEvent.test {
            homeNetworkFlow.emit(false)

            expectNoEvents()
        }

        clearViewModel(vm)
    }

    @Test
    fun `ShowPrompt only emitted once per session`() = runTest {
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)

        val vm = createViewModel()

        vm.vpnActionEvent.test {
            homeNetworkFlow.emit(false)
            assertEquals(DashboardViewModel.VpnAction.ShowPrompt, awaitItem())

            // Simulate returning to home then going external again
            homeNetworkFlow.emit(true)
            homeNetworkFlow.emit(false)

            // Should not emit again -- vpnPromptShown guards it
            expectNoEvents()
        }

        clearViewModel(vm)
    }
}

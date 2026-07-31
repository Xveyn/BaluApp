package com.baluhost.android.presentation.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.notification.NotificationWebSocketManager
import com.baluhost.android.domain.model.DesktopActionResult
import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.domain.model.NasStatus
import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.domain.usecase.plugin.IsGamingModeAvailableUseCase
import com.baluhost.android.domain.usecase.plugin.StartGamingModeUseCase
import com.baluhost.android.domain.model.PowerPermissions
import com.baluhost.android.domain.usecase.power.CheckNasStatusUseCase
import com.baluhost.android.domain.usecase.power.DisableDesktopUseCase
import com.baluhost.android.domain.usecase.power.EnableDesktopUseCase
import com.baluhost.android.domain.usecase.power.GetDesktopStatusUseCase
import com.baluhost.android.domain.usecase.power.GetMyPowerPermissionsUseCase
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelDesktopActionTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var notificationWebSocketManager: NotificationWebSocketManager
    private lateinit var getDesktopStatusUseCase: GetDesktopStatusUseCase
    private lateinit var enableDesktopUseCase: EnableDesktopUseCase
    private lateinit var disableDesktopUseCase: DisableDesktopUseCase
    private lateinit var isGamingModeAvailableUseCase: IsGamingModeAvailableUseCase
    private lateinit var startGamingModeUseCase: StartGamingModeUseCase
    private lateinit var getMyPowerPermissionsUseCase: GetMyPowerPermissionsUseCase
    private lateinit var checkNasStatusUseCase: CheckNasStatusUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        preferencesManager = mockk(relaxed = true)
        notificationWebSocketManager = mockk(relaxed = true)
        getDesktopStatusUseCase = mockk()
        enableDesktopUseCase = mockk()
        disableDesktopUseCase = mockk()
        isGamingModeAvailableUseCase = mockk()
        startGamingModeUseCase = mockk()
        getMyPowerPermissionsUseCase = mockk()
        checkNasStatusUseCase = mockk()

        every { preferencesManager.getServerUrl() } returns flowOf("http://192.168.1.100:3000")
        every { preferencesManager.getUsername() } returns flowOf("testuser")
        every { preferencesManager.getUserRole() } returns flowOf("admin")
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getVpnConfig() } returns flowOf(null)
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)
        every { notificationWebSocketManager.unreadCount } returns MutableStateFlow(0)

        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.UNKNOWN)
        coEvery { isGamingModeAvailableUseCase() } returns false
        // loadPowerPermissions() runs in the ViewModel's init block, so every
        // stub it depends on has to be in place before createViewModel().
        coEvery { getMyPowerPermissionsUseCase() } returns Result.Success(PowerPermissions())
        // onPowerDialogOpened() (Fix 6) short-circuits unless nasStatus is
        // ONLINE. loadDashboardData() drives that during init via
        // updateNasStatus(); telemetry is not stubbed here (each test can
        // override it), so the fallback path through checkNasStatusUseCase()
        // is what actually sets it — resolve it to ONLINE so the desktop
        // dialog tests in this file keep working the way they did before
        // onPowerDialogOpened() started checking nasStatus.
        coEvery { checkNasStatusUseCase() } returns NasStatusResult.Resolved(NasStatus.ONLINE)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DashboardViewModel {
        val networkStateManager: com.baluhost.android.util.NetworkStateManager = mockk(relaxed = true)
        every { networkStateManager.observeHomeNetworkStatus(any()) } returns MutableSharedFlow()
        every { networkStateManager.isVpnActive() } returns false

        return DashboardViewModel(
            getFilesUseCase = mockk(relaxed = true),
            getRecentFilesUseCase = mockk(relaxed = true),
            getCacheStatsUseCase = mockk(relaxed = true),
            getSystemTelemetryUseCase = mockk(relaxed = true),
            getCurrentUptimeUseCase = mockk(relaxed = true),
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
            checkNasStatusUseCase = checkNasStatusUseCase,
            getMyPowerPermissionsUseCase = getMyPowerPermissionsUseCase,
            sendWakeUseCase = mockk(relaxed = true),
            getDesktopStatusUseCase = getDesktopStatusUseCase,
            enableDesktopUseCase = enableDesktopUseCase,
            disableDesktopUseCase = disableDesktopUseCase,
            isGamingModeAvailableUseCase = isGamingModeAvailableUseCase,
            startGamingModeUseCase = startGamingModeUseCase
        )
    }

    /** Cancels viewModelScope, including the endless polling loop, so runTest can finish. */
    private fun clearViewModel(vm: DashboardViewModel) {
        try {
            val clearMethod = ViewModel::class.java.getDeclaredMethod("clear")
            clearMethod.isAccessible = true
            clearMethod.invoke(vm)
        } catch (_: NoSuchMethodException) {
            val onClearedMethod = ViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(vm)
        }
    }

    @Test
    fun `onPowerDialogOpened takes the desktop state from the server`() = runTest {
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.STOPPED)
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        assertEquals(DesktopState.STOPPED, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `a failing desktop status resets the state to UNKNOWN`() = runTest {
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.STOPPED)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertEquals(DesktopState.STOPPED, vm.desktopState.value)

        // The server went away between two openings of the dialog. A stale
        // STOPPED would offer "enable" against a machine nobody can reach.
        coEvery { getDesktopStatusUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        vm.snackbarEvent.test {
            vm.onPowerDialogOpened()

            assertEquals(DesktopState.UNKNOWN, vm.desktopState.value)
            // A discovery call must stay silent — the entry just does not appear.
            expectNoEvents()
        }
        clearViewModel(vm)
    }

    @Test
    fun `onPowerDialogOpened skips the desktop status call when the server is not online`() = runTest {
        // The ONLINE branch of the power dialog is the only one that renders
        // desktop/gaming entries; against OFFLINE or SLEEPING the status call
        // could only time out, so it should not even be attempted.
        coEvery { checkNasStatusUseCase() } returns NasStatusResult.FritzBoxUnreachable
        val vm = createViewModel()
        assertEquals(NasStatus.OFFLINE, vm.nasStatus.value)

        vm.onPowerDialogOpened()

        coVerify(exactly = 0) { getDesktopStatusUseCase() }
        coVerify(exactly = 0) { isGamingModeAvailableUseCase() }
        clearViewModel(vm)
    }

    @Test
    fun `the admin fallback grants the desktop right when the permission call fails`() = runTest {
        coEvery { getMyPowerPermissionsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        val vm = createViewModel()

        assertTrue(vm.powerPermissions.value.canToggleDesktop)
        assertTrue(vm.powerPermissions.value.canUnlockSession)
        clearViewModel(vm)
    }

    @Test
    fun `a non-admin gets no permissions when the permission call fails`() = runTest {
        every { preferencesManager.getUserRole() } returns flowOf("user")
        coEvery { getMyPowerPermissionsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        val vm = createViewModel()

        assertFalse(vm.powerPermissions.value.hasAnyPermission)
        clearViewModel(vm)
    }

    @Test
    fun `onPowerDialogOpened does not ask about gaming mode for a non-admin`() = runTest {
        every { preferencesManager.getUserRole() } returns flowOf("user")
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        coVerify(exactly = 0) { isGamingModeAvailableUseCase() }
        assertFalse(vm.gamingModeAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `onPowerDialogOpened asks about gaming mode for an admin`() = runTest {
        coEvery { isGamingModeAvailableUseCase() } returns true
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        assertTrue(vm.gamingModeAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `enableDesktop with a refused unlock emits exactly one snackbar`() = runTest {
        coEvery { enableDesktopUseCase() } returns
            Result.Success(DesktopActionResult("displays on", sessionUnlocked = false))
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.enableDesktop()

            assertEquals("Displays an – Session ist noch gesperrt", awaitItem())
            expectNoEvents()
        }
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `enableDesktop with a granted unlock reports plain success`() = runTest {
        coEvery { enableDesktopUseCase() } returns
            Result.Success(DesktopActionResult("displays on", sessionUnlocked = true))
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.enableDesktop()

            assertEquals("Displays aktiviert", awaitItem())
        }
        clearViewModel(vm)
    }

    @Test
    fun `enableDesktop reports a failure and resets the state to UNKNOWN`() = runTest {
        // Seed a known, non-UNKNOWN state first — the same way
        // `a failing desktop status resets the state to UNKNOWN` does — so the
        // later assertion actually proves the action moved the state, rather
        // than merely matching the initial UNKNOWN default.
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.STOPPED)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertEquals(DesktopState.STOPPED, vm.desktopState.value)

        coEvery { enableDesktopUseCase() } returns Result.Error(Exception("Displays einschalten fehlgeschlagen: 403"))

        vm.snackbarEvent.test {
            vm.enableDesktop()

            assertEquals("Displays einschalten fehlgeschlagen: 403", awaitItem())
        }
        // A failed action means the app no longer knows the truth: UNKNOWN
        // hides the entry until the next refresh resolves it.
        assertEquals(DesktopState.UNKNOWN, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `enableDesktop with sessionUnlocked null reports plain success`() = runTest {
        coEvery { enableDesktopUseCase() } returns
            Result.Success(DesktopActionResult("displays on", sessionUnlocked = null))
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.enableDesktop()

            assertEquals("Displays aktiviert", awaitItem())
        }
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `disableDesktop reports success and flips the state to STOPPED`() = runTest {
        coEvery { disableDesktopUseCase() } returns Result.Success("displays off")
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.disableDesktop()

            assertEquals("Displays deaktiviert", awaitItem())
        }
        assertEquals(DesktopState.STOPPED, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `disableDesktop reports a failure and resets the state to UNKNOWN`() = runTest {
        // Same seed-then-fail model as the enableDesktop failure test above.
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.RUNNING)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)

        coEvery { disableDesktopUseCase() } returns Result.Error(Exception("Displays ausschalten fehlgeschlagen: 403"))

        vm.snackbarEvent.test {
            vm.disableDesktop()

            assertEquals("Displays ausschalten fehlgeschlagen: 403", awaitItem())
        }
        assertEquals(DesktopState.UNKNOWN, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `startGamingMode passes the plugin message on and turns the displays on`() = runTest {
        coEvery { startGamingModeUseCase() } returns Result.Success("Gaming-Modus gestartet")
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.startGamingMode()

            assertEquals("Gaming-Modus gestartet", awaitItem())
        }
        // The action turns the displays on before it launches Big Picture.
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `startGamingMode reports the plugin's own failure and resets the state to UNKNOWN`() = runTest {
        // The documented partial failure means displays are actually on even
        // though the call errored — a stale STOPPED here would tell the user
        // the opposite of reality. Seed STOPPED first, the same seed-then-fail
        // model as the enableDesktop/disableDesktop failure tests, so the
        // assertion proves the transition instead of matching the default.
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.STOPPED)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertEquals(DesktopState.STOPPED, vm.desktopState.value)

        coEvery { startGamingModeUseCase() } returns
            Result.Error(Exception("Displays sind an, aber Steam startete nicht"))

        vm.snackbarEvent.test {
            vm.startGamingMode()

            assertEquals("Displays sind an, aber Steam startete nicht", awaitItem())
        }
        assertEquals(DesktopState.UNKNOWN, vm.desktopState.value)
        clearViewModel(vm)
    }
}

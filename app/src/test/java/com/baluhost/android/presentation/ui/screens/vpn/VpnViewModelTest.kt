package com.baluhost.android.presentation.ui.screens.vpn

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.usecase.vpn.ConnectVpnUseCase
import com.baluhost.android.domain.usecase.vpn.DisconnectVpnUseCase
import com.baluhost.android.domain.usecase.vpn.FetchVpnConfigUseCase
import com.baluhost.android.util.Result
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class VpnViewModelTest {

    private lateinit var fetchVpnConfigUseCase: FetchVpnConfigUseCase
    private lateinit var connectVpnUseCase: ConnectVpnUseCase
    private lateinit var disconnectVpnUseCase: DisconnectVpnUseCase
    private lateinit var vpnRepository: com.baluhost.android.domain.repository.VpnRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var viewModel: VpnViewModel
    private lateinit var context: android.content.Context

    private val testDispatcher = StandardTestDispatcher()

    // VpnViewModel.init{} starts an infinite `while (isActive) { ...; delay(3000) }`
    // status-monitoring coroutine in viewModelScope. Because Dispatchers.Main is set to
    // testDispatcher below, runTest{} adopts the same virtual-time scheduler as that
    // coroutine. runTest{} advances its scheduler to idle when the test body finishes,
    // and since the monitoring loop always has more scheduled work, that advance never
    // terminates and exhausts the heap (regardless of configured heap size - this is
    // what showed up as OutOfMemoryError inside kotlin-reflect's ProtoBuf parsing: just
    // whatever large allocation happened to be in flight when the heap finally ran out).
    // Routing every constructed ViewModel through a ViewModelStore lets us cancel its
    // viewModelScope (via the public put()/clear() API, which invokes the package-private
    // ViewModel.clear()) before each test's runTest{} block ends.
    //
    // DashboardViewModel has the same shape (an infinite `while (true) { delay(30_000); ... }`
    // loop in viewModelScope), and DashboardViewModelVpnActionTest already solves this exact
    // hazard with its own clearViewModel() helper, which reaches ViewModel.clear() via
    // reflection. We use ViewModelStore here instead because put()/clear() reaches the same
    // method through public API, but it's the same underlying fix for the same problem.
    private val viewModelStore = ViewModelStore()

    private fun newViewModel(): VpnViewModel {
        val vm = VpnViewModel(fetchVpnConfigUseCase, connectVpnUseCase, disconnectVpnUseCase, vpnRepository, preferencesManager, context)
        viewModelStore.put("vpnViewModel", vm) // clears any previously stored VM's scope first
        return vm
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fetchVpnConfigUseCase = mockk()
        connectVpnUseCase = mockk()
        disconnectVpnUseCase = mockk()
        vpnRepository = mockk()
        preferencesManager = mockk()
        context = mockk(relaxed = true)
        val connectivityManager: android.net.ConnectivityManager = mockk(relaxed = true)
        every { context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns connectivityManager

        // Default: no VPN config
        every { preferencesManager.getVpnConfig() } returns flowOf(null)
        every { preferencesManager.getVpnType() } returns flowOf(null)
        every { preferencesManager.getVpnAssignedIp() } returns flowOf(null)
        every { preferencesManager.getVpnDeviceName() } returns flowOf(null)
        coEvery { fetchVpnConfigUseCase() } returns Result.Error(Exception("No config"))
        coEvery { vpnRepository.getAvailableVpnTypes() } returns Result.Success(emptyList())
        coEvery { vpnRepository.getCachedVpnConfig() } returns null

        viewModel = newViewModel()
    }

    @After
    fun teardown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `initial state should check for VPN config`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf("vpn_config_string")

        // When
        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.hasConfig)
            assertFalse(state.isConnected)
        }
        viewModelStore.clear()
    }

    @Test
    fun `initial state should show no config when missing`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf(null)

        // When
        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.hasConfig)
            // VpnViewModel.loadVpnConfig() reports the underlying failure reason rather
            // than a static "no config" message; "No config" is fetchVpnConfigUseCase's
            // stubbed exception message from setup().
            assertEquals("Konfiguration konnte nicht geladen werden: No config", state.error)
        }
        viewModelStore.clear()
    }

    @Test
    fun `connect should transition to connected state on success`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf("vpn_config")

        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        coEvery { connectVpnUseCase() } returns Result.Success(true)

        // When
        viewModel.uiState.test {
            skipItems(1) // Initial state

            viewModel.connect()
            testDispatcher.scheduler.runCurrent()

            // Then
            val loadingState = awaitItem()
            assertTrue(loadingState.isLoading)

            val connectedState = awaitItem()
            assertTrue(connectedState.isConnected)
            assertFalse(connectedState.isLoading)
            assertNull(connectedState.error)
        }
        viewModelStore.clear()
    }

    @Test
    fun `connect should show error on failure`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf("vpn_config")

        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        val errorMessage = "VPN connection failed"
        coEvery { connectVpnUseCase() } returns Result.Error(Exception(errorMessage))

        // When
        viewModel.uiState.test {
            skipItems(1)

            viewModel.connect()
            testDispatcher.scheduler.runCurrent()

            // Then
            skipItems(1) // Loading state

            val errorState = awaitItem()
            assertFalse(errorState.isConnected)
            assertFalse(errorState.isLoading)
            // VpnViewModel.connect() prefixes the use case's exception message with
            // "Verbindung fehlgeschlagen: " rather than surfacing it verbatim.
            assertEquals("Verbindung fehlgeschlagen: $errorMessage", errorState.error)
        }
        viewModelStore.clear()
    }

    @Test
    fun `disconnect should transition to disconnected state on success`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf("vpn_config")

        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        // Connect first
        coEvery { connectVpnUseCase() } returns Result.Success(true)
        viewModel.connect()
        testDispatcher.scheduler.runCurrent()

        // Setup disconnect
        coEvery { disconnectVpnUseCase() } returns Result.Success(true)

        // When
        viewModel.uiState.test {
            skipItems(1)

            viewModel.disconnect()
            testDispatcher.scheduler.runCurrent()

            // Then
            val loadingState = awaitItem()
            assertTrue(loadingState.isLoading)

            val disconnectedState = awaitItem()
            assertFalse(disconnectedState.isConnected)
            assertFalse(disconnectedState.isLoading)
        }
        viewModelStore.clear()
    }

    @Test
    fun `connect should do nothing when already connected`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf("vpn_config")

        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        coEvery { connectVpnUseCase() } returns Result.Success(true)

        // Connect first
        viewModel.connect()
        testDispatcher.scheduler.runCurrent()

        // When - Try to connect again
        viewModel.connect()
        testDispatcher.scheduler.runCurrent()

        // Then - Should only call once
        coVerify(exactly = 1) {
            connectVpnUseCase()
        }
        viewModelStore.clear()
    }

    @Test
    fun `disconnect should do nothing when already disconnected`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf("vpn_config")

        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        // When - Try to disconnect when not connected
        viewModel.disconnect()
        testDispatcher.scheduler.runCurrent()

        // Then
        coVerify(exactly = 0) {
            disconnectVpnUseCase()
        }
        viewModelStore.clear()
    }

    @Test
    fun `should not connect or disconnect while loading`() = runTest {
        // Given
        every { preferencesManager.getVpnConfig() } returns flowOf("vpn_config")

        viewModel = newViewModel()
        testDispatcher.scheduler.runCurrent()

        coEvery { connectVpnUseCase() } coAnswers {
            kotlinx.coroutines.delay(1000)
            Result.Success(true)
        }

        // When - first connect() launches and suspends mid-flight (isLoading becomes
        // true once its coroutine actually runs). Only after that state update has
        // been applied does the guard in connect() have anything to see, so the first
        // call needs its own pump before the "while loading" calls that follow -
        // StandardTestDispatcher never runs launched work eagerly the way the real
        // Dispatchers.Main.immediate does on Android's main thread.
        viewModel.connect()
        testDispatcher.scheduler.runCurrent()

        viewModel.connect()
        viewModel.connect()
        testDispatcher.scheduler.runCurrent()

        // Then - Should only call once
        coVerify(exactly = 1) {
            connectVpnUseCase()
        }
        viewModelStore.clear()
    }
}

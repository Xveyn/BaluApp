package com.baluhost.android.presentation.ui.screens.settings

import app.cash.turbine.test
import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.usecase.power.GetAlwaysAwakeUseCase
import com.baluhost.android.domain.usecase.power.SetAlwaysAwakeUseCase
import com.baluhost.android.util.Clock
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AlwaysAwakeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    /** Fixed so the boundary cases are exact rather than racing the wall clock. */
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private lateinit var getAlwaysAwake: GetAlwaysAwakeUseCase
    private lateinit var setAlwaysAwake: SetAlwaysAwakeUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        getAlwaysAwake = mockk()
        setAlwaysAwake = mockk()
        coEvery { getAlwaysAwake() } returns Result.Success(AlwaysAwake(enabled = false, until = null))
        coEvery { setAlwaysAwake(any(), any()) } returns
            Result.Success(AlwaysAwake(enabled = true, until = null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AlwaysAwakeViewModel(getAlwaysAwake, setAlwaysAwake, Clock { now })

    // ---- loading -----------------------------------------------------------

    @Test
    fun `loads the current override on creation`() = runTest {
        coEvery { getAlwaysAwake() } returns
            Result.Success(AlwaysAwake(enabled = true, until = now.plus(Duration.ofHours(2))))

        val state = viewModel().uiState.value

        assertTrue(state.enabled)
        assertEquals(now.plus(Duration.ofHours(2)), state.until)
        assertTrue(!state.isLoading)
    }

    @Test
    fun `a failed load is shown rather than defaulted away`() = runTest {
        coEvery { getAlwaysAwake() } returns Result.Error(Exception("Server nicht erreichbar"))

        val state = viewModel().uiState.value

        assertEquals("Server nicht erreichbar", state.loadError)
    }

    // ---- presets -----------------------------------------------------------

    @Test
    fun `a one hour preset expires one hour from now`() = runTest {
        val vm = viewModel()

        vm.setPreset(1L)

        coVerify { setAlwaysAwake(true, now.plus(Duration.ofHours(1))) }
    }

    @Test
    fun `an eight hour preset expires eight hours from now`() = runTest {
        val vm = viewModel()

        vm.setPreset(8L)

        coVerify { setAlwaysAwake(true, now.plus(Duration.ofHours(8))) }
    }

    @Test
    fun `the permanent preset sends no expiry`() = runTest {
        val vm = viewModel()

        vm.setPreset(null)

        coVerify { setAlwaysAwake(true, null) }
    }

    @Test
    fun `disabling sends enabled false`() = runTest {
        coEvery { setAlwaysAwake(false, null) } returns
            Result.Success(AlwaysAwake(enabled = false, until = null))
        val vm = viewModel()

        vm.disable()

        coVerify { setAlwaysAwake(false, null) }
    }

    // ---- bounds on a custom instant ----------------------------------------

    @Test
    fun `a custom instant in the past is refused without a request`() = runTest {
        val vm = viewModel()

        vm.snackbarEvent.test {
            vm.setCustom(now.minusSeconds(1))

            assertEquals("Der Zeitpunkt muss in der Zukunft liegen", awaitItem())
        }
        coVerify(exactly = 0) { setAlwaysAwake(any(), any()) }
    }

    @Test
    fun `a custom instant under five minutes away is refused`() = runTest {
        val vm = viewModel()

        vm.snackbarEvent.test {
            vm.setCustom(now.plus(Duration.ofMinutes(4)))

            assertEquals("Mindestens 5 Minuten in der Zukunft", awaitItem())
        }
        coVerify(exactly = 0) { setAlwaysAwake(any(), any()) }
    }

    @Test
    fun `exactly five minutes away is accepted`() = runTest {
        val vm = viewModel()
        val target = now.plus(Duration.ofMinutes(5))

        vm.setCustom(target)

        coVerify { setAlwaysAwake(true, target) }
    }

    @Test
    fun `a custom instant beyond seven days is refused`() = runTest {
        val vm = viewModel()

        vm.snackbarEvent.test {
            vm.setCustom(now.plus(Duration.ofDays(7)).plusSeconds(1))

            assertEquals("Höchstens 7 Tage im Voraus", awaitItem())
        }
        coVerify(exactly = 0) { setAlwaysAwake(any(), any()) }
    }

    @Test
    fun `exactly seven days away is accepted`() = runTest {
        val vm = viewModel()
        val target = now.plus(Duration.ofDays(7))

        vm.setCustom(target)

        coVerify { setAlwaysAwake(true, target) }
    }

    // ---- failure handling --------------------------------------------------

    @Test
    fun `a failed save restores the previous state`() = runTest {
        coEvery { getAlwaysAwake() } returns
            Result.Success(AlwaysAwake(enabled = true, until = null))
        coEvery { setAlwaysAwake(any(), any()) } returns
            Result.Error(Exception("Server nicht erreichbar"))
        val vm = viewModel()
        assertTrue(vm.uiState.value.enabled)

        vm.snackbarEvent.test {
            vm.disable()

            assertEquals("Server nicht erreichbar", awaitItem())
        }
        // The override is still on, because the server never accepted the change.
        assertTrue(vm.uiState.value.enabled)
        assertNull(vm.uiState.value.until)
        assertTrue(!vm.uiState.value.isSaving)
    }
}

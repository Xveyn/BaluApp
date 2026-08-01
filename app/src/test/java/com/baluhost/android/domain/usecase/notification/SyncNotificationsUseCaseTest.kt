package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncNotificationsUseCaseTest {

    private val repository: NotificationRepository = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    @Test
    fun `a successful reconcile is forwarded as success`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(3)
        coEvery { repository.sync(3) } returns kotlin.Result.success(Unit)

        val result = SyncNotificationsUseCase(repository, preferencesManager)()

        assertTrue(result is Result.Success)
    }

    @Test
    fun `a failing reconcile is forwarded as an error`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(3)
        coEvery { repository.sync(3) } returns kotlin.Result.failure(RuntimeException("unreachable"))

        val result = SyncNotificationsUseCase(repository, preferencesManager)()

        assertTrue(result is Result.Error)
    }

    @Test
    fun `without a signed-in account the sync fails cleanly instead of calling the repository`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(null)

        val result = SyncNotificationsUseCase(repository, preferencesManager)()

        assertTrue(result is Result.Error)
        coVerify(exactly = 0) { repository.sync(any()) }
    }

    @Test
    fun `a repository call that throws instead of returning a failure is still reported as an error`() = runTest {
        // repository.sync() wraps its own network call in try/catch, but a Room/DAO
        // exception further down (e.g. deleteAllById, upsertAll) is not caught there -
        // this use case's contract is to never let that escape as a thrown exception.
        every { preferencesManager.getUserId() } returns flowOf(3)
        coEvery { repository.sync(3) } throws IllegalStateException("database is closed")

        val result = SyncNotificationsUseCase(repository, preferencesManager)()

        assertTrue(result is Result.Error)
    }

    @Test
    fun `a preferences lookup that throws is reported as an error instead of propagating`() = runTest {
        every { preferencesManager.getUserId() } returns flow { throw RuntimeException("datastore unavailable") }

        val result = SyncNotificationsUseCase(repository, preferencesManager)()

        assertTrue(result is Result.Error)
        coVerify(exactly = 0) { repository.sync(any()) }
    }
}

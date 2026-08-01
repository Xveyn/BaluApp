package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
}

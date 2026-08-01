package com.baluhost.android.domain.usecase.notification

import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveUnreadCountUseCaseTest {

    private val repository: NotificationRepository = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    @Test
    fun `the count comes from the local table for the signed-in account`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(3)
        every { repository.observeUnreadCount(3) } returns flowOf(4)

        ObserveUnreadCountUseCase(repository, preferencesManager)().test {
            assertEquals(4, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `without a signed-in account the count is zero`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(null)

        ObserveUnreadCountUseCase(repository, preferencesManager)().test {
            assertEquals(0, awaitItem())
            awaitComplete()
        }
    }
}

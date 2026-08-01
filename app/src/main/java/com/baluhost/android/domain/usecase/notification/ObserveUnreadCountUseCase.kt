package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Unread count derived from the local table rather than an in-memory counter,
 * so it survives process death - including the increments that arrived by push
 * while the app was not running.
 */
class ObserveUnreadCountUseCase @Inject constructor(
    private val repository: NotificationRepository,
    private val preferencesManager: PreferencesManager
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<Int> =
        preferencesManager.getUserId().flatMapLatest { owner ->
            if (owner == null) flowOf(0) else repository.observeUnreadCount(owner)
        }
}

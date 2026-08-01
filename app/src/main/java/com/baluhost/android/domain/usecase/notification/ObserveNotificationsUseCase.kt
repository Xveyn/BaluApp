package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.repository.NotificationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Cache reads for the notification list, scoped to the signed-in account.
 * Mirrors [ObserveUnreadCountUseCase]: without a signed-in account there is
 * nothing to show, so the flow settles on an empty list instead of erroring.
 */
class ObserveNotificationsUseCase @Inject constructor(
    private val repository: NotificationRepository,
    private val preferencesManager: PreferencesManager
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(trashed: Boolean): Flow<List<AppNotification>> =
        preferencesManager.getUserId().flatMapLatest { owner ->
            if (owner == null) flowOf(emptyList()) else repository.observeNotifications(owner, trashed)
        }
}

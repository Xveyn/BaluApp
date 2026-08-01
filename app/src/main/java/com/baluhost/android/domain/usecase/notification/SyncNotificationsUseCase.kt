package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Reconciles the local notification cache with the server. This is the one
 * operation every sync trigger (screen open, app start, connectivity regained,
 * after a push, or a user action pushing an intent) funnels through.
 */
class SyncNotificationsUseCase @Inject constructor(
    private val repository: NotificationRepository,
    private val preferencesManager: PreferencesManager
) {
    suspend operator fun invoke(): Result<Unit> {
        val ownerUserId = preferencesManager.getUserId().first()
            ?: return Result.Error(Exception("Kein Konto angemeldet"))

        return repository.sync(ownerUserId).fold(
            onSuccess = { Result.Success(Unit) },
            onFailure = { Result.Error(it as? Exception ?: Exception(it)) }
        )
    }
}

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
    /**
     * Never throws: this is called from places that cannot meaningfully react to an
     * exception (a background app-start coroutine, a best-effort push after a local
     * write) and the contract every caller relies on is "reports failure, never
     * propagates it". Both the preferences lookup and the repository call are inside
     * the guard - not just the network part `repository.sync` already wraps itself -
     * because a local failure (e.g. DataStore or Room throwing) must be caught here too.
     */
    suspend operator fun invoke(): Result<Unit> {
        return try {
            val ownerUserId = preferencesManager.getUserId().first()
                ?: return Result.Error(Exception("Kein Konto angemeldet"))

            repository.sync(ownerUserId).fold(
                onSuccess = { Result.Success(Unit) },
                onFailure = { Result.Error(it as? Exception ?: Exception(it)) }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}

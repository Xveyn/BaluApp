package com.baluhost.android.domain.usecase.activity

import com.baluhost.android.domain.model.RecentFile
import com.baluhost.android.domain.repository.ActivityRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

/**
 * Use case for getting recently accessed files from the activity tracking system.
 */
class GetRecentFilesUseCase @Inject constructor(
    private val activityRepository: ActivityRepository
) {

    suspend operator fun invoke(limit: Int = 10): Result<List<RecentFile>> {
        return activityRepository.getRecentFiles(limit)
    }
}

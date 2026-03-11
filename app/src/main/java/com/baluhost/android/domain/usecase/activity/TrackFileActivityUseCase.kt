package com.baluhost.android.domain.usecase.activity

import com.baluhost.android.domain.model.FileAction
import com.baluhost.android.domain.repository.ActivityRepository
import javax.inject.Inject

/**
 * Use case for tracking file activities from the client side.
 * Called when user opens, downloads, or interacts with a file.
 */
class TrackFileActivityUseCase @Inject constructor(
    private val activityRepository: ActivityRepository
) {

    suspend operator fun invoke(
        action: FileAction,
        filePath: String,
        fileName: String,
        isDirectory: Boolean = false,
        fileSize: Long? = null,
        mimeType: String? = null
    ) {
        activityRepository.trackActivity(
            actionType = action.apiValue,
            filePath = filePath,
            fileName = fileName,
            isDirectory = isDirectory,
            fileSize = fileSize,
            mimeType = mimeType
        )
    }
}

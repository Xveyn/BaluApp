package com.baluhost.android.domain.model

data class ShareStatistics(
    val totalShares: Int,
    val activeShares: Int,
    val sharedWithMe: Int
)

data class FileShareInfo(
    val id: Int,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val isDirectory: Boolean,
    val sharedWithUsername: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canDelete: Boolean,
    val canShare: Boolean,
    val createdAt: String,
    val isExpired: Boolean
)

data class SharedWithMeInfo(
    val shareId: Int,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val isDirectory: Boolean,
    val ownerUsername: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canDelete: Boolean,
    val canShare: Boolean,
    val sharedAt: String,
    val isExpired: Boolean
)

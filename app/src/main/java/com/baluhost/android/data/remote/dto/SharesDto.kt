package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ShareStatisticsDto(
    @SerializedName("total_file_shares") val totalFileShares: Int,
    @SerializedName("active_file_shares") val activeFileShares: Int,
    @SerializedName("files_shared_with_me") val filesSharedWithMe: Int
)

data class FileShareResponseDto(
    val id: Int,
    @SerializedName("file_id") val fileId: Int,
    @SerializedName("owner_id") val ownerId: Int,
    @SerializedName("shared_with_user_id") val sharedWithUserId: Int,
    @SerializedName("can_read") val canRead: Boolean,
    @SerializedName("can_write") val canWrite: Boolean,
    @SerializedName("can_delete") val canDelete: Boolean,
    @SerializedName("can_share") val canShare: Boolean,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("is_expired") val isExpired: Boolean,
    @SerializedName("is_accessible") val isAccessible: Boolean,
    @SerializedName("owner_username") val ownerUsername: String,
    @SerializedName("shared_with_username") val sharedWithUsername: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("is_directory") val isDirectory: Boolean
)

data class SharedWithMeResponseDto(
    @SerializedName("share_id") val shareId: Int,
    @SerializedName("file_id") val fileId: Int,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("is_directory") val isDirectory: Boolean,
    @SerializedName("owner_username") val ownerUsername: String,
    @SerializedName("owner_id") val ownerId: Int,
    @SerializedName("can_read") val canRead: Boolean,
    @SerializedName("can_write") val canWrite: Boolean,
    @SerializedName("can_delete") val canDelete: Boolean,
    @SerializedName("can_share") val canShare: Boolean,
    @SerializedName("shared_at") val sharedAt: String,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("is_expired") val isExpired: Boolean
)

data class ShareableUserDto(
    val id: Int,
    val username: String
)

data class FileShareCreateDto(
    @SerializedName("file_id") val fileId: Int,
    @SerializedName("shared_with_user_id") val sharedWithUserId: Int,
    @SerializedName("can_read") val canRead: Boolean = true,
    @SerializedName("can_write") val canWrite: Boolean = false,
    @SerializedName("can_delete") val canDelete: Boolean = false,
    @SerializedName("can_share") val canShare: Boolean = false,
    @SerializedName("expires_at") val expiresAt: String? = null
)

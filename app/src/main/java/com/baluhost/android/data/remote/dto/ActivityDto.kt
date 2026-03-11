package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTOs for File Activity Tracking API.
 */

// --- Response DTOs ---

data class RecentFilesResponse(
    @SerializedName("files") val files: List<RecentFileDto>
)

data class RecentFileDto(
    @SerializedName("file_path") val filePath: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("is_directory") val isDirectory: Boolean,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("last_action") val lastAction: String,
    @SerializedName("last_action_at") val lastActionAt: String,
    @SerializedName("action_count") val actionCount: Int
)

data class ActivityFeedResponse(
    @SerializedName("activities") val activities: List<ActivityEntryDto>,
    @SerializedName("total") val total: Int,
    @SerializedName("has_more") val hasMore: Boolean
)

data class ActivityEntryDto(
    @SerializedName("id") val id: String,
    @SerializedName("action_type") val actionType: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("is_directory") val isDirectory: Boolean,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("source") val source: String,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("metadata") val metadata: Map<String, String>?,
    @SerializedName("created_at") val createdAt: String
)

// --- Request DTOs ---

data class ReportActivitiesRequest(
    @SerializedName("activities") val activities: List<ReportActivityDto>
)

data class ReportActivityDto(
    @SerializedName("action_type") val actionType: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("is_directory") val isDirectory: Boolean,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("occurred_at") val occurredAt: String
)

data class ReportActivitiesResponse(
    @SerializedName("accepted") val accepted: Int,
    @SerializedName("deduplicated") val deduplicated: Int,
    @SerializedName("rejected") val rejected: Int
)

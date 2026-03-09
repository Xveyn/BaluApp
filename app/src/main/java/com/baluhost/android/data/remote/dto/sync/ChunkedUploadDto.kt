package com.baluhost.android.data.remote.dto.sync

import com.google.gson.annotations.SerializedName

/**
 * DTO for initiating a chunked upload.
 * Maps to backend ChunkedInitRequest.
 */
data class InitiateUploadDto(
    @SerializedName("filename")
    val filename: String,
    @SerializedName("total_size")
    val totalSize: Long,
    @SerializedName("target_path")
    val targetPath: String = ""
)

/**
 * Response from initiating a chunked upload.
 * Maps to backend ChunkedInitResponse.
 */
data class InitiateUploadResponseDto(
    @SerializedName("upload_id")
    val uploadId: String,
    @SerializedName("chunk_size")
    val chunkSize: Int
)

/**
 * Response from uploading a chunk.
 * Maps to backend ChunkedChunkResponse.
 */
data class ChunkUploadResponseDto(
    @SerializedName("received_bytes")
    val receivedBytes: Int
)

/**
 * DTO for remote file information.
 */
data class RemoteFileDto(
    @SerializedName("relative_path")
    val relativePath: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("size")
    val size: Long,
    @SerializedName("hash")
    val hash: String,
    @SerializedName("modified_at")
    val modifiedAt: String
)

/**
 * Response containing list of remote files.
 */
data class RemoteFileListResponseDto(
    @SerializedName("files")
    val files: List<RemoteFileDto>
)

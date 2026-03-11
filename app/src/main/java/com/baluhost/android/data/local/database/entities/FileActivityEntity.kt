package com.baluhost.android.data.local.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Entity for buffering file activity events locally.
 * Events are stored here when offline and synced to the server when connectivity returns.
 */
@Entity(tableName = "file_activities")
data class FileActivityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "action_type")
    val actionType: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean = false,

    @ColumnInfo(name = "file_size")
    val fileSize: Long? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant = Instant.now(),

    @ColumnInfo(name = "synced")
    val synced: Boolean = false
)

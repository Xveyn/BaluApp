package com.baluhost.android.domain.model

/**
 * Domain model for a recently accessed file from the activity tracking system.
 */
data class RecentFile(
    val filePath: String,
    val fileName: String,
    val isDirectory: Boolean,
    val fileSize: Long?,
    val mimeType: String?,
    val lastAction: FileAction,
    val lastActionAt: java.time.Instant,
    val actionCount: Int
) {
    val extension: String
        get() = if (isDirectory) "" else fileName.substringAfterLast('.', "")

    val displaySize: String
        get() = if (fileSize != null) com.baluhost.android.util.ByteFormatter.format(fileSize) else ""
}

/**
 * Supported file activity actions.
 */
enum class FileAction(val apiValue: String, val displayLabel: String) {
    OPEN("file.open", "Geöffnet"),
    DOWNLOAD("file.download", "Heruntergeladen"),
    UPLOAD("file.upload", "Hochgeladen"),
    EDIT("file.edit", "Bearbeitet"),
    DELETE("file.delete", "Gelöscht"),
    MOVE("file.move", "Verschoben"),
    RENAME("file.rename", "Umbenannt"),
    SHARE("file.share", "Geteilt"),
    PERMISSION("file.permission", "Berechtigung geändert"),
    FOLDER_CREATE("folder.create", "Erstellt"),
    UNKNOWN("unknown", "Aktivität");

    companion object {
        fun fromApi(value: String): FileAction {
            return entries.find { it.apiValue == value } ?: UNKNOWN
        }
    }
}

package com.baluhost.android.domain.model

import com.baluhost.android.util.ByteFormatter
import java.time.Instant

/**
 * Domain model for File/Folder.
 */
data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val modifiedAt: Instant,
    val owner: String? = null,
    val permissions: String? = null,
    val mimeType: String? = null
) {
    val isFile: Boolean
        get() = !isDirectory
    
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")
    
    val displaySize: String
        get() = formatFileSize(size)
    
    private fun formatFileSize(bytes: Long): String = ByteFormatter.format(bytes)
}

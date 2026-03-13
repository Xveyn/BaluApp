package com.baluhost.android.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Utility for scanning local folders using Storage Access Framework.
 * Uses fast ContentResolver queries instead of slow DocumentFile.listFiles().
 */
class LocalFolderScanner(private val context: Context) {

    data class ScanResult(
        val totalFiles: Int,
        val totalSize: Long,
        val fileList: List<FileInfo>,
        val errors: List<String>
    )

    data class FileInfo(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val mimeType: String?,
        val isDirectory: Boolean,
        val relativePath: String,
        val hash: String = ""
    )

    /**
     * Scan a folder URI using fast ContentResolver queries.
     */
    suspend fun scanFolder(
        folderUri: Uri,
        recursive: Boolean = true,
        excludePatterns: List<String> = emptyList()
    ): ScanResult = withContext(Dispatchers.IO) {
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val files = mutableListOf<FileInfo>()
        val errors = mutableListOf<String>()
        var totalSize = 0L

        try {
            scanFast(
                treeUri = folderUri,
                parentDocId = treeDocId,
                basePath = "",
                files = files,
                errors = errors,
                recursive = recursive,
                excludePatterns = excludePatterns
            )
            totalSize = files.sumOf { it.size }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add("Scan error: ${e.message}")
        }

        ScanResult(
            totalFiles = files.size,
            totalSize = totalSize,
            fileList = files,
            errors = errors
        )
    }

    /**
     * Fast recursive scan using ContentResolver.query() directly.
     * Orders of magnitude faster than DocumentFile.listFiles().
     */
    private suspend fun scanFast(
        treeUri: Uri,
        parentDocId: String,
        basePath: String,
        files: MutableList<FileInfo>,
        errors: MutableList<String>,
        recursive: Boolean,
        excludePatterns: List<String>
    ) {
        coroutineContext.ensureActive()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            cursor ?: return

            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()

                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val size = cursor.getLong(sizeIdx)
                val lastModified = cursor.getLong(modIdx)
                val mimeType = cursor.getString(mimeIdx)
                val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                // Skip hidden files
                if (name.startsWith(".")) continue

                // Check exclude patterns
                if (excludePatterns.any { name.contains(it, ignoreCase = true) }) continue

                val relativePath = if (basePath.isEmpty()) name else "$basePath/$name"

                if (isDir) {
                    if (recursive) {
                        scanFast(treeUri, docId, relativePath, files, errors, true, excludePatterns)
                    }
                } else {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    files.add(
                        FileInfo(
                            uri = fileUri,
                            name = name,
                            size = size,
                            lastModified = lastModified,
                            mimeType = mimeType,
                            isDirectory = false,
                            relativePath = relativePath
                        )
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add("Error scanning $basePath: ${e.message}")
        } finally {
            cursor?.close()
        }
    }

    /**
     * Calculate SHA256 hash of a file for change detection.
     */
    suspend fun calculateFileHash(fileUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }

                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun calculateFileHash(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun isFolderAccessible(folderUri: Uri): Boolean {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            documentFile?.exists() == true && documentFile.isDirectory
        } catch (e: Exception) {
            false
        }
    }

    fun getFolderDisplayName(folderUri: Uri): String? {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            documentFile?.name
        } catch (e: Exception) {
            null
        }
    }

    fun formatBytes(bytes: Long): String = ByteFormatter.format(bytes)
}

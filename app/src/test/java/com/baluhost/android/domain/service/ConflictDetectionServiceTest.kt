package com.baluhost.android.domain.service

import com.baluhost.android.domain.model.sync.ConflictResolution
import com.baluhost.android.domain.model.sync.FileConflict
import com.baluhost.android.domain.model.sync.RemoteFileInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConflictDetectionServiceTest {

    private lateinit var service: ConflictDetectionService

    @Before
    fun setup() {
        service = ConflictDetectionService()
    }

    @Test
    fun `identical files produce NO_ACTION`() {
        val local = listOf(
            ConflictDetectionService.LocalFileInfo("file.txt", "abc123", 100, 1000L)
        )
        val remote = listOf(
            RemoteFileInfo("file.txt", "file.txt", 100, "abc123", 1000L)
        )

        val result = service.analyzeConflicts(local, remote, lastSyncTime = 500L)

        assertEquals(1, result.noAction.size)
        assertEquals(0, result.toUpload.size)
        assertEquals(0, result.toDownload.size)
        assertEquals(0, result.conflicts.size)
    }

    @Test
    fun `local-only file produces UPLOAD`() {
        val local = listOf(
            ConflictDetectionService.LocalFileInfo("new.txt", "hash1", 50, 2000L)
        )

        val result = service.analyzeConflicts(local, emptyList(), lastSyncTime = 1000L)

        assertEquals(1, result.toUpload.size)
        assertEquals("new.txt", result.toUpload[0].relativePath)
    }

    @Test
    fun `remote-only file produces DOWNLOAD`() {
        val remote = listOf(
            RemoteFileInfo("remote.txt", "remote.txt", 75, "hash2", 2000L)
        )

        val result = service.analyzeConflicts(emptyList(), remote, lastSyncTime = 1000L)

        assertEquals(1, result.toDownload.size)
        assertEquals("remote.txt", result.toDownload[0].relativePath)
    }

    @Test
    fun `both modified after sync produces CONFLICT`() {
        val syncTime = 1000L
        val local = listOf(
            ConflictDetectionService.LocalFileInfo("doc.txt", "localHash", 100, 2000L)
        )
        val remote = listOf(
            RemoteFileInfo("doc.txt", "doc.txt", 110, "remoteHash", 1500L)
        )

        val result = service.analyzeConflicts(local, remote, lastSyncTime = syncTime)

        assertEquals(1, result.conflicts.size)
        assertEquals("doc.txt", result.conflicts[0].relativePath)
    }

    @Test
    fun `only local modified produces UPLOAD`() {
        val syncTime = 1000L
        val local = listOf(
            ConflictDetectionService.LocalFileInfo("doc.txt", "newHash", 100, 2000L)
        )
        val remote = listOf(
            RemoteFileInfo("doc.txt", "doc.txt", 100, "oldHash", 500L)
        )

        val result = service.analyzeConflicts(local, remote, lastSyncTime = syncTime)

        assertEquals(1, result.toUpload.size)
    }

    @Test
    fun `only remote modified produces DOWNLOAD`() {
        val syncTime = 1000L
        val local = listOf(
            ConflictDetectionService.LocalFileInfo("doc.txt", "oldHash", 100, 500L)
        )
        val remote = listOf(
            RemoteFileInfo("doc.txt", "doc.txt", 110, "newHash", 2000L)
        )

        val result = service.analyzeConflicts(local, remote, lastSyncTime = syncTime)

        assertEquals(1, result.toDownload.size)
    }

    @Test
    fun `resolveConflict KEEP_LOCAL returns UPLOAD`() {
        val conflict = FileConflict(
            id = "1", relativePath = "f.txt", fileName = "f.txt",
            localSize = 100, remoteSize = 200,
            localModifiedAt = 2000, remoteModifiedAt = 1000,
            detectedAt = 3000
        )

        val action = service.resolveConflict(conflict, ConflictResolution.KEEP_LOCAL)
        assertEquals(ConflictDetectionService.SyncAction.UPLOAD, action)
    }

    @Test
    fun `resolveConflict KEEP_SERVER returns DOWNLOAD`() {
        val conflict = FileConflict(
            id = "1", relativePath = "f.txt", fileName = "f.txt",
            localSize = 100, remoteSize = 200,
            localModifiedAt = 2000, remoteModifiedAt = 1000,
            detectedAt = 3000
        )

        val action = service.resolveConflict(conflict, ConflictResolution.KEEP_SERVER)
        assertEquals(ConflictDetectionService.SyncAction.DOWNLOAD, action)
    }

    @Test
    fun `resolveConflict KEEP_NEWEST picks newer file`() {
        val localNewer = FileConflict(
            id = "1", relativePath = "f.txt", fileName = "f.txt",
            localSize = 100, remoteSize = 200,
            localModifiedAt = 2000, remoteModifiedAt = 1000,
            detectedAt = 3000
        )
        assertEquals(
            ConflictDetectionService.SyncAction.UPLOAD,
            service.resolveConflict(localNewer, ConflictResolution.KEEP_NEWEST)
        )

        val remoteNewer = localNewer.copy(localModifiedAt = 500, remoteModifiedAt = 2000)
        assertEquals(
            ConflictDetectionService.SyncAction.DOWNLOAD,
            service.resolveConflict(remoteNewer, ConflictResolution.KEEP_NEWEST)
        )
    }

    @Test
    fun `summary counts are correct`() {
        val local = listOf(
            ConflictDetectionService.LocalFileInfo("same.txt", "h1", 10, 100),
            ConflictDetectionService.LocalFileInfo("upload.txt", "h2", 20, 2000),
            ConflictDetectionService.LocalFileInfo("conflict.txt", "h3", 30, 2000)
        )
        val remote = listOf(
            RemoteFileInfo("same.txt", "same.txt", 10, "h1", 100),
            RemoteFileInfo("download.txt", "download.txt", 40, "h4", 2000),
            RemoteFileInfo("conflict.txt", "conflict.txt", 35, "h5", 1500)
        )

        val result = service.analyzeConflicts(local, remote, lastSyncTime = 1000)

        assertEquals(4, result.summary.totalFiles)
        assertEquals(1, result.summary.noActionNeeded)
        assertEquals(1, result.summary.uploadsNeeded)
        assertEquals(1, result.summary.downloadsNeeded)
        assertEquals(1, result.summary.conflictsFound)
    }
}

package com.baluhost.android.presentation.ui.screens.files

import app.cash.turbine.test
import com.baluhost.android.domain.model.FileItem
import com.baluhost.android.domain.usecase.files.DeleteFileUseCase
import com.baluhost.android.domain.usecase.files.DownloadFileUseCase
import com.baluhost.android.domain.usecase.files.GetFilesUseCase
import com.baluhost.android.domain.usecase.files.UploadFileUseCase
import com.baluhost.android.util.Result
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    
    private lateinit var getFilesUseCase: GetFilesUseCase
    private lateinit var uploadFileUseCase: UploadFileUseCase
    private lateinit var downloadFileUseCase: DownloadFileUseCase
    private lateinit var deleteFileUseCase: DeleteFileUseCase
    private lateinit var moveFileUseCase: com.baluhost.android.domain.usecase.files.MoveFileUseCase
    private lateinit var preferencesManager: com.baluhost.android.data.local.datastore.PreferencesManager
    private lateinit var networkMonitor: com.baluhost.android.data.network.NetworkMonitor
    private lateinit var serverConnectivityChecker: com.baluhost.android.data.network.ServerConnectivityChecker
    private lateinit var offlineQueueManager: com.baluhost.android.domain.usecase.OfflineQueueManager
    private lateinit var networkStateManager: com.baluhost.android.util.NetworkStateManager
    private lateinit var viewModel: FilesViewModel
    
    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        getFilesUseCase = mockk()
        uploadFileUseCase = mockk()
        downloadFileUseCase = mockk()
        deleteFileUseCase = mockk()
        moveFileUseCase = mockk()
        preferencesManager = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        serverConnectivityChecker = mockk(relaxed = true)
        offlineQueueManager = mockk(relaxed = true)
        networkStateManager = mockk(relaxed = true)

        // FilesViewModel.init calls .first() on these two (FilesViewModel.kt:190-191).
        // A relaxed mock answers a Flow-returning call with an EMPTY flow, and
        // .first() on an empty flow throws NoSuchElementException before the test
        // body ever runs. Same reason DashboardViewModelVpnActionTest stubs its
        // PreferencesManager flows explicitly.
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getAccessToken() } returns flowOf("token")
        // Collected rather than .first()-ed, so an empty flow would not throw —
        // stubbed anyway so the ViewModel sees a realistic state.
        every { preferencesManager.getServerUrl() } returns flowOf("http://192.168.1.100:3000")
        every { preferencesManager.getVpnConfig() } returns flowOf(null)

        // Default mock for initial load
        coEvery { getFilesUseCase(any(), any()) } returns Result.Success(emptyList())

        viewModel = FilesViewModel(
            getFilesUseCase,
            uploadFileUseCase,
            downloadFileUseCase,
            deleteFileUseCase,
            moveFileUseCase,
            mockk(relaxed = true),
            preferencesManager,
            networkMonitor,
            serverConnectivityChecker,
            offlineQueueManager,
            networkStateManager
        )
    }
    
    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }
    
    @Test
    fun `initial state should load files at root`() = runTest {
        // Given
        val rootFiles = listOf(
            FileItem("Documents", "Documents", 0, true, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user"),
            FileItem("Pictures", "Pictures", 0, true, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user")
        )
        
        coEvery { getFilesUseCase("") } returns Result.Success(rootFiles)
        
        // When
        viewModel = FilesViewModel(
            getFilesUseCase,
            uploadFileUseCase,
            downloadFileUseCase,
            deleteFileUseCase,
            moveFileUseCase,
            mockk(relaxed = true),
            preferencesManager,
            networkMonitor,
            serverConnectivityChecker,
            offlineQueueManager,
            networkStateManager
        )
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(2, state.files.size)
            assertEquals("", state.currentPath)
            assertFalse(state.isLoading)
        }
    }
    
    @Test
    fun `loadFiles should update state with file list`() = runTest {
        // Given
        val files = listOf(
            FileItem("file1.txt", "documents/file1.txt", 1024, false, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user")
        )

        coEvery { getFilesUseCase("documents") } returns Result.Success(files)

        // When
        viewModel.uiState.test {
            skipItems(1) // Initial state

            viewModel.loadFiles("documents")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then - ViewModel.init kicks off its own root loadFiles("") concurrently
            // with the explicit call above, so an unpredictable number of loading /
            // settled states for path "" can arrive first. Drain until we reach the
            // settled state for the path this test actually triggered, rather than
            // assuming a fixed number of emissions.
            var state = awaitItem()
            while (state.isLoading || state.currentPath != "documents") {
                state = awaitItem()
            }
            assertEquals(1, state.files.size)
            assertEquals("documents", state.currentPath)
            assertFalse(state.isLoading)
        }
    }
    
    @Test
    fun `navigateToFolder should update path and load files`() = runTest {
        // Given
        val folderFiles = listOf(
            FileItem("photo.jpg", "Pictures/photo.jpg", 2048, false, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user")
        )
        
        coEvery { getFilesUseCase("Pictures") } returns Result.Success(folderFiles)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        // When
        viewModel.uiState.test {
            skipItems(1)
            
            viewModel.navigateToFolder("Pictures")
            testDispatcher.scheduler.advanceUntilIdle()
            
            // Then
            skipItems(1) // Loading state
            
            val state = awaitItem()
            assertEquals("Pictures", state.currentPath)
            assertEquals(1, state.files.size)
        }
    }
    
    @Test
    fun `navigateBack should return to previous path`() = runTest {
        // Given
        val rootFiles = listOf(
            FileItem("Documents", "Documents", 0, true, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user")
        )

        val subFiles = listOf(
            FileItem("file.txt", "Documents/file.txt", 100, false, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user")
        )
        
        coEvery { getFilesUseCase("") } returns Result.Success(rootFiles)
        coEvery { getFilesUseCase("Documents") } returns Result.Success(subFiles)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        // When - Navigate into folder then back
        viewModel.navigateToFolder("Documents")
        testDispatcher.scheduler.advanceUntilIdle()
        
        val canGoBack = viewModel.navigateBack()
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then
        assertTrue(canGoBack)
        
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.currentPath)
        }
    }
    
    @Test
    fun `navigateBack should return false at root`() = runTest {
        // Given
        testDispatcher.scheduler.advanceUntilIdle()
        
        // When
        val canGoBack = viewModel.navigateBack()
        
        // Then
        assertFalse(canGoBack)
    }
    
    @Test
    fun `uploadFile should track progress and refresh on success`() = runTest {
        // Given
        val file = mockk<File>(relaxed = true)
        every { file.name } returns "test.txt"
        
        val refreshedFiles = listOf(
            FileItem("test.txt", "test.txt", 100, false, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user")
        )

        coEvery {
            uploadFileUseCase(any(), any())
        } returns Result.Success(refreshedFiles[0])

        coEvery { getFilesUseCase("", false) } returns Result.Success(refreshedFiles)

        // uploadFile() only reaches uploadFileUseCase when the ViewModel considers
        // itself online; the mock defaults to offline (relaxed Boolean = false),
        // which silently routes the call into the offline-queue branch instead of
        // the success path this test exercises.
        every { networkMonitor.isCurrentlyOnline() } returns true

        testDispatcher.scheduler.advanceUntilIdle()

        // When
        viewModel.uiState.test {
            skipItems(1)

            viewModel.uploadFile(file)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            val uploadingState = awaitItem()
            assertTrue(uploadingState.isUploading)

            val completedState = awaitItem()
            assertFalse(completedState.isUploading)

            // Should refresh files - drain any number of loading transitions
            // instead of assuming there is exactly one.
            var refreshedState = awaitItem()
            while (refreshedState.isLoading) {
                refreshedState = awaitItem()
            }
            assertEquals(1, refreshedState.files.size)
        }
    }
    
    @Test
    fun `deleteFile should refresh list on success`() = runTest {
        // Given
        val filePath = "documents/file.txt"
        
        coEvery { deleteFileUseCase(filePath) } returns Result.Success(true)
        coEvery { getFilesUseCase("documents", false) } returns Result.Success(emptyList())
        
        // Set current path first
        coEvery { getFilesUseCase("documents", false) } returns Result.Success(
            listOf(FileItem("file.txt", filePath, 100, false, java.time.Instant.ofEpochSecond(System.currentTimeMillis() / 1000), "user"))
        )
        
        viewModel.loadFiles("documents")
        testDispatcher.scheduler.advanceUntilIdle()

        // When
        coEvery { getFilesUseCase("documents") } returns Result.Success(emptyList())

        // deleteFile() only reaches deleteFileUseCase when the ViewModel considers
        // itself online; the mock defaults to offline (relaxed Boolean = false),
        // which silently routes the call into the offline-queue branch instead of
        // the success path this test exercises - the ViewModel never emits a
        // second (loading) state in that branch, which is why the old fixed
        // skipItems(1) here waited for an emission that never arrived.
        every { networkMonitor.isCurrentlyOnline() } returns true

        viewModel.uiState.test {
            skipItems(1)

            viewModel.deleteFile(filePath)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then - Should refresh and show empty list. Drain any number of
            // loading transitions instead of assuming there is exactly one.
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertTrue(state.files.isEmpty())
        }
    }
    
    @Test
    fun `loadFiles should set error state on failure`() = runTest {
        // Given
        val errorMessage = "Network error"
        
        coEvery { getFilesUseCase("documents") } returns Result.Error(Exception(errorMessage))
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        // When
        viewModel.uiState.test {
            skipItems(1)
            
            viewModel.loadFiles("documents")
            testDispatcher.scheduler.advanceUntilIdle()
            
            // Then
            skipItems(1) // Loading state

            val errorState = awaitItem()
            assertFalse(errorState.isLoading)
            // The ViewModel does not pass the cause's text through — it reports
            // its own user-facing wording, so the stub's message is only an input.
            assertEquals("Keine Verbindung zum Server", errorState.error)
        }
    }
    
    @Test
    fun `clearError should remove error message`() = runTest {
        // Given - Set error first
        coEvery { getFilesUseCase("documents") } returns Result.Error(Exception("Error"))
        
        viewModel.loadFiles("documents")
        testDispatcher.scheduler.advanceUntilIdle()
        
        // When
        viewModel.uiState.test {
            skipItems(1)
            
            viewModel.clearError()
            
            // Then
            val state = awaitItem()
            assertNull(state.error)
        }
    }
}

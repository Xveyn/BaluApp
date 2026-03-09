package com.baluhost.android.presentation.ui.screens.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baluhost.android.domain.model.FileItem
import com.baluhost.android.presentation.ui.components.GlassCard
import com.baluhost.android.presentation.ui.components.GlassIntensity
import com.baluhost.android.presentation.ui.components.GradientButton
import com.baluhost.android.presentation.ui.components.OfflineBanner
import com.baluhost.android.presentation.ui.components.VpnStatusBanner
import com.baluhost.android.presentation.ui.theme.*
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Files Screen - Main file browser.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    onNavigateToVpn: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToPendingOperations: () -> Unit = {},
    onNavigateToMediaViewer: (fileUrl: String, fileName: String, mimeType: String?) -> Unit = { _, _, _ -> },
    viewModel: FilesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf<FileItem?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Collect VPN state for banner
    val isInHomeNetwork by viewModel.isInHomeNetwork.collectAsState()
    val hasVpnConfig by viewModel.hasVpnConfig.collectAsState()
    val vpnBannerDismissed by viewModel.vpnBannerDismissed.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()
    // Floating preview URL for overlay (shows a small image/video thumbnail)
    var floatingPreviewUrl by remember { mutableStateOf<String?>(null) }
    
    // Observe app lifecycle to trigger server check on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Show offline warning when losing connection
    LaunchedEffect(uiState.isOnline) {
        if (!uiState.isOnline) {
            snackbarHostState.showSnackbar(
                message = "Keine Internetverbindung",
                duration = SnackbarDuration.Short
            )
        }
    }
    
    // File picker for upload
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // Get original filename from URI
                val originalFileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                } ?: "upload_${System.currentTimeMillis()}"
                
                val inputStream = context.contentResolver.openInputStream(it)
                val tempFile = File(context.cacheDir, originalFileName)
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                viewModel.uploadFile(tempFile)
            } catch (e: Exception) {
                // Error handled by ViewModel
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Slate950, Slate900)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()  // Push content below system status bar
        ) {
            // Offline Banner below status bar
            OfflineBanner(isOnline = uiState.isOnline)
            
            // VPN Status Banner (shows when outside home network)
            VpnStatusBanner(
                isInHomeNetwork = isInHomeNetwork,
                isVpnActive = isVpnActive,
                hasVpnConfig = hasVpnConfig,
                onConnectVpn = onNavigateToVpn,
                onDismiss = { viewModel.dismissVpnBanner() },
                isDismissed = vpnBannerDismissed
            )
            
            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier.weight(1f),
                topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Dateien",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Slate100
                        )
                    },
                    navigationIcon = {
                        if (uiState.currentPath.isNotEmpty()) {
                            IconButton(onClick = { viewModel.navigateBack() }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Zurück",
                                    tint = Sky400
                                )
                            }
                        }
                    },
                    actions = {
                        // Selection mode toggle
                        if (!uiState.isSelectionMode) {
                            IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Auswählen",
                                    tint = Sky400
                                )
                            }
                        }
                        
                        // Pending operations badge
                        if (uiState.pendingOperationsCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = if (uiState.isOnline) Sky400 else Red500,
                                        contentColor = Slate950
                                    ) {
                                        Text("${uiState.pendingOperationsCount}")
                                    }
                                }
                            ) {
                                IconButton(onClick = onNavigateToPendingOperations) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = "Ausstehende Operationen",
                                        tint = Sky400
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            },
            floatingActionButton = {
                // Show FAB only when NOT in selection mode
                if (!uiState.isSelectionMode && !uiState.isUploading && !uiState.isDownloading) {
                    FloatingActionButton(
                        onClick = { 
                            if (uiState.isOnline) {
                                filePicker.launch("*/*")
                            }
                        },
                        containerColor = if (uiState.isOnline) Sky400 else Slate600,
                        contentColor = if (uiState.isOnline) Slate950 else Slate400
                    ) {
                        Icon(
                            Icons.Default.Add, 
                            contentDescription = if (uiState.isOnline) "Datei hochladen" else "Offline - Upload nicht möglich"
                        )
                    }
                }
            }
        ) { paddingValues ->
            SwipeRefresh(
                state = rememberSwipeRefreshState(uiState.isRefreshing),
                onRefresh = { viewModel.refreshFiles() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        uiState.isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = Sky400
                            )
                        }
                        uiState.files.isEmpty() && !uiState.isLoading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = Slate600
                                )
                                Text(
                                    text = "Keine Dateien vorhanden",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Slate400
                                )
                            }
                        }
                        else -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Bulk Action Bar (when in selection mode)
                                if (uiState.isSelectionMode) {
                                    BulkActionBar(
                                        selectedCount = uiState.selectedFiles.size,
                                        onSelectAll = { viewModel.selectAll() },
                                        onDeselectAll = { viewModel.deselectAll() },
                                        onDelete = {
                                            showDeleteDialog = uiState.selectedFiles.firstOrNull()
                                        },
                                        onDownload = { viewModel.downloadSelectedFiles() },
                                        onMove = { showFolderPicker = true },
                                        onCancel = { viewModel.toggleSelectionMode() }
                                    )
                                }

                                // Breadcrumb navigation
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        onClick = { viewModel.navigateToPath("") },
                                        shape = RoundedCornerShape(20.dp),
                                        color = Slate800.copy(alpha = 0.6f),
                                        border = BorderStroke(1.dp, Slate700.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "HOME",
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate300,
                                            letterSpacing = 2.sp
                                        )
                                    }

                                    if (uiState.currentPath.isNotEmpty()) {
                                        val segments = uiState.currentPath.split("/")
                                        segments.forEachIndexed { index, segment ->
                                            Text(
                                                text = "\u203A",
                                                color = Slate500,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Surface(
                                                onClick = {
                                                    viewModel.navigateToPath(
                                                        segments.take(index + 1).joinToString("/")
                                                    )
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                color = Slate800.copy(alpha = 0.6f),
                                                border = BorderStroke(1.dp, Slate700.copy(alpha = 0.5f))
                                            ) {
                                                Text(
                                                    text = segment.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Slate300,
                                                    letterSpacing = 2.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                // File list card
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(horizontal = 16.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Slate900.copy(alpha = 0.55f))
                                        .border(
                                            1.dp,
                                            Slate800.copy(alpha = 0.6f),
                                            RoundedCornerShape(16.dp)
                                        ),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    // NAME header
                                    item {
                                        Text(
                                            text = "NAME",
                                            modifier = Modifier.padding(
                                                horizontal = 20.dp,
                                                vertical = 14.dp
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate400,
                                            letterSpacing = 2.sp
                                        )
                                        HorizontalDivider(
                                            color = Slate700.copy(alpha = 0.5f),
                                            thickness = 0.5.dp
                                        )
                                    }

                                    // File items
                                    itemsIndexed(uiState.files) { index, file ->
                                        GlassFileListItem(
                                            file = file,
                                            isSelectionMode = uiState.isSelectionMode,
                                            isSelected = file in uiState.selectedFiles,
                                            onFileClick = {
                                                if (uiState.isSelectionMode) {
                                                    viewModel.toggleFileSelection(file)
                                                } else {
                                                    when {
                                                        file.isDirectory -> {
                                                            viewModel.navigateToFolder(file.name)
                                                        }
                                                        file.mimeType?.startsWith("image/") == true ||
                                                        file.mimeType?.startsWith("video/") == true ||
                                                        file.mimeType?.startsWith("audio/") == true -> {
                                                            floatingPreviewUrl = viewModel.getFileDownloadUrl(file.path)
                                                        }
                                                        else -> {}
                                                    }
                                                }
                                            },
                                            onDeleteClick = {
                                                showDeleteDialog = file
                                            },
                                            onLongClick = {
                                                if (!uiState.isSelectionMode) {
                                                    viewModel.toggleSelectionMode()
                                                    viewModel.toggleFileSelection(file)
                                                }
                                            }
                                        )
                                        if (index < uiState.files.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = Slate700.copy(alpha = 0.3f),
                                                thickness = 0.5.dp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                }
            
                    // Upload/Download Progress Overlay
                    if (uiState.isUploading || uiState.isDownloading) {
                    GlassCard(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .fillMaxWidth(),
                        intensity = GlassIntensity.Heavy
                    ) {
                        Text(
                            text = if (uiState.isUploading) "Hochladen..." else "Herunterladen...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Slate100
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LinearProgressIndicator(
                            progress = { if (uiState.isUploading) uiState.uploadProgress else uiState.downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Sky400,
                            trackColor = Slate700
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "${((if (uiState.isUploading) uiState.uploadProgress else uiState.downloadProgress) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate400
                        )
                    }
                }
                
                    // Error Snackbar
                    uiState.error?.let { error -> 
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        containerColor = Red500,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                        action = {
                            TextButton(onClick = { viewModel.clearError() }) {
                                Text("OK", color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    ) {
                        Text(error)
                    }
                }

                    // Floating preview shown as a modal Dialog to guarantee it's above navigation bars
                    if (floatingPreviewUrl != null) {
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { floatingPreviewUrl = null },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                // Scrim
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                                        .clickable { floatingPreviewUrl = null }
                                ) {}

                                // Centered preview card with safe max sizes
                                Card(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(16.dp)
                                        .fillMaxWidth(0.9f)
                                        .heightIn(max = 600.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Box(modifier = Modifier
                                        .fillMaxSize()
                                    ) {
                                        AsyncImage(
                                            model = floatingPreviewUrl,
                                            contentDescription = "Vorschaubild",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )

                                        // Close button
                                        IconButton(
                                            onClick = { floatingPreviewUrl = null },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .size(44.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Schließen", tint = Slate100)
                                        }

                                        // Click to open full viewer
                                        Box(modifier = Modifier
                                            .matchParentSize()
                                            .clickable {
                                                onNavigateToMediaViewer(floatingPreviewUrl!!, "Vorschau", null)
                                                floatingPreviewUrl = null
                                            }
                                        ) {}
                                    }
                                }
                            }
                        }
                    }
                }  // Close Box
            }  // Close SwipeRefresh
        }  // Close Scaffold
        }  // Close Column
        
        // Delete Confirmation Dialog
        showDeleteDialog?.let { file ->
            val selectedFiles = uiState.selectedFiles
            val isBulkDelete = selectedFiles.isNotEmpty()
            val deleteCount = if (isBulkDelete) selectedFiles.size else 1
            
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { 
                    Text(
                        if (isBulkDelete) {
                            "$deleteCount Dateien löschen?"
                        } else {
                            "${file.name} löschen?"
                        },
                        fontWeight = FontWeight.Bold
                    ) 
                },
                text = { 
                    Text(
                        if (isBulkDelete) {
                            "$deleteCount Dateien werden gelöscht. Diese Aktion kann nicht rückgängig gemacht werden."
                        } else if (file.isDirectory) {
                            "Dadurch werden der Ordner und alle seine Inhalte gelöscht. Diese Aktion kann nicht rückgängig gemacht werden."
                        } else {
                            "Diese Aktion kann nicht rückgängig gemacht werden."
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (isBulkDelete) {
                                viewModel.deleteSelectedFiles()
                            } else {
                                val fullPath = if (uiState.currentPath.isEmpty()) {
                                    file.name
                                } else {
                                    "${uiState.currentPath}/${file.name}"
                                }
                                viewModel.deleteFile(fullPath)
                            }
                            showDeleteDialog = null
                        }
                    ) {
                        Text("Löschen", color = Red400)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) {
                        Text("Abbrechen")
                    }
                }
            )
        }
    }
    
    // Folder Picker Dialog for move operation
    if (showFolderPicker) {
        FolderPickerDialog(
            currentPath = uiState.currentPath,
            folders = uiState.files.filter { it.isDirectory },
            onDismiss = { showFolderPicker = false },
            onFolderSelected = { destinationPath ->
                viewModel.moveSelectedFiles(destinationPath)
                showFolderPicker = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassFileListItem(
    file: FileItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onFileClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onFileClick,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) Modifier.background(Slate800.copy(alpha = 0.4f))
                else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Checkbox in selection mode
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onFileClick() },
                modifier = Modifier.size(40.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = Sky400,
                    uncheckedColor = Slate400
                )
            )
        }

        // Icon
        Icon(
            imageVector = when {
                file.isDirectory && file.name.equals("Shared", ignoreCase = true) -> Icons.Default.Group
                file.isDirectory -> Icons.Default.Folder
                file.name.endsWith(".pdf", ignoreCase = true) -> Icons.Default.Description
                file.name.endsWith(".jpg", ignoreCase = true) ||
                file.name.endsWith(".jpeg", ignoreCase = true) ||
                file.name.endsWith(".png", ignoreCase = true) -> Icons.Default.Image
                file.name.endsWith(".mp4", ignoreCase = true) ||
                file.name.endsWith(".avi", ignoreCase = true) ||
                file.name.endsWith(".mov", ignoreCase = true) -> Icons.Default.VideoFile
                else -> Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = when {
                file.isDirectory && file.name.equals("Shared", ignoreCase = true) -> Green400
                file.isDirectory -> Sky400
                else -> Indigo400
            }
        )

        // Name
        Text(
            text = file.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Slate200
        )

        // Context menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Optionen",
                    modifier = Modifier.size(18.dp),
                    tint = Slate500
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Löschen") },
                    onClick = {
                        showMenu = false
                        onDeleteClick()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}

/**
 * Bulk Action Bar shown when files are selected.
 */
@Composable
fun BulkActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onMove: () -> Unit,
    onCancel: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        intensity = GlassIntensity.Medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection count
            Text(
                text = "$selectedCount ausgewählt",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Sky400
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Select All / Deselect All
                IconButton(onClick = if (selectedCount > 0) onDeselectAll else onSelectAll) {
                    Icon(
                        imageVector = if (selectedCount > 0) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                        contentDescription = if (selectedCount > 0) "Alle abwählen" else "Alle auswählen",
                        tint = Slate300
                    )
                }
                
                // Download
                if (selectedCount > 0) {
                    IconButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Herunterladen",
                            tint = Sky400
                        )
                    }
                }
                
                // Move
                if (selectedCount > 0) {
                    IconButton(onClick = onMove) {
                        Icon(
                            imageVector = Icons.Default.DriveFileMove,
                            contentDescription = "Verschieben",
                            tint = Sky400
                        )
                    }
                }
                
                // Delete
                if (selectedCount > 0) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Löschen",
                            tint = Red400
                        )
                    }
                }
                
                // Cancel
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Abbrechen",
                        tint = Slate400
                    )
                }
            }
        }
    }
}
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp * 1000))
}

/**
 * Folder Picker Dialog for selecting destination folder for move operation.
 */
@Composable
fun FolderPickerDialog(
    currentPath: String,
    folders: List<FileItem>,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Zielordner wählen",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Wähle einen Zielordner für die ausgewählten Dateien:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Root folder option
                TextButton(
                    onClick = { onFolderSelected("") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = null,
                            tint = Sky400
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "/ (Root)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                
                // Current folder option (if not in root)
                if (currentPath.isNotEmpty()) {
                    TextButton(
                        onClick = { onFolderSelected(currentPath) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Sky400
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = currentPath.ifEmpty { "/" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                // Subfolders
                if (folders.isNotEmpty()) {
                    Text(
                        text = "Unterordner:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    
                    folders.forEach { folder ->
                        TextButton(
                            onClick = {
                                val folderPath = if (currentPath.isEmpty()) {
                                    folder.name
                                } else {
                                    "$currentPath/${folder.name}"
                                }
                                onFolderSelected(folderPath)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = Sky400
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                } else if (currentPath.isEmpty()) {
                    Text(
                        text = "Keine Ordner verfügbar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

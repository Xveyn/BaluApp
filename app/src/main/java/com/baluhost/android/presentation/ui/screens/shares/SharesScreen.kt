package com.baluhost.android.presentation.ui.screens.shares

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.data.remote.dto.FileItemDto
import com.baluhost.android.data.remote.dto.ShareableUserDto
import com.baluhost.android.domain.model.FileShareInfo
import com.baluhost.android.domain.model.SharedWithMeInfo
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SharesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val shareGradient = listOf(Color(0xFF3B82F6), Color(0xFF6366F1))

    // Success snackbar
    LaunchedEffect(uiState.createSuccess) {
        if (uiState.createSuccess) {
            kotlinx.coroutines.delay(2000)
            viewModel.dismissCreateSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Shares",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadShares() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showCreateShareDialog() },
                containerColor = Color(0xFF3B82F6),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Share file")
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        BaluBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Tab Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Shared with me", "Shared by me").forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Surface(
                            onClick = { selectedTab = index },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.2f)
                                    else Color.Transparent,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6))
                                     else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) Color.White else Color(0xFF64748B),
                                modifier = Modifier.padding(vertical = 10.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF3B82F6))
                    }
                } else {
                    when (selectedTab) {
                        0 -> SharedWithMeTab(uiState.sharedWithMe)
                        1 -> SharedByMeTab(
                            shares = uiState.myShares,
                            onDeleteShare = { viewModel.deleteShare(it) }
                        )
                    }
                }

                // Error snackbar at bottom
                if (uiState.error != null) {
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        action = {
                            TextButton(onClick = { viewModel.dismissError() }) {
                                Text("Dismiss", color = Color(0xFF3B82F6))
                            }
                        },
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color(0xFFFDA4AF)
                    ) {
                        Text(uiState.error ?: "")
                    }
                }

                // Success snackbar
                if (uiState.createSuccess) {
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        action = {
                            TextButton(onClick = { viewModel.dismissCreateSuccess() }) {
                                Text("OK", color = Color(0xFF3B82F6))
                            }
                        },
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color(0xFF4ADE80)
                    ) {
                        Text("Share created successfully")
                    }
                }
            }
        }
    }

    // Create Share Dialog
    if (uiState.showCreateDialog) {
        CreateShareDialog(
            uiState = uiState,
            onDismiss = { viewModel.dismissCreateShareDialog() },
            onNavigateToFolder = { viewModel.navigateToFolder(it) },
            onNavigateUp = { viewModel.navigateUp() },
            onSelectFile = { viewModel.selectFile(it) },
            onSelectUser = { viewModel.selectUser(it) },
            onSetCanRead = { viewModel.setCanRead(it) },
            onSetCanWrite = { viewModel.setCanWrite(it) },
            onSetCanDelete = { viewModel.setCanDelete(it) },
            onSetCanShare = { viewModel.setCanShare(it) },
            onSetExpiresAt = { viewModel.setExpiresAt(it) },
            onCreateShare = { viewModel.createShare() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateShareDialog(
    uiState: SharesUiState,
    onDismiss: () -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectFile: (FileItemDto) -> Unit,
    onSelectUser: (ShareableUserDto) -> Unit,
    onSetCanRead: (Boolean) -> Unit,
    onSetCanWrite: (Boolean) -> Unit,
    onSetCanDelete: (Boolean) -> Unit,
    onSetCanShare: (Boolean) -> Unit,
    onSetExpiresAt: (Long?) -> Unit,
    onCreateShare: () -> Unit
) {
    val canCreate = uiState.selectedFile != null && uiState.selectedUser != null && !uiState.isCreating
    var showDatePicker by remember { mutableStateOf(false) }
    var hasExpiration by remember { mutableStateOf(uiState.expiresAt != null) }
    var userDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f),
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                "Share a File",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── File Browser Section ──
                Text(
                    "Select File",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                // Path breadcrumb
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.browserPath.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { onNavigateUp() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.browserPath.isEmpty()) "/" else uiState.browserPath,
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // File list
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0B1120),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    if (uiState.isLoadingFiles) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF3B82F6),
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else if (uiState.browserFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No files in this directory",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                        ) {
                            // Sort: directories first, then files
                            val sortedFiles = uiState.browserFiles.sortedWith(
                                compareByDescending<FileItemDto> { it.isDirectory }
                                    .thenBy { it.name.lowercase() }
                            )
                            sortedFiles.forEach { file ->
                                val isSelected = uiState.selectedFile?.path == file.path
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (isSelected) Modifier.border(
                                                1.dp,
                                                Color(0xFF3B82F6),
                                                RoundedCornerShape(4.dp)
                                            )
                                            else Modifier
                                        )
                                        .clickable {
                                            if (file.isDirectory) {
                                                onNavigateToFolder(file.path)
                                            } else {
                                                onSelectFile(file)
                                            }
                                        }
                                        .background(
                                            if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.1f)
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (file.isDirectory) Icons.Default.Folder
                                        else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (file.isDirectory) Color(0xFFFBBF24)
                                        else Color(0xFF94A3B8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!file.isDirectory) {
                                            Text(
                                                text = formatFileSize(file.size),
                                                color = Color(0xFF64748B),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    if (file.isDirectory) {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = Color(0xFF475569),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (file.fileId == null && !file.isDirectory) {
                                        Text(
                                            "no id",
                                            color = Color(0xFF475569),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (file != sortedFiles.last()) {
                                    HorizontalDivider(
                                        color = Color(0xFF1E293B).copy(alpha = 0.5f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }
                    }
                }

                // Selected file indicator
                if (uiState.selectedFile != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF3B82F6).copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = uiState.selectedFile!!.name,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // ── User Selection ──
                Text(
                    "Share with",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                if (uiState.isLoadingUsers) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF3B82F6),
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            "Loading users...",
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else if (uiState.shareableUsers.isEmpty()) {
                    Text(
                        "No users available",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = userDropdownExpanded,
                        onExpandedChange = { userDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = uiState.selectedUser?.username ?: "Select user",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = userDropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color(0xFF1E293B),
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedTextColor = if (uiState.selectedUser != null) Color.White else Color(0xFF64748B),
                                focusedTextColor = Color.White,
                                cursorColor = Color(0xFF3B82F6),
                                unfocusedContainerColor = Color(0xFF0B1120),
                                focusedContainerColor = Color(0xFF0B1120)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        ExposedDropdownMenu(
                            expanded = userDropdownExpanded,
                            onDismissRequest = { userDropdownExpanded = false },
                            containerColor = Color(0xFF1E293B)
                        ) {
                            uiState.shareableUsers.forEach { user ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            user.username,
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    onClick = {
                                        onSelectUser(user)
                                        userDropdownExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = null,
                                            tint = Color(0xFF3B82F6),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // ── Permissions ──
                Text(
                    "Permissions",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0B1120),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        PermissionToggle("Read", uiState.canRead, onSetCanRead)
                        HorizontalDivider(color = Color(0xFF1E293B).copy(alpha = 0.5f), thickness = 0.5.dp)
                        PermissionToggle("Write", uiState.canWrite, onSetCanWrite)
                        HorizontalDivider(color = Color(0xFF1E293B).copy(alpha = 0.5f), thickness = 0.5.dp)
                        PermissionToggle("Delete", uiState.canDelete, onSetCanDelete)
                        HorizontalDivider(color = Color(0xFF1E293B).copy(alpha = 0.5f), thickness = 0.5.dp)
                        PermissionToggle("Share", uiState.canShare, onSetCanShare)
                    }
                }

                // ── Expiration ──
                Text(
                    "Expiration",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0B1120),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Set expiration date",
                                color = Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = hasExpiration,
                                onCheckedChange = {
                                    hasExpiration = it
                                    if (!it) onSetExpiresAt(null)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Color(0xFF3B82F6),
                                    uncheckedTrackColor = Color(0xFF1E293B),
                                    uncheckedBorderColor = Color(0xFF334155),
                                    uncheckedThumbColor = Color(0xFF64748B)
                                )
                            )
                        }

                        if (hasExpiration) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showDatePicker = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.DateRange,
                                    contentDescription = null,
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (uiState.expiresAt != null) {
                                        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
                                        sdf.format(Date(uiState.expiresAt!!))
                                    } else {
                                        "Select date"
                                    },
                                    color = if (uiState.expiresAt != null) Color.White else Color(0xFF64748B),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Error
                if (uiState.createError != null) {
                    Text(
                        text = uiState.createError!!,
                        color = Color(0xFFFDA4AF),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCreateShare,
                enabled = canCreate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3B82F6),
                    disabledContainerColor = Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (uiState.isCreating) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    "Share",
                    color = if (canCreate) Color.White else Color(0xFF475569)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )

    // Date Picker Dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.expiresAt ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onSetExpiresAt(datePickerState.selectedDateMillis)
                    showDatePicker = false
                }) {
                    Text("OK", color = Color(0xFF3B82F6))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            colors = DatePickerDefaults.colors(
                containerColor = Color(0xFF0F172A)
            )
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White,
                    headlineContentColor = Color.White,
                    weekdayContentColor = Color(0xFF94A3B8),
                    yearContentColor = Color.White,
                    currentYearContentColor = Color(0xFF3B82F6),
                    selectedYearContainerColor = Color(0xFF3B82F6),
                    selectedDayContainerColor = Color(0xFF3B82F6),
                    todayContentColor = Color(0xFF3B82F6),
                    todayDateBorderColor = Color(0xFF3B82F6),
                    dayContentColor = Color(0xFFCBD5E1),
                    navigationContentColor = Color.White,
                    subheadContentColor = Color(0xFF94A3B8)
                )
            )
        }
    }
}

@Composable
private fun PermissionToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = Color(0xFFCBD5E1),
            style = MaterialTheme.typography.bodySmall
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF3B82F6),
                uncheckedTrackColor = Color(0xFF1E293B),
                uncheckedBorderColor = Color(0xFF334155),
                uncheckedThumbColor = Color(0xFF64748B)
            )
        )
    }
}

private fun formatFileSize(bytes: Long): String = com.baluhost.android.util.ByteFormatter.format(bytes)

@Composable
private fun SharedWithMeTab(shares: List<SharedWithMeInfo>) {
    if (shares.isEmpty()) {
        EmptyState(
            icon = Icons.Default.FolderShared,
            message = "No files have been shared with you"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shares, key = { it.shareId }) { share ->
                SharedWithMeCard(share)
            }
        }
    }
}

@Composable
private fun SharedByMeTab(
    shares: List<FileShareInfo>,
    onDeleteShare: (Int) -> Unit
) {
    if (shares.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Share,
            message = "You haven't shared any files"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shares, key = { it.id }) { share ->
                SharedByMeCard(share, onDeleteShare)
            }
        }
    }
}

@Composable
private fun SharedWithMeCard(share: SharedWithMeInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // File icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (share.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = share.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "from ${share.ownerUsername}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    PermissionBadge("R", share.canRead)
                    PermissionBadge("W", share.canWrite)
                    PermissionBadge("D", share.canDelete)
                }
            }

            if (share.isExpired) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.15f)
                ) {
                    Text(
                        "Expired",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFEF4444),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedByMeCard(share: FileShareInfo, onDeleteShare: (Int) -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // File icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (share.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = share.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "to ${share.sharedWithUsername}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    PermissionBadge("R", share.canRead)
                    PermissionBadge("W", share.canWrite)
                    PermissionBadge("D", share.canDelete)
                }
            }

            // Revoke button
            IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Revoke share",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteShare(share.id)
                }) {
                    Text("Revoke", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            title = {
                Text(
                    "Revoke Share",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    "Stop sharing \"${share.fileName}\" with ${share.sharedWithUsername}?",
                    color = Color(0xFF94A3B8)
                )
            },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun PermissionBadge(label: String, enabled: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (enabled) Color(0xFF3B82F6).copy(alpha = 0.2f) else Color(0xFF1E293B).copy(alpha = 0.5f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color(0xFF3B82F6) else Color(0xFF475569),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF334155),
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B)
            )
        }
    }
}

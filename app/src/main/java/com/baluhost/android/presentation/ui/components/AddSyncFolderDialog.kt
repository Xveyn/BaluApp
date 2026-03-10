package com.baluhost.android.presentation.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baluhost.android.domain.model.sync.ConflictResolution
import com.baluhost.android.domain.model.sync.SyncType
import com.baluhost.android.presentation.ui.screens.sync.NasBrowseState
import com.baluhost.android.presentation.ui.screens.sync.NasFolder
import com.baluhost.android.presentation.ui.theme.*

/**
 * Dialog to add a new sync folder configuration.
 * Allows browsing NAS directories to select a remote target path.
 *
 * Regular users: remote path defaults to their home directory ({username}/{folderName})
 * Admin users: can set any target path on the NAS
 */
@Composable
fun AddSyncFolderDialog(
    localFolderUri: Uri?,
    localFolderName: String?,
    nasState: NasBrowseState,
    username: String,
    isAdmin: Boolean,
    onBrowseNas: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (
        localPath: String,
        remotePath: String,
        syncType: SyncType,
        conflictResolution: ConflictResolution,
        autoSync: Boolean
    ) -> Unit
) {
    var syncType by remember { mutableStateOf(SyncType.BIDIRECTIONAL) }
    var conflictResolution by remember { mutableStateOf(ConflictResolution.KEEP_NEWEST) }
    var autoSync by remember { mutableStateOf(true) }
    var showSyncTypeDropdown by remember { mutableStateOf(false) }
    var showConflictResolutionDropdown by remember { mutableStateOf(false) }
    var showNasBrowser by remember { mutableStateOf(false) }

    // Remote path defaults to user's home directory
    // Admin: can choose any path; Regular user: always inside {username}/
    val defaultPath = if (username.isNotEmpty()) {
        val folderPart = if (!localFolderName.isNullOrEmpty()) localFolderName else "Sync"
        "$username/$folderPart"
    } else {
        if (!localFolderName.isNullOrEmpty()) "$localFolderName/mobile" else "sync/mobile"
    }
    var remotePath by remember { mutableStateOf(defaultPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Synchronisierter Ordner hinzufügen",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            if (showNasBrowser) {
                // NAS folder browser view
                NasFolderBrowser(
                    nasState = nasState,
                    onNavigate = onBrowseNas,
                    onSelect = { selectedPath ->
                        remotePath = selectedPath
                        showNasBrowser = false
                    },
                    onBack = { showNasBrowser = false }
                )
            } else {
                // Normal config view
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Selected Local Folder
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Lokales Verzeichnis",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = localFolderName ?: "Kein Verzeichnis ausgewählt",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Remote Path - editable with browse button
                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { newPath ->
                            // Non-admin users: enforce username prefix
                            remotePath = if (!isAdmin && username.isNotEmpty() && !newPath.startsWith("$username/")) {
                                "$username/${newPath.removePrefix("/")}"
                            } else {
                                newPath
                            }
                        },
                        label = { Text("Zielverzeichnis auf dem NAS") },
                        leadingIcon = {
                            Icon(Icons.Default.Sync, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = {
                                val startPath = if (!isAdmin && username.isNotEmpty()) username else ""
                                onBrowseNas(startPath)
                                showNasBrowser = true
                            }) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "NAS durchsuchen",
                                    tint = Sky400
                                )
                            }
                        },
                        placeholder = {
                            Text(if (isAdmin) "z.B. sven/Dokumente" else "z.B. $username/Dokumente")
                        },
                        supportingText = if (!isAdmin && username.isNotEmpty()) {
                            { Text("Pfad innerhalb von $username/", color = Slate400) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Sky400,
                            focusedLabelColor = Sky400,
                            focusedLeadingIconColor = Sky400,
                            unfocusedBorderColor = Slate600,
                            unfocusedLabelColor = Slate400,
                            cursorColor = Sky400
                        )
                    )

                    // Browse NAS button
                    OutlinedButton(
                        onClick = {
                            // Non-admin: start browsing in user's home directory
                            val startPath = if (!isAdmin && username.isNotEmpty()) username else ""
                            onBrowseNas(startPath)
                            showNasBrowser = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Sky400),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Sky400.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("NAS-Ordner durchsuchen")
                    }

                    Divider()

                    // Sync Type Selection
                    Text(
                        text = "Synchronisierungsrichtung",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showSyncTypeDropdown = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(syncTypeLabel(syncType))
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        }
                        DropdownMenu(
                            expanded = showSyncTypeDropdown,
                            onDismissRequest = { showSyncTypeDropdown = false }
                        ) {
                            SyncType.values().forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(syncTypeLabel(type)) },
                                    onClick = {
                                        syncType = type
                                        showSyncTypeDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Conflict Resolution Strategy
                    Text(
                        text = "Konfliktauflösung",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showConflictResolutionDropdown = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(conflictResolutionLabel(conflictResolution))
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        }
                        DropdownMenu(
                            expanded = showConflictResolutionDropdown,
                            onDismissRequest = { showConflictResolutionDropdown = false }
                        ) {
                            ConflictResolution.values().forEach { strategy ->
                                DropdownMenuItem(
                                    text = { Text(conflictResolutionLabel(strategy)) },
                                    onClick = {
                                        conflictResolution = strategy
                                        showConflictResolutionDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Auto Sync Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatische Synchronisierung",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Synchronisiere automatisch im Hintergrund",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoSync,
                            onCheckedChange = { autoSync = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!showNasBrowser) {
                Button(
                    onClick = {
                        if (localFolderUri != null) {
                            onConfirm(
                                localFolderUri.toString(),
                                remotePath,
                                syncType,
                                conflictResolution,
                                autoSync
                            )
                        }
                    },
                    enabled = localFolderUri != null && remotePath.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Hinzufügen")
                }
            }
        },
        dismissButton = {
            if (!showNasBrowser) {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
            }
        },
        containerColor = Slate900,
        titleContentColor = Slate100,
        textContentColor = Slate300
    )
}

/**
 * Inline NAS folder browser component.
 */
@Composable
private fun NasFolderBrowser(
    nasState: NasBrowseState,
    onNavigate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 400.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header with back button and current path
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Zurück",
                    tint = Sky400
                )
            }
            Text(
                text = "NAS-Ordner wählen",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Slate100
            )
        }

        // Current path display
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = Sky400,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (nasState.currentPath.isEmpty()) "/" else "/${nasState.currentPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate300,
                    modifier = Modifier.weight(1f)
                )
                // Select current folder button
                TextButton(
                    onClick = { onSelect(nasState.currentPath) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Green500)
                ) {
                    Text("Auswählen", fontSize = 12.sp)
                }
            }
        }

        // Navigate up button (if not at root)
        if (nasState.currentPath.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val parentPath = nasState.currentPath
                            .trimEnd('/')
                            .substringBeforeLast('/', "")
                        onNavigate(parentPath)
                    },
                colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = Slate400,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "..",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate300
                    )
                }
            }
        }

        // Loading state
        if (nasState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Sky400,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Error state
        nasState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = Red500,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Red500
                    )
                }
            }
        }

        // Folder list
        if (!nasState.isLoading && nasState.error == null) {
            if (nasState.folders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Keine Unterordner vorhanden",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(nasState.folders, key = { it.path }) { folder ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(folder.path) },
                            colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = Sky400,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate100,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Slate500,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun syncTypeLabel(type: SyncType): String = when (type) {
    SyncType.UPLOAD_ONLY -> "Nur hochladen"
    SyncType.DOWNLOAD_ONLY -> "Nur herunterladen"
    SyncType.BIDIRECTIONAL -> "Bidirektional"
}

@Composable
private fun conflictResolutionLabel(strategy: ConflictResolution): String = when (strategy) {
    ConflictResolution.KEEP_LOCAL -> "Telefon bevorzugen"
    ConflictResolution.KEEP_SERVER -> "NAS bevorzugen"
    ConflictResolution.KEEP_NEWEST -> "Neueste Version (Standard)"
    ConflictResolution.ASK_USER -> "Mich fragen"
}

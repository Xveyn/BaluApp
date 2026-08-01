package com.baluhost.android.presentation.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.NotificationCategory
import com.baluhost.android.domain.model.NotificationType
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.GlassCard
import com.baluhost.android.presentation.ui.components.GlassIntensity
import com.baluhost.android.presentation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPreferences: () -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Snackbar events are only emitted for user-triggered actions (restore, delete
    // permanently, empty trash, dismiss all) - see NotificationsViewModel.snackbarEvent.
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    var showDismissAllDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Benachrichtigungen",
                            color = Color.White
                        )
                        if (unreadCount > 0) {
                            Badge(
                                containerColor = Red500,
                                contentColor = Color.White
                            ) {
                                Text("$unreadCount")
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Zurück",
                            tint = Slate400
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToPreferences) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Einstellungen",
                            tint = Slate400
                        )
                    }
                    if (unreadCount > 0) {
                        IconButton(onClick = { viewModel.markAllAsRead() }) {
                            Icon(
                                Icons.Default.DoneAll,
                                contentDescription = "Alle gelesen",
                                tint = Sky400
                            )
                        }
                    }
                    if (uiState.notifications.isNotEmpty()) {
                        when (uiState.tab) {
                            NotificationsViewModel.Tab.INBOX -> {
                                IconButton(onClick = { showDismissAllDialog = true }) {
                                    Icon(
                                        Icons.Default.ClearAll,
                                        contentDescription = "Alle wegklicken",
                                        tint = Slate400
                                    )
                                }
                            }
                            NotificationsViewModel.Tab.TRASH -> {
                                IconButton(onClick = { showEmptyTrashDialog = true }) {
                                    Icon(
                                        Icons.Default.DeleteSweep,
                                        contentDescription = "Papierkorb leeren",
                                        tint = Slate400
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    containerColor = Slate800,
                    contentColor = Color.White,
                    snackbarData = data
                )
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
                // Inbox / Trash tabs
                TabRow(
                    selectedTabIndex = uiState.tab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = Sky400
                ) {
                    Tab(
                        selected = uiState.tab == NotificationsViewModel.Tab.INBOX,
                        onClick = { viewModel.setTab(NotificationsViewModel.Tab.INBOX) },
                        text = { Text("Posteingang") },
                        selectedContentColor = Sky400,
                        unselectedContentColor = Slate400
                    )
                    Tab(
                        selected = uiState.tab == NotificationsViewModel.Tab.TRASH,
                        onClick = { viewModel.setTab(NotificationsViewModel.Tab.TRASH) },
                        text = { Text("Papierkorb") },
                        selectedContentColor = Sky400,
                        unselectedContentColor = Slate400
                    )
                }

                if (uiState.tab == NotificationsViewModel.Tab.TRASH) {
                    Text(
                        "Einträge werden nach ${uiState.retentionDays} Tagen endgültig gelöscht",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Filter Chips: category (raw string - see rawCategoryLabel/-Icon),
                // type, and unread-only
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedCategory == null,
                            onClick = { viewModel.setCategory(null) },
                            label = { Text("Alle") },
                            colors = filterChipColors(uiState.selectedCategory == null)
                        )
                    }
                    items(uiState.availableCategories) { rawCategory ->
                        FilterChip(
                            selected = uiState.selectedCategory == rawCategory,
                            onClick = {
                                viewModel.setCategory(
                                    if (uiState.selectedCategory == rawCategory) null else rawCategory
                                )
                            },
                            label = { Text(rawCategoryLabel(rawCategory)) },
                            leadingIcon = {
                                Icon(
                                    rawCategoryIcon(rawCategory),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = filterChipColors(uiState.selectedCategory == rawCategory)
                        )
                    }
                    item {
                        FilterChip(
                            selected = uiState.typeFilter == NotificationType.INFO,
                            onClick = {
                                viewModel.setTypeFilter(
                                    if (uiState.typeFilter == NotificationType.INFO) null else NotificationType.INFO
                                )
                            },
                            label = { Text("Info") },
                            colors = filterChipColors(uiState.typeFilter == NotificationType.INFO)
                        )
                    }
                    item {
                        FilterChip(
                            selected = uiState.typeFilter == NotificationType.WARNING,
                            onClick = {
                                viewModel.setTypeFilter(
                                    if (uiState.typeFilter == NotificationType.WARNING) null else NotificationType.WARNING
                                )
                            },
                            label = { Text("Warnung") },
                            colors = filterChipColors(uiState.typeFilter == NotificationType.WARNING)
                        )
                    }
                    item {
                        FilterChip(
                            selected = uiState.typeFilter == NotificationType.CRITICAL,
                            onClick = {
                                viewModel.setTypeFilter(
                                    if (uiState.typeFilter == NotificationType.CRITICAL) null else NotificationType.CRITICAL
                                )
                            },
                            label = { Text("Kritisch") },
                            colors = filterChipColors(uiState.typeFilter == NotificationType.CRITICAL)
                        )
                    }
                    item {
                        FilterChip(
                            selected = uiState.unreadOnly,
                            onClick = { viewModel.toggleUnreadOnly() },
                            label = { Text("Nur ungelesen") },
                            colors = filterChipColors(uiState.unreadOnly)
                        )
                    }
                }

                if (uiState.isOffline) {
                    Text(
                        "Offline – zuletzt bekannter Stand",
                        color = Orange500,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Notification List
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        // uiState.hasLoadedOnce is the ViewModel's own "have I ever
                        // delivered a real list" flag (see its doc comment) - reading
                        // it straight from uiState, instead of a screen-local flag
                        // racing a fresh subscription, means a populated list is never
                        // hidden behind a spinner just because the screen itself was
                        // freshly recomposed (rotation, navigating to Preferences and
                        // back): the moment the list is non-empty, this branch is false
                        // regardless of hasLoadedOnce.
                        !uiState.hasLoadedOnce && uiState.notifications.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Sky400)
                            }
                        }
                        // A sync is still filling an empty list: show the spinner
                        // rather than "no notifications" for the moment before the
                        // first real batch lands.
                        uiState.notifications.isEmpty() && uiState.isRefreshing -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Sky400)
                            }
                        }
                        uiState.notifications.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.NotificationsNone,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = Slate400.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        if (uiState.tab == NotificationsViewModel.Tab.TRASH) {
                                            "Papierkorb ist leer"
                                        } else {
                                            "Keine Benachrichtigungen"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Slate400
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = uiState.notifications,
                                    key = { it.id }
                                ) { notification ->
                                    NotificationCard(
                                        notification = notification,
                                        tab = uiState.tab,
                                        onMarkRead = { viewModel.markAsRead(notification.id) },
                                        onDismiss = { viewModel.dismiss(notification.id) },
                                        onSnooze = { viewModel.snooze(notification.id) },
                                        onRestore = { viewModel.restore(notification.id) },
                                        onDeletePermanently = { viewModel.deletePermanently(notification.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDismissAllDialog) {
        AlertDialog(
            onDismissRequest = { showDismissAllDialog = false },
            containerColor = Slate900,
            title = { Text("Alle wegklicken?", color = Color.White) },
            text = {
                Text(
                    "Alle Benachrichtigungen im Posteingang werden in den Papierkorb verschoben. " +
                        "Das betrifft mehrere Einträge auf einmal.",
                    color = Slate300
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDismissAllDialog = false
                    viewModel.dismissAll()
                }) {
                    Text("Wegklicken", color = Red400)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDismissAllDialog = false }) {
                    Text("Abbrechen", color = Slate400)
                }
            }
        )
    }

    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            containerColor = Slate900,
            title = { Text("Papierkorb leeren?", color = Color.White) },
            text = {
                Text(
                    "Alle Einträge im Papierkorb werden endgültig gelöscht. " +
                        "Das kann nicht rückgängig gemacht werden.",
                    color = Slate300
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyTrashDialog = false
                    viewModel.emptyTrash()
                }) {
                    Text("Leeren", color = Red400)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text("Abbrechen", color = Slate400)
                }
            }
        )
    }
}

@Composable
private fun NotificationCard(
    notification: AppNotification,
    tab: NotificationsViewModel.Tab,
    onMarkRead: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isTrash = tab == NotificationsViewModel.Tab.TRASH
    val onCardClick: (() -> Unit)? = if (isTrash) {
        null
    } else {
        { if (!notification.isRead) onMarkRead() }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        intensity = GlassIntensity.Medium,
        onClick = onCardClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Unread indicator + Category icon
            Box {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(typeColor(notification.type).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = rawCategoryIcon(notification.rawCategory),
                        contentDescription = null,
                        tint = typeColor(notification.type),
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (!notification.isRead) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Sky400)
                            .align(Alignment.TopStart)
                    )
                }
            }

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (!notification.isRead) FontWeight.Bold else FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Priority indicator for critical
                    if (notification.priority >= 3) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Red400)
                        )
                    }
                }

                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    notification.timeAgo?.let { timeAgo ->
                        Text(
                            text = timeAgo,
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500
                        )
                    }

                    // Context menu
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Aktionen",
                                tint = Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = Slate900
                        ) {
                            if (isTrash) {
                                DropdownMenuItem(
                                    text = { Text("Wiederherstellen", color = Slate300) },
                                    onClick = {
                                        showMenu = false
                                        onRestore()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.RotateLeft, null, tint = Sky400)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Endgültig löschen", color = Slate300) },
                                    onClick = {
                                        showMenu = false
                                        onDeletePermanently()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.DeleteForever, null, tint = Red400)
                                    }
                                )
                            } else {
                                if (!notification.isRead) {
                                    DropdownMenuItem(
                                        text = { Text("Als gelesen markieren", color = Slate300) },
                                        onClick = {
                                            showMenu = false
                                            onMarkRead()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Done, null, tint = Sky400)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Schlummern (1h)", color = Slate300) },
                                    onClick = {
                                        showMenu = false
                                        onSnooze()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Snooze, null, tint = Yellow400)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Verwerfen", color = Slate300) },
                                    onClick = {
                                        showMenu = false
                                        onDismiss()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Close, null, tint = Red400)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Critical priority left border accent
        if (notification.priority >= 3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Red400)
            )
        }
    }
}

/** Icon for a core [NotificationCategory]; unmatched raw categories use [rawCategoryIcon]. */
private fun categoryIcon(category: NotificationCategory): ImageVector = when (category) {
    NotificationCategory.RAID -> Icons.Default.Storage
    NotificationCategory.SMART -> Icons.Default.HealthAndSafety
    NotificationCategory.BACKUP -> Icons.Default.Backup
    NotificationCategory.SCHEDULER -> Icons.Default.Schedule
    NotificationCategory.SYSTEM -> Icons.Default.Computer
    NotificationCategory.SECURITY -> Icons.Default.Security
    NotificationCategory.SYNC -> Icons.Default.Sync
    NotificationCategory.VPN -> Icons.Default.VpnKey
}

/** Label for a core [NotificationCategory]; unmatched raw categories use [rawCategoryLabel]. */
private fun categoryLabel(category: NotificationCategory): String = when (category) {
    NotificationCategory.RAID -> "RAID"
    NotificationCategory.SMART -> "SMART"
    NotificationCategory.BACKUP -> "Backup"
    NotificationCategory.SCHEDULER -> "Scheduler"
    NotificationCategory.SYSTEM -> "System"
    NotificationCategory.SECURITY -> "Sicherheit"
    NotificationCategory.SYNC -> "Sync"
    NotificationCategory.VPN -> "VPN"
}

/**
 * Icon for a raw server category string. The server's category set is open (core
 * categories, "lifecycle", plugin names), so this maps onto the closed
 * [NotificationCategory] enum only when the raw string matches one of its entries,
 * and falls back to a generic icon otherwise - unlike filtering, an icon lookup can
 * degrade gracefully instead of needing to reach every possible value.
 */
private fun rawCategoryIcon(rawCategory: String): ImageVector =
    NotificationCategory.entries.find { it.name.equals(rawCategory, ignoreCase = true) }
        ?.let { categoryIcon(it) }
        ?: Icons.Default.Category

/** Label for a raw server category string; see [rawCategoryIcon] for the fallback rule. */
private fun rawCategoryLabel(rawCategory: String): String =
    NotificationCategory.entries.find { it.name.equals(rawCategory, ignoreCase = true) }
        ?.let { categoryLabel(it) }
        ?: rawCategory.replaceFirstChar { it.uppercase() }

private fun typeColor(type: NotificationType): Color = when (type) {
    NotificationType.CRITICAL -> Red400
    NotificationType.WARNING -> Yellow400
    NotificationType.INFO -> Sky400
}

@Composable
private fun filterChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = Slate900.copy(alpha = 0.55f),
    selectedContainerColor = Sky400.copy(alpha = 0.2f),
    labelColor = Slate400,
    selectedLabelColor = Sky400,
    iconColor = Slate400,
    selectedLeadingIconColor = Sky400
)

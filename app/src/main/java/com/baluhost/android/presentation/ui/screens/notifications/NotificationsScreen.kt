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
import androidx.compose.ui.unit.sp
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

    // Load more when near end of list
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && uiState.hasMore && !uiState.isLoading) {
            viewModel.loadMore()
        }
    }

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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        BaluBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Filter Chips
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
                    items(NotificationCategory.entries.toList()) { category ->
                        FilterChip(
                            selected = uiState.selectedCategory == category,
                            onClick = { viewModel.setCategory(category) },
                            label = { Text(categoryLabel(category)) },
                            leadingIcon = {
                                Icon(
                                    categoryIcon(category),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = filterChipColors(uiState.selectedCategory == category)
                        )
                    }
                }

                // Unread Only Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Nur ungelesene",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate400
                    )
                    Switch(
                        checked = uiState.unreadOnly,
                        onCheckedChange = { viewModel.toggleUnreadOnly() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Sky400,
                            checkedTrackColor = Slate800,
                            uncheckedThumbColor = Slate400,
                            uncheckedTrackColor = Slate800
                        )
                    )
                }

                // Notification List
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.notifications.isEmpty() && !uiState.isLoading) {
                        // Empty State
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
                                    "Keine Benachrichtigungen",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Slate400
                                )
                            }
                        }
                    } else {
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
                                    onMarkRead = { viewModel.markAsRead(notification.id) },
                                    onDismiss = { viewModel.dismiss(notification.id) },
                                    onSnooze = { viewModel.snooze(notification.id) }
                                )
                            }

                            if (uiState.isLoading && uiState.notifications.isNotEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Sky400,
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Initial loading
                    if (uiState.isLoading && uiState.notifications.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Sky400)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    notification: AppNotification,
    onMarkRead: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        intensity = GlassIntensity.Medium,
        onClick = {
            if (!notification.isRead) onMarkRead()
        }
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
                        imageVector = categoryIcon(notification.category),
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

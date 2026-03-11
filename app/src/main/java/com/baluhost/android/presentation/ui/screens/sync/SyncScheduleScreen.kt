package com.baluhost.android.presentation.ui.screens.sync

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.domain.model.sync.ScheduleType
import com.baluhost.android.domain.model.sync.SyncSchedule
import com.baluhost.android.presentation.ui.theme.*
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScheduleScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncScheduleViewModel = hiltViewModel()
) {
    val schedules by viewModel.schedules.collectAsState()
    val autoVpnEnabled by viewModel.autoVpnEnabled.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }

    // VPN consent launcher
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.setAutoVpn(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync-Zeitpläne") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück", tint = Sky400)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshFromServer() }) {
                        Icon(Icons.Default.Refresh, "Aktualisieren", tint = Sky400)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = Sky500,
                contentColor = Slate950
            ) {
                Icon(Icons.Default.Add, "Zeitplan erstellen")
            }
        },
        containerColor = Slate950
    ) { paddingValues ->
        SwipeRefresh(
            state = rememberSwipeRefreshState(isLoading),
            onRefresh = { viewModel.loadSchedules() }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // Error message
                errorMessage?.let { msg ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, null, tint = Red400, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(msg, color = Red400, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, "Schließen", tint = Slate400, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Auto-VPN Card
                item {
                    AutoVpnCard(
                        enabled = autoVpnEnabled,
                        onToggle = { enabled ->
                            if (enabled) {
                                // Check VPN consent first
                                val intent = VpnService.prepare(context)
                                if (intent != null) {
                                    vpnConsentLauncher.launch(intent)
                                } else {
                                    viewModel.setAutoVpn(true)
                                }
                            } else {
                                viewModel.setAutoVpn(false)
                            }
                        }
                    )
                }

                // Schedules header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ZEITPLÄNE",
                            color = Slate500,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "${schedules.count { it.enabled }} aktiv",
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    }
                }

                // Schedule list
                if (schedules.isEmpty() && !isLoading) {
                    item {
                        EmptyScheduleState()
                    }
                } else {
                    items(schedules, key = { it.scheduleId }) { schedule ->
                        ScheduleCard(
                            schedule = schedule,
                            onToggle = { viewModel.toggleSchedule(schedule.scheduleId, !schedule.enabled) },
                            onDelete = { viewModel.deleteSchedule(schedule.scheduleId) }
                        )
                    }
                }
            }
        }
    }

    // Create Dialog
    if (showCreateDialog) {
        CreateScheduleDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { type, time, dayOfWeek, dayOfMonth, autoVpn ->
                viewModel.createSchedule(type, time, dayOfWeek, dayOfMonth, autoVpn)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun AutoVpnCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Sky500.copy(alpha = 0.1f) else Slate900.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = if (enabled) {
            androidx.compose.foundation.BorderStroke(1.dp, Sky500.copy(alpha = 0.3f))
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, Slate700.copy(alpha = 0.5f))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VpnKey,
                contentDescription = null,
                tint = if (enabled) Sky400 else Slate500,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Auto-VPN für Sync",
                    color = Slate200,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "VPN automatisch verbinden vor jedem Sync",
                    color = Slate400,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Sky400,
                    checkedTrackColor = Sky500.copy(alpha = 0.3f),
                    uncheckedThumbColor = Slate500,
                    uncheckedTrackColor = Slate700
                )
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: SyncSchedule,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val typeLabel = when (schedule.scheduleType) {
        ScheduleType.DAILY -> "Täglich"
        ScheduleType.WEEKLY -> "Wöchentlich"
        ScheduleType.MONTHLY -> "Monatlich"
        ScheduleType.ON_CHANGE -> "Bei Änderung"
        ScheduleType.MANUAL -> "Manuell"
    }

    val dayLabel = buildString {
        schedule.timeOfDay?.let { append("um $it") }
        if (schedule.scheduleType == ScheduleType.WEEKLY && schedule.dayOfWeek != null) {
            val days = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
            val idx = if (schedule.dayOfWeek in 0..6) schedule.dayOfWeek else 0
            append(" · ${days[idx]}")
        }
        if (schedule.scheduleType == ScheduleType.MONTHLY && schedule.dayOfMonth != null) {
            append(" · ${schedule.dayOfMonth}.")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Slate900.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (schedule.enabled) Slate700.copy(alpha = 0.5f) else Slate700.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (schedule.enabled) Yellow400 else Slate500,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(typeLabel, color = Slate200, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (dayLabel.isNotEmpty()) {
                            Text(dayLabel, color = Slate400, fontSize = 12.sp)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Auto-VPN badge
                    if (schedule.autoVpn) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Sky500.copy(alpha = 0.15f))
                                .border(1.dp, Sky500.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("VPN", color = Sky400, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Status badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (schedule.enabled) Green500.copy(alpha = 0.15f)
                                else Slate700.copy(alpha = 0.5f)
                            )
                            .border(
                                1.dp,
                                if (schedule.enabled) Green500.copy(alpha = 0.3f)
                                else Slate600.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(onClick = onToggle)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (schedule.enabled) "Aktiv" else "Inaktiv",
                            color = if (schedule.enabled) Green400 else Slate400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Delete button
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Löschen", tint = Slate500, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Next run info
            schedule.nextRunAt?.let { nextRun ->
                if (nextRun > 0 && schedule.enabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccessTime, null, tint = Slate500, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Nächste Ausführung: ${formatTimestamp(nextRun)}",
                            color = Slate500,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Last run info
            schedule.lastRunAt?.let { lastRun ->
                if (lastRun > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = Slate600, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Zuletzt: ${formatTimestamp(lastRun)}",
                            color = Slate600,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScheduleState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = Slate600,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Keine Zeitpläne",
                color = Slate400,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Erstelle einen Zeitplan um Ordner automatisch zu synchronisieren",
                color = Slate500,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScheduleDialog(
    onDismiss: () -> Unit,
    onCreate: (ScheduleType, String, Int?, Int?, Boolean) -> Unit
) {
    var scheduleType by remember { mutableStateOf(ScheduleType.DAILY) }
    var timeHour by remember { mutableIntStateOf(2) }
    var timeMinute by remember { mutableIntStateOf(0) }
    var dayOfWeek by remember { mutableStateOf<Int?>(null) }
    var dayOfMonth by remember { mutableStateOf<Int?>(null) }
    var autoVpn by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var dayOfWeekExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate900,
        titleContentColor = Slate200,
        title = { Text("Neuer Zeitplan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Schedule Type Dropdown
                Text("Frequenz", color = Slate400, fontSize = 12.sp)
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = when (scheduleType) {
                            ScheduleType.DAILY -> "Täglich"
                            ScheduleType.WEEKLY -> "Wöchentlich"
                            ScheduleType.MONTHLY -> "Monatlich"
                            else -> "Täglich"
                        },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Sky400,
                            unfocusedBorderColor = Slate700,
                            focusedTextColor = Slate200,
                            unfocusedTextColor = Slate200
                        )
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        DropdownMenuItem(text = { Text("Täglich") }, onClick = { scheduleType = ScheduleType.DAILY; typeExpanded = false })
                        DropdownMenuItem(text = { Text("Wöchentlich") }, onClick = { scheduleType = ScheduleType.WEEKLY; typeExpanded = false })
                        DropdownMenuItem(text = { Text("Monatlich") }, onClick = { scheduleType = ScheduleType.MONTHLY; typeExpanded = false })
                    }
                }

                // Time Picker
                Text("Uhrzeit", color = Slate400, fontSize = 12.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = "%02d".format(timeHour),
                        onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) timeHour = it } },
                        modifier = Modifier.width(72.dp),
                        label = { Text("Std", fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Sky400,
                            unfocusedBorderColor = Slate700,
                            focusedTextColor = Slate200,
                            unfocusedTextColor = Slate200,
                            focusedLabelColor = Slate400,
                            unfocusedLabelColor = Slate500
                        )
                    )
                    Text(":", color = Slate400, fontSize = 20.sp)
                    OutlinedTextField(
                        value = "%02d".format(timeMinute),
                        onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..59) timeMinute = it } },
                        modifier = Modifier.width(72.dp),
                        label = { Text("Min", fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Sky400,
                            unfocusedBorderColor = Slate700,
                            focusedTextColor = Slate200,
                            unfocusedTextColor = Slate200,
                            focusedLabelColor = Slate400,
                            unfocusedLabelColor = Slate500
                        )
                    )
                }

                // Day of Week (for weekly)
                AnimatedVisibility(visible = scheduleType == ScheduleType.WEEKLY) {
                    Column {
                        Text("Wochentag", color = Slate400, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        ExposedDropdownMenuBox(expanded = dayOfWeekExpanded, onExpandedChange = { dayOfWeekExpanded = it }) {
                            val days = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
                            OutlinedTextField(
                                value = dayOfWeek?.let { days.getOrNull(it) } ?: "Wählen...",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dayOfWeekExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Sky400,
                                    unfocusedBorderColor = Slate700,
                                    focusedTextColor = Slate200,
                                    unfocusedTextColor = Slate200
                                )
                            )
                            ExposedDropdownMenu(expanded = dayOfWeekExpanded, onDismissRequest = { dayOfWeekExpanded = false }) {
                                days.forEachIndexed { index, day ->
                                    DropdownMenuItem(
                                        text = { Text(day) },
                                        onClick = { dayOfWeek = index; dayOfWeekExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }

                // Day of Month (for monthly)
                AnimatedVisibility(visible = scheduleType == ScheduleType.MONTHLY) {
                    Column {
                        Text("Tag im Monat", color = Slate400, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = dayOfMonth?.toString() ?: "",
                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 1..31) dayOfMonth = it } },
                            placeholder = { Text("1-31", color = Slate600) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Sky400,
                                unfocusedBorderColor = Slate700,
                                focusedTextColor = Slate200,
                                unfocusedTextColor = Slate200
                            )
                        )
                    }
                }

                // Auto-VPN toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Slate800.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VpnKey, null, tint = Slate400, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-VPN", color = Slate300, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoVpn,
                        onCheckedChange = { autoVpn = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Sky400,
                            checkedTrackColor = Sky500.copy(alpha = 0.3f),
                            uncheckedThumbColor = Slate500,
                            uncheckedTrackColor = Slate700
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val time = "%02d:%02d".format(timeHour, timeMinute)
                    onCreate(scheduleType, time, dayOfWeek, dayOfMonth, autoVpn)
                }
            ) {
                Text("Erstellen", color = Sky400)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = Slate400)
            }
        }
    )
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
    return sdf.format(Date(epochMs))
}

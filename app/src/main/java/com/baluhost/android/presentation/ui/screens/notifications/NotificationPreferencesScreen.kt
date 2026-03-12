package com.baluhost.android.presentation.ui.screens.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.GlassCard
import com.baluhost.android.presentation.ui.components.GlassIntensity
import com.baluhost.android.presentation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPreferencesScreen(
    onNavigateBack: () -> Unit,
    viewModel: NotificationPreferencesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error, duration = SnackbarDuration.Short)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Benachrichtigungs-Einstellungen",
                        color = Color.White
                    )
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { paddingValues ->
        BaluBackground {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Sky400)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Global Settings
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium
                    ) {
                        Text(
                            text = "ALLGEMEIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Push Notifications
                        SettingsToggle(
                            label = "Push-Benachrichtigungen",
                            description = "System-Benachrichtigungen empfangen",
                            checked = uiState.pushEnabled,
                            onCheckedChange = { viewModel.updatePushEnabled(it) }
                        )

                        HorizontalDivider(color = Slate700.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // In-App Notifications
                        SettingsToggle(
                            label = "In-App-Benachrichtigungen",
                            description = "Benachrichtigungen innerhalb der App anzeigen",
                            checked = uiState.inAppEnabled,
                            onCheckedChange = { viewModel.updateInAppEnabled(it) }
                        )
                    }

                    // Quiet Hours
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium
                    ) {
                        Text(
                            text = "RUHEZEITEN",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsToggle(
                            label = "Ruhezeiten aktivieren",
                            description = "Keine Benachrichtigungen während der Ruhezeit",
                            checked = uiState.quietHoursEnabled,
                            onCheckedChange = { viewModel.updateQuietHoursEnabled(it) }
                        )

                        if (uiState.quietHoursEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TimeField(
                                    label = "Von",
                                    value = uiState.quietHoursStart,
                                    onValueChange = { viewModel.updateQuietHoursStart(it) },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                TimeField(
                                    label = "Bis",
                                    value = uiState.quietHoursEnd,
                                    onValueChange = { viewModel.updateQuietHoursEnd(it) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Minimum Priority
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium
                    ) {
                        Text(
                            text = "MINDEST-PRIORITÄT",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = priorityLabel(uiState.minPriority),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = uiState.minPriority.toFloat(),
                            onValueChange = { viewModel.updateMinPriority(it.toInt()) },
                            valueRange = 0f..3f,
                            steps = 2,
                            colors = SliderDefaults.colors(
                                thumbColor = Sky400,
                                activeTrackColor = Sky400,
                                inactiveTrackColor = Slate700
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Alle", style = MaterialTheme.typography.labelSmall, color = Slate500)
                            Text("Nur kritisch", style = MaterialTheme.typography.labelSmall, color = Slate500)
                        }
                    }

                    // Category Preferences
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium
                    ) {
                        Text(
                            text = "KATEGORIE-EINSTELLUNGEN",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val categories = listOf(
                            "raid" to "RAID",
                            "smart" to "SMART",
                            "backup" to "Backup",
                            "scheduler" to "Scheduler",
                            "system" to "System",
                            "security" to "Sicherheit",
                            "sync" to "Sync",
                            "vpn" to "VPN"
                        )

                        categories.forEachIndexed { index, (key, label) ->
                            val pref = uiState.categoryPreferences[key]
                            val pushEnabled = pref?.push ?: true
                            val inAppEnabled = pref?.inApp ?: true

                            Column {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Checkbox(
                                            checked = pushEnabled,
                                            onCheckedChange = {
                                                viewModel.updateCategoryPreference(key, it, inAppEnabled)
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Sky400,
                                                uncheckedColor = Slate400
                                            )
                                        )
                                        Text("Push", style = MaterialTheme.typography.bodySmall, color = Slate400)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Checkbox(
                                            checked = inAppEnabled,
                                            onCheckedChange = {
                                                viewModel.updateCategoryPreference(key, pushEnabled, it)
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Sky400,
                                                uncheckedColor = Slate400
                                            )
                                        )
                                        Text("In-App", style = MaterialTheme.typography.bodySmall, color = Slate400)
                                    }
                                }
                            }

                            if (index < categories.size - 1) {
                                HorizontalDivider(color = Slate700.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Slate400
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Sky400,
                checkedTrackColor = Slate800,
                uncheckedThumbColor = Slate400,
                uncheckedTrackColor = Slate800
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            // Allow only HH:MM format
            if (newValue.matches(Regex("^\\d{0,2}:?\\d{0,2}$"))) {
                onValueChange(newValue)
            }
        },
        label = { Text(label, color = Slate400) },
        modifier = modifier,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Slate300,
            focusedBorderColor = Sky400,
            unfocusedBorderColor = Slate700,
            cursorColor = Sky400
        )
    )
}

private fun priorityLabel(priority: Int): String = when (priority) {
    0 -> "Alle Benachrichtigungen"
    1 -> "Mittel und höher"
    2 -> "Hoch und kritisch"
    3 -> "Nur kritisch"
    else -> "Alle Benachrichtigungen"
}

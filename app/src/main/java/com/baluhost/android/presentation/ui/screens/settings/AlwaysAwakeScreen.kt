package com.baluhost.android.presentation.ui.screens.settings

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
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.theme.*
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val PRESETS: List<Pair<String, Long?>> = listOf(
    "1 Std" to 1L,
    "4 Std" to 4L,
    "8 Std" to 8L,
    "Dauerhaft" to null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlwaysAwakeScreen(
    onNavigateBack: () -> Unit,
    viewModel: AlwaysAwakeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Always-Awake", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück", tint = Slate400)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Slate950
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Sky400) }

                uiState.loadError != null -> LoadErrorBlock(
                    message = uiState.loadError!!,
                    onRetry = { viewModel.load() }
                )

                else -> {
                    MasterSwitch(
                        enabled = uiState.enabled,
                        isSaving = uiState.isSaving,
                        onToggle = { on -> if (on) viewModel.setPreset(null) else viewModel.disable() }
                    )

                    StatusLine(enabled = uiState.enabled, until = uiState.until)

                    Text(
                        text = "Dauer",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        fontWeight = FontWeight.Medium
                    )
                    PresetRow(
                        isSaving = uiState.isSaving,
                        onPreset = { hours -> viewModel.setPreset(hours) }
                    )

                    TextButton(
                        onClick = { showDatePicker = true },
                        enabled = !uiState.isSaving
                    ) { Text("Zeitpunkt wählen…", color = Sky400) }

                    Text(
                        text = "Always-Awake hat Vorrang: solange es aktiv ist, greift keine " +
                            "automatische Schlafautomatik und kein Zeitplan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
        }
    }

    // Date first, then time — Material3 has no combined picker.
    if (showDatePicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") }
            }
        ) { DatePicker(state = state) }
    }

    pickedDate?.let { date ->
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            confirmButton = {
                TextButton(onClick = {
                    val local = LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute))
                    // The picker speaks the user's local time; the server wants UTC.
                    viewModel.setCustom(local.atZone(ZoneId.systemDefault()).toInstant())
                    pickedDate = null
                }) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { pickedDate = null }) { Text("Abbrechen") }
            },
            title = { Text("Uhrzeit", color = Color.White) },
            text = { TimePicker(state = timeState) },
            containerColor = Slate900
        )
    }
}

@Composable
private fun MasterSwitch(enabled: Boolean, isSaving: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Always-Awake", style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(
                "Server wach halten",
                style = MaterialTheme.typography.bodySmall,
                color = Slate400
            )
        }
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Sky400, strokeWidth = 2.dp)
        } else {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PresetRow(isSaving: Boolean, onPreset: (Long?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESETS.forEach { (label, hours) ->
            OutlinedButton(onClick = { onPreset(hours) }, enabled = !isSaving) {
                Text(label)
            }
        }
    }
}

@Composable
private fun LoadErrorBlock(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Orange500)
        TextButton(onClick = onRetry) { Text("Erneut versuchen", color = Sky400) }
    }
}

/** Recomputes once a second so the remaining time stays honest while the screen is open. */
@Composable
private fun StatusLine(enabled: Boolean, until: Instant?) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(enabled, until) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    val text = when {
        !enabled -> "Aus — die Schlafautomatik greift normal."
        until == null -> "Dauerhaft aktiv"
        !until.isAfter(now) -> "Abgelaufen"
        else -> "Noch ${formatRemaining(Duration.between(now, until))} — bis ${formatUntil(until)}"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Green500 else Slate400)
}

private fun formatRemaining(d: Duration): String {
    val days = d.toDays()
    val hours = d.toHours() % 24
    val minutes = d.toMinutes() % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun formatUntil(until: Instant): String {
    val local = until.atZone(ZoneId.systemDefault())
    val today = LocalDate.now(ZoneId.systemDefault())
    return if (local.toLocalDate() == today) {
        local.format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        local.format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
    }
}

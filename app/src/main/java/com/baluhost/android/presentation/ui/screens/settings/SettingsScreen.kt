package com.baluhost.android.presentation.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.GlassCard
import com.baluhost.android.presentation.ui.components.GlassIntensity
import com.baluhost.android.presentation.ui.components.GradientButton
import com.baluhost.android.presentation.ui.components.defaultGradient
import com.baluhost.android.presentation.ui.components.errorGradient
import com.baluhost.android.util.ByteUnitMode
import com.baluhost.android.presentation.ui.screens.vpn.VpnViewModel
import com.baluhost.android.presentation.ui.theme.*

/**
 * Settings screen with device management options.
 * Dark glassmorphism design matching webapp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSplash: () -> Unit,
    onNavigateToNotificationPreferences: () -> Unit = {},
    onNavigateToFritzBox: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    vpnViewModel: VpnViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val vpnUiState by vpnViewModel.uiState.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinSetupError by remember { mutableStateOf<String?>(null) }

    // BSSID permission for "Set Home Network"
    val bssidPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setHomeNetwork()
        }
    }
    var showBssidRationale by remember { mutableStateOf(false) }
    val bssidPermission = Manifest.permission.ACCESS_FINE_LOCATION

    // Handle successful deletion - navigate to splash for re-onboarding
    LaunchedEffect(uiState.deviceDeleted) {
        if (uiState.deviceDeleted) {
            onNavigateToSplash()
        }
    }

    // Show error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Einstellungen",
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Section: Konto ──
                SectionHeader(text = "Konto")

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "BENUTZERINFORMATIONEN",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    InfoRow(label = "Benutzername", value = uiState.username)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(label = "Server", value = uiState.serverUrl)
                    uiState.deviceId?.let { deviceId ->
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow(
                            label = "Geräte-ID",
                            value = deviceId.take(8) + "..."
                        )
                    }
                }

                // ── Section: Sicherheit ──
                SectionHeader(text = "Sicherheit")

                SecurityCard(
                    uiState = uiState,
                    onToggleBiometric = viewModel::toggleBiometric,
                    onSetupPin = { showPinDialog = true },
                    onRemovePin = viewModel::removePin,
                    onToggleAppLock = viewModel::toggleAppLock,
                    onSetLockTimeout = viewModel::setLockTimeout
                )

                // ── Section: Netzwerk & VPN ──
                SectionHeader(text = "Netzwerk & VPN")

                // Network Settings Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "NETZWERK",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Home network status
                    InfoRow(
                        label = "Heimnetzwerk",
                        value = if (uiState.homeBssidConfigured) "Konfiguriert" else "Nicht konfiguriert"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val context = LocalContext.current
                    GradientButton(
                        onClick = {
                            // Check if permission is already granted
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, bssidPermission
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                viewModel.setHomeNetwork()
                            } else {
                                showBssidRationale = true
                            }
                        },
                        text = "Heimnetzwerk setzen",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.isOnWifi
                    )

                    if (!uiState.isOnWifi) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Verbinde dich mit deinem Heim-WLAN um das Netzwerk zu setzen",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Slate700.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto-VPN toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-VPN wenn extern",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = "VPN automatisch verbinden wenn nicht im Heimnetzwerk",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                        Switch(
                            checked = uiState.autoVpnOnExternal,
                            onCheckedChange = { viewModel.toggleAutoVpn(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Sky400,
                                checkedTrackColor = Slate800,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }
                }

                // VPN Settings Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "VPN-EINSTELLUNGEN",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // VPN Status Card (inner glass)
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Light,
                        padding = PaddingValues(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Status:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = if (vpnUiState.isConnected) "Verbunden" else "Getrennt",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (vpnUiState.isConnected) Green500 else Red500,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        vpnUiState.clientIp?.let { ip ->
                            Spacer(modifier = Modifier.height(8.dp))
                            InfoRow(label = "Lokale IP", value = ip)
                        }

                        vpnUiState.serverEndpoint?.let { endpoint ->
                            Spacer(modifier = Modifier.height(8.dp))
                            InfoRow(label = "Server", value = endpoint)
                        }

                        vpnUiState.deviceName?.let { deviceName ->
                            Spacer(modifier = Modifier.height(8.dp))
                            InfoRow(label = "Gerätename", value = deviceName)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // VPN Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GradientButton(
                            onClick = {
                                if (vpnUiState.isConnected) {
                                    vpnViewModel.disconnect()
                                } else {
                                    vpnViewModel.connect()
                                }
                            },
                            text = when {
                                vpnUiState.isLoading && vpnUiState.isConnected -> "Trennen..."
                                vpnUiState.isLoading -> "Verbinde..."
                                vpnUiState.isConnected -> "Trennen"
                                else -> "Verbinden"
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !vpnUiState.isLoading && vpnUiState.hasConfig,
                            gradient = if (vpnUiState.isConnected) errorGradient() else defaultGradient()
                        )

                        GradientButton(
                            onClick = { vpnViewModel.refreshConfig() },
                            text = "Aktualisieren",
                            modifier = Modifier.weight(1f),
                            enabled = !vpnUiState.isLoading,
                            gradient = com.baluhost.android.presentation.ui.components.secondaryGradient()
                        )
                    }

                    // VPN Error Message
                    vpnUiState.error?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Fehler: $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = Red500,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Fritz!Box Settings Card (admin only)
                if (isAdmin) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium,
                        onClick = onNavigateToFritzBox
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "FRITZ!BOX",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate500,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Wake-on-LAN Konfiguration",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate400
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Öffnen",
                                tint = Sky400
                            )
                        }
                    }
                }

                // ── Section: App-Einstellungen ──
                SectionHeader(text = "App-Einstellungen")

                // Notification Settings Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium,
                    onClick = onNavigateToNotificationPreferences
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "BENACHRICHTIGUNGEN",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate500,
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Push, In-App und Ruhezeiten konfigurieren",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Slate400
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Öffnen",
                            tint = Sky400
                        )
                    }
                }

                // Cache Management Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "CACHE-VERWALTUNG",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Cache Stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gecachte Dateien",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Slate400
                            )
                            Text(
                                text = "${uiState.cacheFileCount} Dateien",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate100
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "Älteste Datei",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Slate400
                            )
                            Text(
                                text = if (uiState.cacheOldestAgeDays != null) {
                                    "${uiState.cacheOldestAgeDays} Tage"
                                } else {
                                    "Keine Daten"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate100
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Der Cache wird automatisch bereinigt wenn er älter als 7 Tage ist oder mehr als 1000 Dateien enthält.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    GradientButton(
                        onClick = { viewModel.clearCache() },
                        text = if (uiState.isClearingCache) "Cache wird geleert..." else "Cache jetzt leeren",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isClearingCache && uiState.cacheFileCount > 0
                    )
                }

                // Byte Unit Mode Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "BYTE-EINHEIT",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Binary option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setByteUnitMode(ByteUnitMode.BINARY) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.byteUnitMode == ByteUnitMode.BINARY,
                            onClick = { viewModel.setByteUnitMode(ByteUnitMode.BINARY) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Sky400,
                                unselectedColor = Slate400
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Binär (KiB / MiB / GiB)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = "1 GiB = 1024 MiB",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                    }

                    HorizontalDivider(color = Slate700.copy(alpha = 0.5f))

                    // Decimal option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setByteUnitMode(ByteUnitMode.DECIMAL) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.byteUnitMode == ByteUnitMode.DECIMAL,
                            onClick = { viewModel.setByteUnitMode(ByteUnitMode.DECIMAL) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Sky400,
                                unselectedColor = Slate400
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Dezimal (KB / MB / GB)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = "1 GB = 1000 MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                    }
                }

                // ── Section: Gefahrenzone ──
                Spacer(modifier = Modifier.height(8.dp))

                // Danger Zone Card (red-tinted glass)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Red500.copy(alpha = 0.1f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Red500.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "GEFAHRENZONE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Red500,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = "Das Entfernen dieses Geräts wird die Verbindung zum Server beenden und alle lokalen Daten löschen. Diese Aktion kann nicht rückgängig gemacht werden.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )

                        GradientButton(
                            onClick = { showDeleteDialog = true },
                            text = if (uiState.isDeleting) "Entferne Gerät..." else "Gerät entfernen",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isDeleting,
                            gradient = errorGradient()
                        )
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Gerät entfernen?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Möchtest du dieses Gerät wirklich von BaluHost entfernen?\n\n" +
                            "\u2022 Die Verbindung zum Server wird beendet\n" +
                            "\u2022 Alle lokalen Daten werden gelöscht\n" +
                            "\u2022 Du musst das Gerät erneut registrieren, um es wieder zu verwenden\n\n" +
                            "Diese Aktion kann nicht rückgängig gemacht werden."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteDevice()
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Red500
                    )
                ) {
                    Text("Entfernen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                ) {
                    Text("Abbrechen")
                }
            },
            containerColor = Slate900
        )
    }

    // PIN Setup Dialog
    if (showPinDialog) {
        PinSetupDialog(
            onDismiss = {
                showPinDialog = false
                pinSetupError = null
            },
            onConfirm = { pin ->
                viewModel.setupPin(
                    pin = pin,
                    onSuccess = {
                        showPinDialog = false
                        pinSetupError = null
                    },
                    onError = { error ->
                        pinSetupError = error
                    }
                )
            }
        )
    }

    // Show PIN setup error if any
    LaunchedEffect(pinSetupError) {
        pinSetupError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
        }
    }

    // BSSID permission rationale
    if (showBssidRationale) {
        AlertDialog(
            onDismissRequest = { showBssidRationale = false },
            title = { Text("WLAN-Erkennung") },
            text = {
                Text(
                    "BaluApp möchte dein Heimnetzwerk (WLAN) erkennen, um automatisch " +
                    "die richtige Verbindung zu wählen. Dazu wird einmalig der " +
                    "WLAN-Zugangspunkt identifiziert. Dein Standort wird nicht " +
                    "gespeichert oder übertragen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBssidRationale = false
                    bssidPermissionLauncher.launch(bssidPermission)
                }) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = { showBssidRationale = false }) {
                    Text("Überspringen")
                }
            },
            containerColor = Slate900
        )
    }
}

@Composable
private fun SecurityCard(
    uiState: SettingsUiState,
    onToggleBiometric: (Boolean) -> Unit,
    onSetupPin: () -> Unit,
    onRemovePin: () -> Unit,
    onToggleAppLock: (Boolean) -> Unit,
    onSetLockTimeout: (Int) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        intensity = GlassIntensity.Medium
    ) {
        Text(
            text = "SICHERHEIT",
            style = MaterialTheme.typography.labelSmall,
            color = Slate500,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Biometric Authentication
        if (uiState.biometricAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Biometrische Entsperrung",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Text(
                        text = "Fingerabdruck oder Gesichtserkennung",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
                Switch(
                    checked = uiState.biometricEnabled,
                    onCheckedChange = onToggleBiometric,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Sky400,
                        checkedTrackColor = Slate800,
                        uncheckedThumbColor = Slate400,
                        uncheckedTrackColor = Slate800
                    )
                )
            }

            HorizontalDivider(color = Slate700.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))
        }

        // PIN Setup
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = if (uiState.pinConfigured) "PIN konfiguriert" else "Keine PIN festgelegt",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
            TextButton(
                onClick = if (uiState.pinConfigured) onRemovePin else onSetupPin
            ) {
                Text(
                    if (uiState.pinConfigured) "Entfernen" else "Einrichten",
                    color = Sky400
                )
            }
        }

        HorizontalDivider(color = Slate700.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(12.dp))

        // App Lock
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Automatische Sperre",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = "App nach Zeitüberschreitung sperren",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
            Switch(
                checked = uiState.appLockEnabled,
                onCheckedChange = onToggleAppLock,
                enabled = uiState.biometricEnabled || uiState.pinConfigured,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Sky400,
                    checkedTrackColor = Slate800,
                    uncheckedThumbColor = Slate400,
                    uncheckedTrackColor = Slate800
                )
            )
        }

        // Lock Timeout Slider
        if (uiState.appLockEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Sperrzeit: ${uiState.lockTimeoutMinutes} Minuten",
                style = MaterialTheme.typography.bodySmall,
                color = Slate400
            )
            Slider(
                value = uiState.lockTimeoutMinutes.toFloat(),
                onValueChange = { onSetLockTimeout(it.toInt()) },
                valueRange = 1f..30f,
                steps = 28,
                colors = SliderDefaults.colors(
                    thumbColor = Sky400,
                    activeTrackColor = Sky400,
                    inactiveTrackColor = Slate700
                )
            )
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Slate400,
        modifier = modifier.padding(top = 8.dp)
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Slate100
        )
    }
}

package com.baluhost.android.presentation.ui.screens.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.GlassCard
import com.baluhost.android.presentation.ui.components.GlassIntensity
import com.baluhost.android.presentation.ui.components.GradientButton
import com.baluhost.android.presentation.ui.components.defaultGradient
import com.baluhost.android.presentation.ui.components.errorGradient
import com.baluhost.android.presentation.ui.theme.*
import com.baluhost.android.service.vpn.BaluHostVpnService

/**
 * VPN Screen - VPN connection management with dark glassmorphism design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnScreen(
    onNavigateBack: () -> Unit,
    viewModel: VpnViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            BaluHostVpnService.clearPermissionRequest()
            viewModel.connect()
        }
    }

    // Observe permission request from VPN service (Samsung workaround)
    val permissionIntent by BaluHostVpnService.needsPermission.collectAsState()
    LaunchedEffect(permissionIntent) {
        permissionIntent?.let { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "VPN Connection",
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Slate400
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshConfig() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Config",
                            tint = Slate400
                        )
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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // VPN Status Icon
                Icon(
                    imageVector = if (uiState.isConnected) Icons.Default.Lock else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = if (uiState.isConnected) Green500 else Slate400
                )

                // Status Text
                Text(
                    text = when {
                        uiState.isConnected -> "Connected"
                        uiState.isLoading -> "Connecting..."
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (uiState.isConnected) Green500 else Color.White
                )

                // VPN Type Selector
                if (uiState.availableTypes.size > 1) {
                    val typeLabels = mapOf(
                        "fritzbox" to "FritzBox",
                        "wireguard" to "WireGuard"
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        uiState.availableTypes.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = type == uiState.selectedType,
                                onClick = { viewModel.onTypeSelected(type) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = uiState.availableTypes.size
                                ),
                                enabled = !uiState.isLoading && !uiState.isConnected,
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = Slate700,
                                    activeContentColor = Color.White,
                                    inactiveContainerColor = Slate900.copy(alpha = 0.5f),
                                    inactiveContentColor = Slate400,
                                    disabledActiveContainerColor = Slate700.copy(alpha = 0.5f),
                                    disabledActiveContentColor = Slate400,
                                    disabledInactiveContainerColor = Slate900.copy(alpha = 0.3f),
                                    disabledInactiveContentColor = Slate500
                                ),
                                border = SegmentedButtonDefaults.borderStroke(
                                    color = Slate600.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = typeLabels[type] ?: type.replaceFirstChar { it.uppercase() },
                                    fontWeight = if (type == uiState.selectedType) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Connection Info Card
                if (uiState.hasConfig) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium
                    ) {
                        Text(
                            text = "CONNECTION DETAILS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Slate700.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        uiState.deviceName?.let { name ->
                            InfoRow(label = "Device", value = name)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        uiState.serverEndpoint?.let { endpoint ->
                            InfoRow(label = "Server", value = endpoint)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        uiState.clientIp?.let { ip ->
                            InfoRow(label = "Local IP", value = ip)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        InfoRow(
                            label = "Status",
                            value = if (uiState.isConnected) "Active" else "Inactive"
                        )

                        if (uiState.isConnected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            InfoRow(label = "Protocol", value = "WireGuard (UDP)")
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Error Message
                if (uiState.error != null) {
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
                        val errorMessage = uiState.error
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage,
                                modifier = Modifier.padding(16.dp),
                                color = Red400,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Connect/Disconnect Button
                GradientButton(
                    onClick = {
                        if (uiState.isConnected) {
                            viewModel.disconnect()
                        } else {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnPermissionLauncher.launch(intent)
                            } else {
                                viewModel.connect()
                            }
                        }
                    },
                    text = when {
                        uiState.isLoading -> "..."
                        uiState.isConnected -> "Disconnect"
                        else -> "Connect"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = uiState.hasConfig && !uiState.isLoading,
                    gradient = if (uiState.isConnected) errorGradient() else defaultGradient()
                )

                // Help Text
                if (!uiState.hasConfig) {
                    Text(
                        text = "No VPN configuration found. Please scan a QR code to register this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
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

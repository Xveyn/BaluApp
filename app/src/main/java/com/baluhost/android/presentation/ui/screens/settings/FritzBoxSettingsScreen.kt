package com.baluhost.android.presentation.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.GlassCard
import com.baluhost.android.presentation.ui.components.GlassIntensity
import com.baluhost.android.presentation.ui.components.GradientButton
import com.baluhost.android.presentation.ui.components.defaultGradient
import com.baluhost.android.presentation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FritzBoxSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: FritzBoxSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Fritz!Box", color = Color.White)
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
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Slate800,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp)
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Description
                Text(
                    text = "Konfiguriere die Fritz!Box-Verbindung für direktes Wake-on-LAN, wenn das NAS schläft.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate400
                )

                // Connection Config Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "VERBINDUNG",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.host,
                        onValueChange = { viewModel.updateHost(it) },
                        label = { Text("Host") },
                        placeholder = { Text("192.168.178.1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fritzBoxTextFieldColors()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.port,
                        onValueChange = { viewModel.updatePort(it) },
                        label = { Text("Port") },
                        placeholder = { Text("49000") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fritzBoxTextFieldColors()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.username,
                        onValueChange = { viewModel.updateUsername(it) },
                        label = { Text("Benutzername") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fritzBoxTextFieldColors()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = { viewModel.updatePassword(it) },
                        label = {
                            Text(if (uiState.hasPassword) "Passwort (gespeichert)" else "Passwort")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = Slate400
                                )
                            }
                        },
                        colors = fritzBoxTextFieldColors()
                    )
                }

                // WoL Target Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "WAKE-ON-LAN ZIEL",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.macAddress,
                        onValueChange = { viewModel.updateMacAddress(it) },
                        label = { Text("NAS MAC-Adresse") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fritzBoxTextFieldColors()
                    )
                }

                // Actions
                GradientButton(
                    onClick = { viewModel.testConnection() },
                    text = if (uiState.isTesting) "Teste Verbindung..." else "Verbindung testen",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isTesting
                )

                GradientButton(
                    onClick = { viewModel.save() },
                    text = "Speichern",
                    modifier = Modifier.fillMaxWidth(),
                    gradient = defaultGradient()
                )
            }
        }
    }
}

@Composable
private fun fritzBoxTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Slate300,
    focusedBorderColor = Sky400,
    unfocusedBorderColor = Slate600,
    focusedLabelColor = Sky400,
    unfocusedLabelColor = Slate400,
    cursorColor = Sky400,
    focusedPlaceholderColor = Slate500,
    unfocusedPlaceholderColor = Slate600
)

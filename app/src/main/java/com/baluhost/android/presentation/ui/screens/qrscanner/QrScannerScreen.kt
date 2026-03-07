package com.baluhost.android.presentation.ui.screens.qrscanner

import android.Manifest
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * QR Scanner Screen using CameraX and ML Kit.
 * 
 * Scans QR codes for device registration.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onNavigateToFiles: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QrScannerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    // Navigate to files on successful registration
    LaunchedEffect(uiState) {
        if (uiState is QrScannerState.Success) {
            // Short delay to show success message if VPN was configured
            val state = uiState as QrScannerState.Success
            if (state.vpnConfigured) {
                kotlinx.coroutines.delay(2000) // Show VPN success message for 2s
            }
            onNavigateToFiles()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    if (uiState !is QrScannerState.Processing) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Zurück",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                !cameraPermissionState.status.isGranted -> {
                    // Camera permission not granted
                    CameraPermissionRequest(
                        onRequestPermission = {
                            cameraPermissionState.launchPermissionRequest()
                        }
                    )
                }
                uiState is QrScannerState.Scanning -> {
                    // Show camera preview with manual input fallback
                    ScanningContent(
                        onQrCodeScanned = viewModel::onQrCodeScanned
                    )
                }
                uiState is QrScannerState.Processing || uiState is QrScannerState.Success -> {
                    // Processing registration or Success with VPN configured
                    ProcessingOverlay(
                        isSuccess = uiState is QrScannerState.Success,
                        vpnConfigured = (uiState as? QrScannerState.Success)?.vpnConfigured ?: false
                    )
                }
                uiState is QrScannerState.Error -> {
                    // Show error
                    ErrorOverlay(
                        message = (uiState as QrScannerState.Error).message,
                        onRetry = viewModel::resetScanner
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionRequest(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Camera access is needed to scan QR codes for device registration",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onRequestPermission,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Grant Permission")
        }
    }
}

@Composable
private fun ScanningContent(
    onQrCodeScanned: (String) -> Unit
) {
    var cameraError by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraError) {
            CameraErrorFallback(
                onManualInput = { showManualInput = true }
            )
        } else {
            CameraPreview(
                onQrCodeScanned = onQrCodeScanned,
                onCameraError = { cameraError = true }
            )
        }

        // "Manuell eingeben" button — always visible at the bottom
        if (!cameraError) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "Point camera at QR code",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { showManualInput = true }) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Server-URL manuell eingeben")
                }
            }
        }
    }

    if (showManualInput) {
        ManualInputDialog(
            onDismiss = { showManualInput = false },
            onConnect = { serverUrl, token ->
                showManualInput = false
                val json = JSONObject().apply {
                    put("server", serverUrl)
                    put("token", token)
                }.toString()
                onQrCodeScanned(json)
            }
        )
    }
}

@Composable
private fun CameraPreview(
    onQrCodeScanned: (String) -> Unit,
    onCameraError: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var hasScanned by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val barcodeScanner = BarcodeScanning.getClient()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !hasScanned) {
                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )

                        barcodeScanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                        barcode.rawValue?.let { qrData ->
                                            hasScanned = true
                                            onQrCodeScanned(qrData)
                                        }
                                        break
                                    }
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    onCameraError()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun CameraErrorFallback(
    onManualInput: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Kamera nicht verfügbar",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Die Kamera konnte nicht gestartet werden. Du kannst die Server-Daten auch manuell eingeben.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onManualInput,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Server-URL manuell eingeben")
        }
    }
}

@Composable
private fun ManualInputDialog(
    onDismiss: () -> Unit,
    onConnect: (serverUrl: String, token: String) -> Unit
) {
    var serverUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server manuell verbinden") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Gib die Server-URL und das Registrierungs-Token ein, die du vom Server-Admin erhalten hast.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server-URL") },
                    placeholder = { Text("https://server.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Registrierungs-Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(serverUrl.trim(), token.trim()) },
                enabled = serverUrl.isNotBlank() && token.isNotBlank(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Verbinden")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            ) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
private fun ProcessingOverlay(
    isSuccess: Boolean = false,
    vpnConfigured: Boolean = false
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isSuccess) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Registrierung erfolgreich",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (vpnConfigured) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✓ VPN konfiguriert",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Gerät wird registriert...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Registration Failed",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onRetry,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Try Again")
                }
            }
        }
    }
}

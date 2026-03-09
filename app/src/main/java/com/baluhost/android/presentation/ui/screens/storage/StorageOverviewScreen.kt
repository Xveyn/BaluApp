package com.baluhost.android.presentation.ui.screens.storage

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StorageOverviewScreen(
    onNavigateToFiles: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Speicher-Einrichtung abgeschlossen",
            style = MaterialTheme.typography.headlineSmall,
            color = androidx.compose.ui.graphics.Color.White
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onNavigateToFiles,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = com.baluhost.android.presentation.ui.theme.Sky500,
                contentColor = androidx.compose.ui.graphics.Color.White
            )
        ) {
            Text("Weiter zu Dateien")
        }
    }
}

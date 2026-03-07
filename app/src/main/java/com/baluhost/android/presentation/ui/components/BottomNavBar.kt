package com.baluhost.android.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.baluhost.android.presentation.ui.theme.Sky400
import com.baluhost.android.presentation.ui.theme.Slate400
import com.baluhost.android.presentation.ui.theme.Slate800
import com.baluhost.android.presentation.ui.theme.Slate900

/**
 * Bottom Navigation Bar with dark glassmorphism styling matching webapp header.
 */
@Composable
fun BottomNavBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    Column {
        HorizontalDivider(
            thickness = 1.dp,
            color = Slate800.copy(alpha = 0.5f)
        )
        NavigationBar(
            containerColor = Slate900.copy(alpha = 0.85f),
            tonalElevation = 0.dp
        ) {
            BottomNavItem.entries.forEach { item ->
                NavigationBarItem(
                    selected = currentRoute.startsWith(item.route),
                    onClick = { onNavigate(item.route) },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label
                        )
                    },
                    label = { Text(item.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Sky400,
                        selectedTextColor = Sky400,
                        unselectedIconColor = Slate400,
                        unselectedTextColor = Slate400,
                        indicatorColor = Sky400.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }
}

enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("dashboard", "Home", Icons.Default.Home),
    FILES("files", "Dateien", Icons.Default.Folder),
    SYNC("sync", "Sync", Icons.Default.Sync),
    SETTINGS("settings", "Einstellungen", Icons.Default.Settings)
}

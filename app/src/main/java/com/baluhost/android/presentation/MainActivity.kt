package com.baluhost.android.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.local.security.AppLockManager
import com.baluhost.android.data.notification.NotificationWebSocketManager
import com.baluhost.android.data.notification.ServerConnectionService
import com.baluhost.android.presentation.navigation.NavGraph
import com.baluhost.android.presentation.navigation.Screen
import com.baluhost.android.presentation.ui.theme.BaluHostTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main activity for the BaluHost app.
 * Handles notification deep links, intent routing, and app lock lifecycle.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var notificationWebSocketManager: NotificationWebSocketManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize app logger
        com.baluhost.android.util.Logger.init(applicationContext)

        // Start foreground notification service and WebSocket if user is logged in
        lifecycleScope.launch {
            val serverUrl = preferencesManager.getServerUrl().first()
            if (serverUrl != null) {
                Log.d(TAG, "User is connected, starting ServerConnectionService")
                ServerConnectionService.start(this@MainActivity)

                // Connect notification WebSocket
                try {
                    notificationWebSocketManager.connect()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to connect notification WebSocket", e)
                }
            }
        }

        setContent {
            BaluHostTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var initialRoute by remember { mutableStateOf<String?>(null) }
                    var shouldShowLock by remember { mutableStateOf(false) }
                    val lifecycleOwner = LocalLifecycleOwner.current
                    
                    // Check lock screen on every start (not resume, to avoid false triggers from notification shade)
                    // Also refresh sync schedules on resume
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_START) {
                                lifecycleScope.launch {
                                    val showLock = appLockManager.shouldShowLockScreen()
                                    if (showLock) {
                                        Log.d(TAG, "App lock check: showing lock screen")
                                        shouldShowLock = true
                                    } else {
                                        Log.d(TAG, "App lock check: no lock needed")
                                    }

                                    // Reconnect WebSocket when coming back from background
                                    val serverUrl = preferencesManager.getServerUrl().first()
                                    if (serverUrl != null) {
                                        try {
                                            notificationWebSocketManager.connect()
                                        } catch (e: Exception) {
                                            Log.w(TAG, "WebSocket reconnect failed", e)
                                        }
                                    }
                                }
                            }
                            if (event == Lifecycle.Event.ON_STOP) {
                                // Disconnect WebSocket when app goes to background (FCM takes over)
                                notificationWebSocketManager.disconnect()
                            }
                            if (event == Lifecycle.Event.ON_RESUME) {
                                com.baluhost.android.data.worker.SyncScheduleWorkScheduler
                                    .triggerImmediateRefresh(this@MainActivity)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }
                    
                    // Handle initial intent on first composition
                    LaunchedEffect(Unit) {
                        initialRoute = handleIntent(intent)
                    }
                    
                    // Navigate to lock screen if needed
                    LaunchedEffect(shouldShowLock) {
                        if (shouldShowLock) {
                            navController.navigate(Screen.Lock.route) {
                                launchSingleTop = true
                            }
                            shouldShowLock = false
                        }
                    }
                    
                    // Navigate to initial route if provided
                    LaunchedEffect(initialRoute) {
                        initialRoute?.let { route ->
                            navController.navigate(route) {
                                // Clear back stack if coming from notification
                                popUpTo(navController.graph.startDestinationId) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                    
                    NavGraph(navController = navController)
                }
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        // Record timestamp when app goes to background
        lifecycleScope.launch {
            appLockManager.onAppBackground()
            Log.d(TAG, "App moved to background, recording timestamp")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Intent will be handled on next recomposition
    }
    
    /**
     * Handle incoming intents from notifications and deep links.
     * Returns the navigation route to open, or null if no special handling needed.
     */
    private fun handleIntent(intent: Intent?): String? {
        if (intent == null) return null
        
        val notificationType = intent.getStringExtra("notification_type")
        val action = intent.getStringExtra("action")
        val deepLink = intent.getStringExtra("deep_link")
        
        Log.d(TAG, "Handling intent: type=$notificationType, action=$action, deepLink=$deepLink")
        
        // Handle notification intents
        when (notificationType) {
            "expiration_warning" -> {
                Log.i(TAG, "Opening device settings for expiration warning")
                return Screen.Settings.route
            }
            "device_removed" -> {
                Log.i(TAG, "Device was removed, navigating to re-registration")
                return Screen.QrScanner.route
            }
            "backend_notification" -> {
                val actionUrl = intent.getStringExtra("action_url")
                Log.i(TAG, "Backend notification tapped, action_url=$actionUrl")
                return when {
                    actionUrl?.contains("/files") == true -> Screen.Main.route
                    actionUrl?.contains("/sync") == true -> Screen.Main.route
                    actionUrl?.contains("/raid") == true -> Screen.Main.route
                    else -> Screen.Notifications.route
                }
            }
        }

        // Handle action intents
        when (action) {
            "renew_device" -> {
                Log.i(TAG, "Opening device renewal flow")
                return Screen.QrScanner.route
            }
        }
        
        // Handle deep links (baluhost:// scheme)
        if (intent.action == Intent.ACTION_VIEW) {
            val uri: Uri? = intent.data
            if (uri != null) {
                Log.i(TAG, "Handling deep link: $uri")
                return handleDeepLink(uri)
            }
        }
        
        return null
    }
    
    /**
     * Parse deep link URI and return navigation route.
     * Supports:
     * - baluhost://files -> File browser
     * - baluhost://settings -> Settings screen
     * - baluhost://device_settings -> Device settings
     * - baluhost://scan -> QR code scanner
     */
    private fun handleDeepLink(uri: Uri): String? {
        return when (uri.host) {
            "files" -> {
                Log.d(TAG, "Deep link to files")
                Screen.Main.route
            }
            "settings" -> {
                Log.d(TAG, "Deep link to settings")
                Screen.Main.route
            }
            "device_settings" -> {
                Log.d(TAG, "Deep link to device settings")
                Screen.Main.route
            }
            "scan" -> {
                Log.d(TAG, "Deep link to QR scanner")
                Screen.QrScanner.route
            }
            else -> {
                Log.w(TAG, "Unknown deep link host: ${uri.host}")
                null
            }
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}

package com.baluhost.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.worker.OfflineQueueWorkScheduler
import com.baluhost.android.data.worker.SyncScheduleWorkScheduler
import com.baluhost.android.util.ByteFormatter
import com.baluhost.android.util.ByteUnitMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BaluHost Application class with Hilt dependency injection.
 * 
 * Responsibilities:
 * - Initialize Hilt for app-wide DI
 * - Configure WorkManager with Hilt
 * - Setup global application state
 * - Configure Coil ImageLoader with authentication
 * - Schedule background workers for offline queue
 */
@HiltAndroidApp
class BaluHostApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        
        // Schedule offline queue background workers
        OfflineQueueWorkScheduler.schedulePeriodicRetry(this)
        OfflineQueueWorkScheduler.scheduleDailyCleanup(this)
        
        // Schedule cache cleanup worker (daily, LRU + age-based)
        OfflineQueueWorkScheduler.scheduleCacheCleanup(this)

        // Schedule periodic sync schedule refresh (every 30 minutes)
        SyncScheduleWorkScheduler.schedulePeriodicRefresh(this)

        // Create notification channels for backend alerts
        createNotificationChannels()

        // Load byte unit mode preference
        applicationScope.launch {
            val mode = preferencesManager.getByteUnitMode().first()
            ByteFormatter.mode = if (mode == "decimal") ByteUnitMode.DECIMAL else ByteUnitMode.BINARY
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channels = listOf(
                NotificationChannel(
                    "alerts_critical",
                    "Kritische Alarme",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Kritische Benachrichtigungen vom Server"
                    enableVibration(true)
                    enableLights(true)
                },
                NotificationChannel(
                    "alerts_warning",
                    "Warnungen",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Warnmeldungen vom Server"
                },
                NotificationChannel(
                    "alerts_info",
                    "Informationen",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Informative Benachrichtigungen vom Server"
                }
            )

            channels.forEach { manager.createNotificationChannel(it) }
        }
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
    
    override fun newImageLoader(): ImageLoader {
        return imageLoader
    }
}

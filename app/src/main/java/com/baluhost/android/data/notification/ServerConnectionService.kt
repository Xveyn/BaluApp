package com.baluhost.android.data.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baluhost.android.data.worker.FolderSyncWorker
import com.baluhost.android.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ServerConnectionService : Service() {

    @Inject
    lateinit var monitor: ServerConnectionMonitor

    @Inject
    lateinit var workManager: WorkManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private data class SyncProgressInfo(
        val status: String,
        val fileName: String?,
        val current: Int,
        val total: Int,
        val activeSyncCount: Int
    )

    private data class NotificationState(
        val connectionState: ConnectionState,
        val syncProgress: SyncProgressInfo?
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // Ensure notification channel exists before startForeground
        ensureNotificationChannel()

        val initialNotification = buildNotification(
            title = "BaluHost",
            text = "Verbindung wird hergestellt..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SyncNotificationManager.NOTIFICATION_ID_CONNECTION,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                SyncNotificationManager.NOTIFICATION_ID_CONNECTION,
                initialNotification
            )
        }

        monitor.acquire("service")

        serviceScope.launch {
            val syncFlow = workManager.getWorkInfosByTagFlow(FolderSyncWorker.TAG_SYNC)
            combine(monitor.connectionState, syncFlow) { connState, workInfos ->
                val runningInfos = workInfos.filter { it.state == WorkInfo.State.RUNNING }
                val syncProgress = extractSyncProgress(runningInfos)
                NotificationState(connState, syncProgress)
            }.collectLatest { state ->
                val notification = buildCombinedNotification(state)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(SyncNotificationManager.NOTIFICATION_ID_CONNECTION, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved — stopping self")
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        monitor.release("service")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel(SyncNotificationManager.CHANNEL_SERVER_CONNECTION) == null) {
                val channel = android.app.NotificationChannel(
                    SyncNotificationManager.CHANNEL_SERVER_CONNECTION,
                    "Server-Verbindung",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Zeigt an, wenn eine Verbindung zum BaluHost-Server besteht"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun extractSyncProgress(runningInfos: List<WorkInfo>): SyncProgressInfo? {
        if (runningInfos.isEmpty()) return null
        val primary = runningInfos.firstOrNull { info ->
            info.progress.getString(FolderSyncWorker.PROGRESS_STATUS) != null
        } ?: runningInfos.first()

        val progress = primary.progress
        val status = progress.getString(FolderSyncWorker.PROGRESS_STATUS) ?: "running"
        val fileName = progress.getString(FolderSyncWorker.PROGRESS_FILE)
        val current = progress.getInt(FolderSyncWorker.PROGRESS_CURRENT, 0)
        val total = progress.getInt(FolderSyncWorker.PROGRESS_TOTAL, 0)

        return SyncProgressInfo(
            status = status,
            fileName = fileName,
            current = current,
            total = total,
            activeSyncCount = runningInfos.size
        )
    }

    private fun formatSyncStatus(progress: SyncProgressInfo, activeSyncCount: Int): String {
        val statusText = when (progress.status) {
            "scanning_local" -> "Dateien scannen..."
            "listing_remote" -> "Remote-Dateien abrufen..."
            "detecting_conflicts" -> "Konflikte prüfen..."
            "loading_config" -> "Konfiguration laden..."
            "uploading" -> if (progress.total > 0) "Hochladen (${progress.current}/${progress.total})" else "Hochladen..."
            "downloading" -> if (progress.total > 0) "Herunterladen (${progress.current}/${progress.total})" else "Herunterladen..."
            else -> "Sync läuft..."
        }
        val extra = if (activeSyncCount > 1) " +${activeSyncCount - 1} weitere" else ""
        return "Sync: $statusText$extra"
    }

    private fun buildCombinedNotification(state: NotificationState): Notification {
        val (connState, syncProgress) = state

        return when (connState) {
            is ConnectionState.Connected -> {
                val uptimeText = "Uptime: ${connState.uptimeText}"
                if (syncProgress != null) {
                    val syncText = formatSyncStatus(syncProgress, syncProgress.activeSyncCount)
                    val isIndeterminate = syncProgress.total == 0
                    val bigText = "$uptimeText\n$syncText"

                    buildNotification(
                        title = "Mit BaluHost verbunden",
                        text = syncText,
                        icon = android.R.drawable.stat_notify_sync,
                        progressMax = if (isIndeterminate) 0 else syncProgress.total,
                        progressCurrent = if (isIndeterminate) 0 else syncProgress.current,
                        progressIndeterminate = isIndeterminate,
                        bigText = bigText
                    )
                } else {
                    buildNotification(
                        title = "Mit BaluHost verbunden",
                        text = uptimeText,
                        icon = android.R.drawable.stat_notify_sync
                    )
                }
            }
            is ConnectionState.Disconnected -> buildNotification(
                title = "BaluHost getrennt",
                text = "Server nicht erreichbar"
            )
            is ConnectionState.Idle -> buildNotification(
                title = "BaluHost",
                text = "Verbindung wird hergestellt..."
            )
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        icon: Int = android.R.drawable.stat_notify_sync,
        progressMax: Int = 0,
        progressCurrent: Int = 0,
        progressIndeterminate: Boolean = false,
        bigText: String? = null
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, SyncNotificationManager.CHANNEL_SERVER_CONNECTION)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setContentIntent(pendingIntent)

        if (progressMax > 0 || progressIndeterminate) {
            builder.setProgress(progressMax, progressCurrent, progressIndeterminate)
        }

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "ServerConnectionService"

        fun start(context: Context) {
            val intent = Intent(context, ServerConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerConnectionService::class.java))
        }
    }
}

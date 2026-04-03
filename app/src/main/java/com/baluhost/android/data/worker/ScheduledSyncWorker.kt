package com.baluhost.android.data.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.SyncRepository
import com.baluhost.android.domain.usecase.vpn.ConnectVpnUseCase
import com.baluhost.android.domain.usecase.vpn.DisconnectVpnUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Worker that executes a scheduled sync.
 *
 * Optionally connects VPN before syncing and disconnects after.
 * Delegates actual file sync to FolderSyncWorker for each auto-sync folder.
 */
@HiltWorker
class ScheduledSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val preferencesManager: PreferencesManager,
    private val connectVpnUseCase: ConnectVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val workManager: WorkManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ScheduledSyncWorker"
        const val INPUT_SCHEDULE_ID = "schedule_id"
        const val INPUT_AUTO_VPN = "auto_vpn"
        private const val VPN_CONNECT_TIMEOUT_MS = 10_000L
        private const val VPN_CHECK_INTERVAL_MS = 500L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scheduleId = inputData.getInt(INPUT_SCHEDULE_ID, -1)
        val scheduleAutoVpn = inputData.getBoolean(INPUT_AUTO_VPN, false)
        val globalAutoVpn = preferencesManager.isAutoVpnForSync().first()
        val autoVpn = scheduleAutoVpn || globalAutoVpn

        Log.d(TAG, "Starting scheduled sync (schedule=$scheduleId, autoVpn=$autoVpn)")

        var vpnConnectedByUs = false

        try {
            // 1. Auto-VPN connect if needed
            if (autoVpn && !isVpnActive()) {
                Log.d(TAG, "Connecting VPN for sync...")
                val vpnResult = connectVpnUseCase()
                if (vpnResult is com.baluhost.android.util.Result.Success) {
                    vpnConnectedByUs = waitForVpn(VPN_CONNECT_TIMEOUT_MS)
                    if (vpnConnectedByUs) {
                        Log.d(TAG, "VPN connected successfully")
                    } else {
                        Log.w(TAG, "VPN failed to establish within timeout, proceeding without VPN")
                    }
                } else {
                    Log.w(TAG, "VPN connect failed: $vpnResult, proceeding without VPN")
                }
            }

            // 2. Preflight: check if NAS is sleeping
            try {
                val preflightResult = syncRepository.getSyncPreflight()
                val preflight = preflightResult.getOrNull()
                if (preflight != null) {
                    // Cache sleep schedule for offline awareness
                    preflight.sleepSchedule?.let { schedule ->
                        preferencesManager.saveSleepSchedule(
                            sleepTime = schedule.sleepTime,
                            wakeTime = schedule.wakeTime,
                            enabled = schedule.enabled
                        )
                    }
                    if (!preflight.syncAllowed) {
                        Log.d(TAG, "Preflight: sync blocked (${preflight.blockReason}), next wake: ${preflight.nextWakeAt}")
                        return@withContext Result.success()
                    }
                }
            } catch (e: Exception) {
                // NAS unreachable — check cached sleep schedule
                val probablySleeping = preferencesManager.isNasProbablySleeping().first()
                if (probablySleeping) {
                    Log.d(TAG, "NAS unreachable and probably sleeping, skipping sync")
                    return@withContext Result.success()
                }
                Log.w(TAG, "Preflight failed but NAS not in sleep window, proceeding: ${e.message}")
            }

            // 3. Get all sync folders with autoSync=true
            val deviceId = preferencesManager.getDeviceId().first()
                ?: return@withContext Result.failure(
                    workDataOf("error" to "No device ID configured")
                )

            val foldersResult = syncRepository.getSyncFolders(deviceId)
            val folders = foldersResult.getOrNull()
            if (folders == null) {
                Log.e(TAG, "Failed to load sync folders: ${foldersResult.exceptionOrNull()?.message}")
                return@withContext Result.retry()
            }

            val autoSyncFolders = folders.filter { it.autoSync }
            Log.d(TAG, "Found ${autoSyncFolders.size} auto-sync folders")

            if (autoSyncFolders.isEmpty()) {
                return@withContext Result.success()
            }

            // 4. Enqueue FolderSyncWorker for each folder
            for (folder in autoSyncFolders) {
                val syncRequest = FolderSyncWorker.createOneTimeRequest(
                    folderId = folder.id,
                    isManual = false
                )
                workManager.enqueueUniqueWork(
                    "scheduled_sync_folder_${folder.id}",
                    ExistingWorkPolicy.REPLACE,
                    syncRequest
                )
                Log.d(TAG, "Enqueued sync for folder: ${folder.id} -> ${folder.remotePath}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled sync failed", e)
            Result.retry()
        } finally {
            // 4. Disconnect VPN only if we connected it
            if (vpnConnectedByUs) {
                Log.d(TAG, "Disconnecting VPN after sync")
                try {
                    disconnectVpnUseCase()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disconnect VPN", e)
                }
            }
        }
    }

    private fun isVpnActive(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private suspend fun waitForVpn(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isVpnActive()) return true
            delay(VPN_CHECK_INTERVAL_MS)
        }
        return false
    }
}

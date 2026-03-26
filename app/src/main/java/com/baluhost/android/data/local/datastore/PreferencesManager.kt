package com.baluhost.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.baluhost.android.data.local.security.SecurePreferencesManager
import com.baluhost.android.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app preferences using DataStore.
 * 
 * Stores non-sensitive data like server URL, user preferences.
 * Sensitive data (tokens, PIN, biometric settings) are stored in SecurePreferencesManager.
 */
@Singleton
class PreferencesManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val securePreferences: SecurePreferencesManager
) {
    
    // Keys (access/refresh tokens now stored in SecurePreferencesManager)
    private val serverUrlKey = stringPreferencesKey(Constants.PrefsKeys.SERVER_URL)
    private val userIdKey = stringPreferencesKey(Constants.PrefsKeys.USER_ID)
    private val vpnConnectedKey = stringPreferencesKey("vpn_connected")
    private val usernameKey = stringPreferencesKey(Constants.PrefsKeys.USERNAME)
    private val userRoleKey = stringPreferencesKey("user_role")
    private val devModeKey = stringPreferencesKey("dev_mode")
    private val vpnConfigKey = stringPreferencesKey(Constants.PrefsKeys.VPN_CONFIG)
    private val fcmTokenKey = stringPreferencesKey("fcm_token")
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val onboardingCompletedKey = stringPreferencesKey("onboarding_completed")
    private val byteUnitModeKey = stringPreferencesKey("byte_unit_mode")
    private val homeBssidKey = stringPreferencesKey("home_bssid")
    // Project convention: booleans stored as "true"/"false" strings via stringPreferencesKey
    // (same as vpnConnectedKey, autoVpnForSync, etc.) — NOT booleanPreferencesKey
    private val autoVpnOnExternalKey = stringPreferencesKey("auto_vpn_on_external")

    // Access Token (delegated to SecurePreferencesManager for encryption)
    suspend fun saveAccessToken(token: String) {
        securePreferences.saveAccessToken(token)
    }
    
    fun getAccessToken(): Flow<String?> = flow {
        emit(securePreferences.getAccessToken())
    }
    
    // Refresh Token (delegated to SecurePreferencesManager for encryption)
    suspend fun saveRefreshToken(token: String) {
        securePreferences.saveRefreshToken(token)
    }
    
    fun getRefreshToken(): Flow<String?> = flow {
        emit(securePreferences.getRefreshToken())
    }
    
    // Server URL
    suspend fun saveServerUrl(url: String) {
        dataStore.edit { prefs -> prefs[serverUrlKey] = url }
    }
    
    fun getServerUrl(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[serverUrlKey] }
    }
    
    // User Info
    suspend fun saveUserId(userId: Int) {
        dataStore.edit { prefs -> prefs[userIdKey] = userId.toString() }
    }
    
    fun getUserId(): Flow<Int?> {
        return dataStore.data.map { prefs -> prefs[userIdKey]?.toIntOrNull() }
    }
    
    suspend fun saveUsername(username: String) {
        dataStore.edit { prefs -> prefs[usernameKey] = username }
    }
    
    fun getUsername(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[usernameKey] }
    }
    
    // User Role
    suspend fun saveUserRole(role: String) {
        dataStore.edit { prefs -> prefs[userRoleKey] = role }
    }
    
    fun getUserRole(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[userRoleKey] }
    }
    
    // Dev Mode Flag
    suspend fun saveDevMode(devMode: Boolean) {
        dataStore.edit { prefs -> prefs[devModeKey] = devMode.toString() }
    }
    
    fun getDevMode(): Flow<Boolean> {
        return dataStore.data.map { prefs -> 
            prefs[devModeKey]?.toBoolean() ?: false 
        }
    }
    
    // VPN Config
    suspend fun saveVpnConfig(config: String) {
        dataStore.edit { prefs -> prefs[vpnConfigKey] = config }
    }
    
    fun getVpnConfig(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[vpnConfigKey] }
    }
    
    // FCM Token (Firebase Cloud Messaging)
    suspend fun saveFcmToken(token: String) {
        dataStore.edit { prefs -> prefs[fcmTokenKey] = token }
    }
    
    fun getFcmToken(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[fcmTokenKey] }
    }
    
    // Device ID (from registration)
    suspend fun saveDeviceId(deviceId: String) {
        dataStore.edit { prefs -> prefs[deviceIdKey] = deviceId }
    }
    
    fun getDeviceId(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[deviceIdKey] }
    }
    
    // Onboarding State
    suspend fun saveOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs -> prefs[onboardingCompletedKey] = completed.toString() }
    }
    
    fun isOnboardingCompleted(): Flow<Boolean> {
        return dataStore.data.map { prefs -> 
            prefs[onboardingCompletedKey]?.toBoolean() ?: false
        }
    }
    
    // Byte Unit Mode (binary / decimal)
    suspend fun saveByteUnitMode(mode: String) {
        dataStore.edit { prefs -> prefs[byteUnitModeKey] = mode }
    }

    fun getByteUnitMode(): Flow<String> {
        return dataStore.data.map { prefs -> prefs[byteUnitModeKey] ?: "binary" }
    }

    // Sync Folder URIs (stored as JSON string map: folderId -> URI string)
    suspend fun saveSyncFolderUri(folderId: String, uri: String) {
        dataStore.edit { prefs ->
            val key = stringPreferencesKey("sync_folder_uri_$folderId")
            prefs[key] = uri
        }
    }
    
    suspend fun getSyncFolderUri(folderId: String): String? {
        val key = stringPreferencesKey("sync_folder_uri_$folderId")
        return dataStore.data.map { prefs -> prefs[key] }.first()
    }
    
    suspend fun removeSyncFolderUri(folderId: String) {
        dataStore.edit { prefs ->
            val key = stringPreferencesKey("sync_folder_uri_$folderId")
            prefs.remove(key)
        }
    }
    
    // Pending conflicts for manual resolution
    suspend fun savePendingConflicts(folderId: String, conflicts: List<com.baluhost.android.domain.model.sync.FileConflict>) {
        val key = stringPreferencesKey("pending_conflicts_$folderId")
        val conflictsJson = conflicts.joinToString("|||") { conflict ->
            "${conflict.id}::${conflict.relativePath}::${conflict.fileName}::" +
            "${conflict.localSize}::${conflict.remoteSize}::" +
            "${conflict.localModifiedAt}::${conflict.remoteModifiedAt}::${conflict.detectedAt}"
        }
        dataStore.edit { prefs ->
            prefs[key] = conflictsJson
        }
    }
    
    fun getPendingConflicts(folderId: String): Flow<List<com.baluhost.android.domain.model.sync.FileConflict>> = flow {
        val key = stringPreferencesKey("pending_conflicts_$folderId")
        val conflictsJson = dataStore.data.map { prefs -> prefs[key] }.first()
        
        if (conflictsJson.isNullOrEmpty()) {
            emit(emptyList())
        } else {
            val conflicts = conflictsJson.split("|||").mapNotNull { entry ->
                try {
                    val parts = entry.split("::")
                    if (parts.size >= 8) {
                        com.baluhost.android.domain.model.sync.FileConflict(
                            id = parts[0],
                            relativePath = parts[1],
                            fileName = parts[2],
                            localSize = parts[3].toLong(),
                            remoteSize = parts[4].toLong(),
                            localModifiedAt = parts[5].toLong(),
                            remoteModifiedAt = parts[6].toLong(),
                            detectedAt = parts[7].toLong()
                        )
                    } else null
                } catch (e: Exception) {
                    com.baluhost.android.util.Logger.e("PreferencesManager", "Failed to parse pending conflict entry", e)
                    null
                }
            }
            emit(conflicts)
        }
    }
    
    suspend fun clearPendingConflicts(folderId: String) {
        dataStore.edit { prefs ->
            val key = stringPreferencesKey("pending_conflicts_$folderId")
            prefs.remove(key)
        }
    }
    
    // Sync History (stored as JSON string, max 50 entries)
    suspend fun saveSyncHistory(history: com.baluhost.android.domain.model.sync.SyncHistory) {
        val key = stringPreferencesKey("sync_history")
        
        // Get existing history
        val existingJson = dataStore.data.map { prefs -> prefs[key] }.first()
        val existingHistory = if (!existingJson.isNullOrEmpty()) {
            existingJson.split("|||").mapNotNull { entry ->
                try {
                    val parts = entry.split("::")
                    if (parts.size >= 10) {
                        com.baluhost.android.domain.model.sync.SyncHistory(
                            id = parts[0],
                            folderId = parts[1],
                            folderName = parts[2],
                            timestamp = parts[3].toLong(),
                            status = com.baluhost.android.domain.model.sync.SyncHistoryStatus.valueOf(parts[4]),
                            filesUploaded = parts[5].toInt(),
                            filesDownloaded = parts[6].toInt(),
                            filesDeleted = parts[7].toInt(),
                            conflictsDetected = parts[8].toInt(),
                            conflictsResolved = parts[9].toInt(),
                            bytesTransferred = parts[10].toLong(),
                            durationMs = parts[11].toLong(),
                            errorMessage = parts.getOrNull(12)?.takeIf { it != "null" }
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }.toMutableList()
        } else {
            mutableListOf()
        }

        // Add new entry at the beginning
        existingHistory.add(0, history)
        
        // Keep only last 50 entries
        val trimmedHistory = existingHistory.take(50)
        
        // Serialize back to string
        val newJson = trimmedHistory.joinToString("|||") { h ->
            "${h.id}::${h.folderId}::${h.folderName}::" +
            "${h.timestamp}::${h.status}::" +
            "${h.filesUploaded}::${h.filesDownloaded}::${h.filesDeleted}::" +
            "${h.conflictsDetected}::${h.conflictsResolved}::" +
            "${h.bytesTransferred}::${h.durationMs}::${h.errorMessage}"
        }
        
        dataStore.edit { prefs ->
            prefs[key] = newJson
        }
    }
    
    fun getSyncHistory(folderId: String? = null): Flow<List<com.baluhost.android.domain.model.sync.SyncHistory>> = flow {
        val key = stringPreferencesKey("sync_history")
        val historyJson = dataStore.data.map { prefs -> prefs[key] }.first()
        
        if (historyJson.isNullOrEmpty()) {
            emit(emptyList())
        } else {
            val history = historyJson.split("|||").mapNotNull { entry ->
                try {
                    val parts = entry.split("::")
                    if (parts.size >= 10) {
                        com.baluhost.android.domain.model.sync.SyncHistory(
                            id = parts[0],
                            folderId = parts[1],
                            folderName = parts[2],
                            timestamp = parts[3].toLong(),
                            status = com.baluhost.android.domain.model.sync.SyncHistoryStatus.valueOf(parts[4]),
                            filesUploaded = parts[5].toInt(),
                            filesDownloaded = parts[6].toInt(),
                            filesDeleted = parts[7].toInt(),
                            conflictsDetected = parts[8].toInt(),
                            conflictsResolved = parts[9].toInt(),
                            bytesTransferred = parts[10].toLong(),
                            durationMs = parts[11].toLong(),
                            errorMessage = parts.getOrNull(12)?.takeIf { it != "null" }
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            // Filter by folderId if provided
            val filtered = if (folderId != null) {
                history.filter { it.folderId == folderId }
            } else {
                history
            }
            
            emit(filtered)
        }
    }
    
    fun getSyncHistorySummary(): Flow<com.baluhost.android.domain.model.sync.SyncHistorySummary> = flow {
        val history = getSyncHistory().first()
        
        val summary = com.baluhost.android.domain.model.sync.SyncHistorySummary(
            totalSyncs = history.size,
            successfulSyncs = history.count { it.status == com.baluhost.android.domain.model.sync.SyncHistoryStatus.SUCCESS },
            failedSyncs = history.count { it.status == com.baluhost.android.domain.model.sync.SyncHistoryStatus.FAILED },
            totalFilesUploaded = history.sumOf { it.filesUploaded },
            totalFilesDownloaded = history.sumOf { it.filesDownloaded },
            totalBytesTransferred = history.sumOf { it.bytesTransferred },
            totalConflictsDetected = history.sumOf { it.conflictsDetected },
            totalConflictsResolved = history.sumOf { it.conflictsResolved },
            lastSyncTimestamp = history.maxOfOrNull { it.timestamp }
        )
        
        emit(summary)
    }
    
    // Auto-VPN for sync preference
    suspend fun saveAutoVpnForSync(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("auto_vpn_for_sync")] = enabled.toString() }
    }

    fun isAutoVpnForSync(): Flow<Boolean> {
        return dataStore.data.map { prefs ->
            prefs[stringPreferencesKey("auto_vpn_for_sync")]?.toBoolean() ?: false
        }
    }

    // Sync Schedules cache (for offline availability)
    suspend fun saveSyncSchedules(schedules: List<com.baluhost.android.domain.model.sync.SyncSchedule>) {
        val key = stringPreferencesKey("sync_schedules_cache")
        val serialized = schedules.joinToString("|||") { s ->
            "${s.scheduleId}::${s.deviceId}::${s.scheduleType.toApiString()}::" +
            "${s.timeOfDay}::${s.dayOfWeek ?: "null"}::${s.dayOfMonth ?: "null"}::" +
            "${s.nextRunAt ?: "null"}::${s.lastRunAt ?: "null"}::" +
            "${s.enabled}::${s.syncDeletions}::${s.resolveConflicts}::${s.autoVpn}"
        }
        dataStore.edit { prefs -> prefs[key] = serialized }
    }

    suspend fun getCachedSyncSchedules(): List<com.baluhost.android.domain.model.sync.SyncSchedule> {
        val key = stringPreferencesKey("sync_schedules_cache")
        val cached = dataStore.data.map { prefs -> prefs[key] }.first()
        if (cached.isNullOrEmpty()) return emptyList()

        return cached.split("|||").mapNotNull { entry ->
            try {
                val parts = entry.split("::")
                if (parts.size >= 11) {
                    com.baluhost.android.domain.model.sync.SyncSchedule(
                        scheduleId = parts[0].toInt(),
                        deviceId = parts[1],
                        scheduleType = com.baluhost.android.domain.model.sync.ScheduleType.fromString(parts[2]),
                        timeOfDay = parts[3],
                        dayOfWeek = parts[4].takeIf { it != "null" }?.toIntOrNull(),
                        dayOfMonth = parts[5].takeIf { it != "null" }?.toIntOrNull(),
                        nextRunAt = parts[6].takeIf { it != "null" }?.toLongOrNull(),
                        lastRunAt = parts[7].takeIf { it != "null" }?.toLongOrNull(),
                        enabled = parts[8].toBoolean(),
                        syncDeletions = parts[9].toBoolean(),
                        resolveConflicts = parts[10],
                        autoVpn = parts.getOrNull(11)?.toBoolean() ?: false
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    // Clear all tokens (on logout or auth failure)
    suspend fun clearTokens() {
        securePreferences.clearTokens()
    }

    // Adapter credential helpers (wrap SecurePreferencesManager)
    suspend fun saveAdapterCredentials(adapterKey: String, username: String?, password: String?) {
        // securePreferences is synchronous; run on calling coroutine
        securePreferences.saveAdapterCredentials(adapterKey, username, password)
    }

    suspend fun getAdapterCredentials(adapterKey: String): Pair<String?, String?> {
        return securePreferences.getAdapterCredentials(adapterKey)
    }

    suspend fun removeAdapterCredentials(adapterKey: String) {
        securePreferences.removeAdapterCredentials(adapterKey)
    }
    
    // VPN Additional Data
    suspend fun saveVpnClientId(clientId: Int) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("vpn_client_id")] = clientId.toString() }
    }
    
    fun getVpnClientId(): Flow<Int?> {
        return dataStore.data.map { prefs -> 
            prefs[stringPreferencesKey("vpn_client_id")]?.toIntOrNull()
        }
    }
    
    suspend fun saveVpnDeviceName(deviceName: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("vpn_device_name")] = deviceName }
    }
    
    fun getVpnDeviceName(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("vpn_device_name")] }
    }
    
    suspend fun saveVpnPublicKey(publicKey: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("vpn_public_key")] = publicKey }
    }
    
    fun getVpnPublicKey(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("vpn_public_key")] }
    }
    
    suspend fun saveVpnAssignedIp(assignedIp: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("vpn_assigned_ip")] = assignedIp }
    }
    
    fun getVpnAssignedIp(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("vpn_assigned_ip")] }
    }

    suspend fun saveVpnType(type: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("vpn_type")] = type }
    }

    fun getVpnType(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("vpn_type")] }
    }

    // Fritz!Box Config (for direct TR-064 WoL)
    suspend fun saveFritzBoxHost(host: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("fritzbox_host")] = host }
    }

    fun getFritzBoxHost(): Flow<String> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("fritzbox_host")] ?: "192.168.178.1" }
    }

    suspend fun saveFritzBoxPort(port: Int) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("fritzbox_port")] = port.toString() }
    }

    fun getFritzBoxPort(): Flow<Int> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("fritzbox_port")]?.toIntOrNull() ?: 49000 }
    }

    suspend fun saveFritzBoxUsername(username: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("fritzbox_username")] = username }
    }

    fun getFritzBoxUsername(): Flow<String> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("fritzbox_username")] ?: "" }
    }

    suspend fun saveFritzBoxMacAddress(mac: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("fritzbox_mac")] = mac }
    }

    fun getFritzBoxMacAddress(): Flow<String> {
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey("fritzbox_mac")] ?: "" }
    }

    suspend fun saveFritzBoxPassword(password: String) {
        securePreferences.saveFritzBoxPassword(password)
    }

    fun getFritzBoxPassword(): String? {
        return securePreferences.getFritzBoxPassword()
    }

    fun isFritzBoxConfigured(): Flow<Boolean> {
        return dataStore.data.map { prefs ->
            val mac = prefs[stringPreferencesKey("fritzbox_mac")] ?: ""
            mac.isNotEmpty()
        }
    }

    // Home BSSID (for home network detection)
    suspend fun saveHomeBssid(bssid: String) {
        dataStore.edit { prefs -> prefs[homeBssidKey] = bssid.uppercase() }
    }

    fun getHomeBssid(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[homeBssidKey] }
    }

    suspend fun getHomeBssidOnce(): String? {
        return dataStore.data.map { prefs -> prefs[homeBssidKey] }.first()
    }

    suspend fun clearHomeBssid() {
        dataStore.edit { prefs -> prefs.remove(homeBssidKey) }
    }

    // Auto-VPN when external network detected
    suspend fun saveAutoVpnOnExternal(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[autoVpnOnExternalKey] = enabled.toString() }
    }

    fun isAutoVpnOnExternal(): Flow<Boolean> {
        return dataStore.data.map { prefs ->
            prefs[autoVpnOnExternalKey]?.toBoolean() ?: false
        }
    }

    // Clear all data
    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
        securePreferences.clearFritzBoxPassword()
    }
}

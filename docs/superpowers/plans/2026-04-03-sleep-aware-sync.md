# Sleep-Aware Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make automatic sync respect the NAS sleep schedule — skip syncs when the NAS is sleeping, handle 503 responses gracefully, and cache the sleep schedule for offline awareness.

**Architecture:** A `SyncTrigger` enum is passed via Retrofit `@Tag` through the repository layer and read by a new `SyncTriggerInterceptor` that sets the `X-Sync-Trigger` header. The `ScheduledSyncWorker` calls a new preflight endpoint before dispatching folder syncs. 503 responses are handled alongside existing 429 handling in `FolderSyncWorker`. Sleep schedule is cached in `PreferencesManager` for offline fallback.

**Tech Stack:** Kotlin, Retrofit 2 (`@Tag`), OkHttp Interceptor, WorkManager, DataStore Preferences

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `data/remote/dto/sync/SyncPreflightDto.kt` | Preflight response DTO |
| Create | `data/remote/interceptors/SyncTriggerInterceptor.kt` | Reads `SyncTrigger` tag, sets `X-Sync-Trigger` header |
| Create | `domain/model/sync/SyncTrigger.kt` | Enum: `AUTO`, `MANUAL` |
| Modify | `data/remote/api/SyncApi.kt` | Add preflight endpoint |
| Modify | `domain/repository/SyncRepository.kt` | Add `getSyncPreflight()` |
| Modify | `data/repository/SyncRepositoryImpl.kt` | Implement preflight call |
| Modify | `di/NetworkModule.kt` | Wire `SyncTriggerInterceptor` into OkHttp chain |
| Modify | `data/worker/ScheduledSyncWorker.kt` | Preflight check + offline sleep awareness before dispatching |
| Modify | `data/worker/FolderSyncWorker.kt` | 503 handling alongside existing 429 handling |
| Modify | `data/local/datastore/PreferencesManager.kt` | Cache/read sleep schedule |
| Test | `test/.../sync/SleepScheduleUtilTest.kt` | `isInSleepWindow()` unit tests |

All paths relative to `app/src/main/java/com/baluhost/android/`.

---

### Task 1: SyncTrigger Enum + Interceptor

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/model/sync/SyncTrigger.kt`
- Create: `app/src/main/java/com/baluhost/android/data/remote/interceptors/SyncTriggerInterceptor.kt`

- [ ] **Step 1: Create SyncTrigger enum**

```kotlin
// domain/model/sync/SyncTrigger.kt
package com.baluhost.android.domain.model.sync

enum class SyncTrigger(val headerValue: String) {
    AUTO("auto"),
    MANUAL("manual")
}
```

- [ ] **Step 2: Create SyncTriggerInterceptor**

```kotlin
// data/remote/interceptors/SyncTriggerInterceptor.kt
package com.baluhost.android.data.remote.interceptors

import com.baluhost.android.domain.model.sync.SyncTrigger
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class SyncTriggerInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val trigger = request.tag(SyncTrigger::class.java)
        if (trigger != null) {
            val newRequest = request.newBuilder()
                .header("X-Sync-Trigger", trigger.headerValue)
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/sync/SyncTrigger.kt app/src/main/java/com/baluhost/android/data/remote/interceptors/SyncTriggerInterceptor.kt
git commit -m "feat(sync): add SyncTrigger enum and OkHttp interceptor for X-Sync-Trigger header"
```

---

### Task 2: Wire SyncTriggerInterceptor into OkHttp

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/di/NetworkModule.kt`

- [ ] **Step 1: Add provider for SyncTriggerInterceptor**

Add after `provideErrorInterceptor()` (around line 74):

```kotlin
@Provides
@Singleton
fun provideSyncTriggerInterceptor(): SyncTriggerInterceptor {
    return SyncTriggerInterceptor()
}
```

- [ ] **Step 2: Add interceptor to OkHttpClient builder**

Modify `provideOkHttpClient` to accept and add the new interceptor. Add parameter `syncTriggerInterceptor: SyncTriggerInterceptor` and add `.addInterceptor(syncTriggerInterceptor)` after the `authInterceptor` line (line 107):

```kotlin
fun provideOkHttpClient(
    loggingInterceptor: HttpLoggingInterceptor,
    authInterceptor: AuthInterceptor,
    errorInterceptor: ErrorInterceptor,
    dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
    vpnAwareSocketFactory: VpnAwareSocketFactory,
    syncTriggerInterceptor: SyncTriggerInterceptor
): OkHttpClient {
    return OkHttpClient.Builder()
        .socketFactory(vpnAwareSocketFactory)
        .addInterceptor(dynamicBaseUrlInterceptor)
        .addInterceptor(errorInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(syncTriggerInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(Constants.CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(Constants.READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(Constants.WRITE_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
```

Add import: `import com.baluhost.android.data.remote.interceptors.SyncTriggerInterceptor`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/di/NetworkModule.kt
git commit -m "feat(sync): wire SyncTriggerInterceptor into OkHttp client"
```

---

### Task 3: Preflight DTO + API Endpoint

**Files:**
- Create: `app/src/main/java/com/baluhost/android/data/remote/dto/sync/SyncPreflightDto.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/SyncApi.kt`

- [ ] **Step 1: Create preflight DTOs**

```kotlin
// data/remote/dto/sync/SyncPreflightDto.kt
package com.baluhost.android.data.remote.dto.sync

import com.google.gson.annotations.SerializedName

data class SyncPreflightDto(
    @SerializedName("sync_allowed")
    val syncAllowed: Boolean,
    @SerializedName("current_sleep_state")
    val currentSleepState: String,
    @SerializedName("sleep_schedule")
    val sleepSchedule: SleepScheduleInfoDto?,
    @SerializedName("next_wake_at")
    val nextWakeAt: String?,
    @SerializedName("block_reason")
    val blockReason: String?
)

data class SleepScheduleInfoDto(
    val enabled: Boolean,
    @SerializedName("sleep_time")
    val sleepTime: String,
    @SerializedName("wake_time")
    val wakeTime: String,
    val mode: String
)
```

- [ ] **Step 2: Add preflight endpoint to SyncApi**

Add at the end of the `SyncApi` interface (before closing brace, line 187):

```kotlin
/**
 * Check if sync is allowed (sleep-aware preflight).
 */
@GET("sync/preflight")
suspend fun getSyncPreflight(): SyncPreflightDto
```

Add import: `import com.baluhost.android.data.remote.dto.sync.SyncPreflightDto` (not needed — already imports `sync.*`)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/dto/sync/SyncPreflightDto.kt app/src/main/java/com/baluhost/android/data/remote/api/SyncApi.kt
git commit -m "feat(sync): add preflight DTO and SyncApi endpoint"
```

---

### Task 4: Repository Layer — Preflight + SyncTrigger Tag

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/repository/SyncRepository.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/SyncRepositoryImpl.kt`

- [ ] **Step 1: Add preflight method to SyncRepository interface**

Add at the end of the interface (before closing brace, line 137):

```kotlin
/**
 * Check if sync is currently allowed (sleep-aware preflight).
 */
suspend fun getSyncPreflight(): Result<SyncPreflightResponse>
```

- [ ] **Step 2: Create domain model for preflight response**

Add to `app/src/main/java/com/baluhost/android/domain/model/sync/SyncModels.kt` at the end:

```kotlin
data class SyncPreflightResponse(
    val syncAllowed: Boolean,
    val currentSleepState: String,
    val sleepSchedule: SleepScheduleInfo?,
    val nextWakeAt: String?,
    val blockReason: String?
)

data class SleepScheduleInfo(
    val enabled: Boolean,
    val sleepTime: String,
    val wakeTime: String,
    val mode: String
)
```

- [ ] **Step 3: Implement in SyncRepositoryImpl**

Add at the end of `SyncRepositoryImpl` (before closing brace, line 329):

```kotlin
override suspend fun getSyncPreflight(): Result<SyncPreflightResponse> {
    return try {
        val dto = syncApi.getSyncPreflight()
        Result.success(
            SyncPreflightResponse(
                syncAllowed = dto.syncAllowed,
                currentSleepState = dto.currentSleepState,
                sleepSchedule = dto.sleepSchedule?.let {
                    SleepScheduleInfo(
                        enabled = it.enabled,
                        sleepTime = it.sleepTime,
                        wakeTime = it.wakeTime,
                        mode = it.mode
                    )
                },
                nextWakeAt = dto.nextWakeAt,
                blockReason = dto.blockReason
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

Add imports in SyncRepositoryImpl: `import com.baluhost.android.domain.model.sync.SyncPreflightResponse` and `import com.baluhost.android.domain.model.sync.SleepScheduleInfo`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/repository/SyncRepository.kt app/src/main/java/com/baluhost/android/data/repository/SyncRepositoryImpl.kt app/src/main/java/com/baluhost/android/domain/model/sync/SyncModels.kt
git commit -m "feat(sync): add preflight to repository layer with domain models"
```

---

### Task 5: Sleep Schedule Caching in PreferencesManager

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt`

- [ ] **Step 1: Add sleep schedule cache methods**

Add after the existing `getCachedSyncSchedules()` method (around line 398):

```kotlin
// Sleep schedule cache (for offline NAS sleep awareness)
suspend fun saveSleepSchedule(sleepTime: String, wakeTime: String, enabled: Boolean) {
    dataStore.edit { prefs ->
        prefs[stringPreferencesKey("nas_sleep_time")] = sleepTime
        prefs[stringPreferencesKey("nas_wake_time")] = wakeTime
        prefs[stringPreferencesKey("nas_sleep_enabled")] = enabled.toString()
    }
}

fun isNasProbablySleeping(): Flow<Boolean> {
    return dataStore.data.map { prefs ->
        val enabled = prefs[stringPreferencesKey("nas_sleep_enabled")]?.toBoolean() ?: false
        if (!enabled) return@map false
        val sleepTime = prefs[stringPreferencesKey("nas_sleep_time")] ?: return@map false
        val wakeTime = prefs[stringPreferencesKey("nas_wake_time")] ?: return@map false
        try {
            val now = java.time.LocalTime.now()
            val sleep = java.time.LocalTime.parse(sleepTime)
            val wake = java.time.LocalTime.parse(wakeTime)
            if (sleep == wake) false
            else if (sleep < wake) now >= sleep && now < wake
            else now >= sleep || now < wake
        } catch (e: Exception) {
            false
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt
git commit -m "feat(sync): add sleep schedule caching and isNasProbablySleeping() to PreferencesManager"
```

---

### Task 6: Sleep Window Unit Tests

**Files:**
- Create: `app/src/test/java/com/baluhost/android/sync/SleepScheduleUtilTest.kt`

- [ ] **Step 1: Write isInSleepWindow tests**

The sleep window logic is in `PreferencesManager.isNasProbablySleeping()` but the core logic can be tested standalone. Extract a pure function for testability:

Create `app/src/main/java/com/baluhost/android/util/SleepScheduleUtil.kt`:

```kotlin
package com.baluhost.android.util

import java.time.LocalTime

object SleepScheduleUtil {
    /**
     * Check if [now] falls within the sleep window [sleepTime, wakeTime).
     * Handles overnight windows (e.g., 23:00 -> 06:00).
     */
    fun isInSleepWindow(now: LocalTime, sleepTime: LocalTime, wakeTime: LocalTime): Boolean {
        if (sleepTime == wakeTime) return false
        return if (sleepTime < wakeTime) {
            now >= sleepTime && now < wakeTime
        } else {
            now >= sleepTime || now < wakeTime
        }
    }
}
```

- [ ] **Step 2: Write the tests**

```kotlin
// app/src/test/java/com/baluhost/android/sync/SleepScheduleUtilTest.kt
package com.baluhost.android.sync

import com.baluhost.android.util.SleepScheduleUtil
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class SleepScheduleUtilTest {

    @Test
    fun `overnight window - during sleep returns true`() {
        // 23:00 -> 06:00, current time 02:00
        assertTrue(SleepScheduleUtil.isInSleepWindow(
            now = LocalTime.of(2, 0),
            sleepTime = LocalTime.of(23, 0),
            wakeTime = LocalTime.of(6, 0)
        ))
    }

    @Test
    fun `overnight window - at sleep start returns true`() {
        assertTrue(SleepScheduleUtil.isInSleepWindow(
            now = LocalTime.of(23, 0),
            sleepTime = LocalTime.of(23, 0),
            wakeTime = LocalTime.of(6, 0)
        ))
    }

    @Test
    fun `overnight window - at wake time returns false`() {
        assertFalse(SleepScheduleUtil.isInSleepWindow(
            now = LocalTime.of(6, 0),
            sleepTime = LocalTime.of(23, 0),
            wakeTime = LocalTime.of(6, 0)
        ))
    }

    @Test
    fun `overnight window - during day returns false`() {
        assertFalse(SleepScheduleUtil.isInSleepWindow(
            now = LocalTime.of(14, 0),
            sleepTime = LocalTime.of(23, 0),
            wakeTime = LocalTime.of(6, 0)
        ))
    }

    @Test
    fun `daytime window - during sleep returns true`() {
        // 01:00 -> 07:00
        assertTrue(SleepScheduleUtil.isInSleepWindow(
            now = LocalTime.of(3, 0),
            sleepTime = LocalTime.of(1, 0),
            wakeTime = LocalTime.of(7, 0)
        ))
    }

    @Test
    fun `daytime window - outside returns false`() {
        assertFalse(SleepScheduleUtil.isInSleepWindow(
            now = LocalTime.of(12, 0),
            sleepTime = LocalTime.of(1, 0),
            wakeTime = LocalTime.of(7, 0)
        ))
    }

    @Test
    fun `equal times - always returns false`() {
        assertFalse(SleepScheduleUtil.isInSleepWindow(
            now = LocalTime.of(23, 0),
            sleepTime = LocalTime.of(23, 0),
            wakeTime = LocalTime.of(23, 0)
        ))
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.baluhost.android.sync.SleepScheduleUtilTest"`
Expected: All 7 tests pass.

- [ ] **Step 4: Refactor PreferencesManager to use SleepScheduleUtil**

In `PreferencesManager.isNasProbablySleeping()`, replace the inline logic with:

```kotlin
fun isNasProbablySleeping(): Flow<Boolean> {
    return dataStore.data.map { prefs ->
        val enabled = prefs[stringPreferencesKey("nas_sleep_enabled")]?.toBoolean() ?: false
        if (!enabled) return@map false
        val sleepTimeStr = prefs[stringPreferencesKey("nas_sleep_time")] ?: return@map false
        val wakeTimeStr = prefs[stringPreferencesKey("nas_wake_time")] ?: return@map false
        try {
            SleepScheduleUtil.isInSleepWindow(
                now = java.time.LocalTime.now(),
                sleepTime = java.time.LocalTime.parse(sleepTimeStr),
                wakeTime = java.time.LocalTime.parse(wakeTimeStr)
            )
        } catch (e: Exception) {
            false
        }
    }
}
```

Add import: `import com.baluhost.android.util.SleepScheduleUtil`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/util/SleepScheduleUtil.kt app/src/test/java/com/baluhost/android/sync/SleepScheduleUtilTest.kt app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt
git commit -m "feat(sync): add SleepScheduleUtil with unit tests, use in PreferencesManager"
```

---

### Task 7: Preflight Check in ScheduledSyncWorker

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/worker/ScheduledSyncWorker.kt`

- [ ] **Step 1: Add preflight check before folder dispatch**

Insert after the VPN logic (line 70) and before getting sync folders (line 73). The worker should:
1. Call preflight
2. If `sync_allowed == false`, cache the sleep schedule and return `Result.success()` (nothing to do)
3. If preflight fails (NAS unreachable), check `isNasProbablySleeping()` as fallback

Replace the section starting at line 72 (`// 2. Get all sync folders`) with:

```kotlin
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
```

Update the comment numbering below: step 3 (get folders) and step 4 (enqueue workers) become 3 and 4.

Add import: `import com.baluhost.android.domain.model.sync.SleepScheduleInfo`

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/worker/ScheduledSyncWorker.kt
git commit -m "feat(sync): add preflight check and offline sleep awareness to ScheduledSyncWorker"
```

---

### Task 8: 503 Handling in FolderSyncWorker

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt`

- [ ] **Step 1: Add 503 handling to upload retry loop**

In the upload retry loop (lines 279-294), add a 503 branch after the existing 429 check:

```kotlin
} catch (e: HttpException) {
    if (e.code() == 429 && attempt < MAX_UPLOAD_RETRIES) {
        val delayMs = RETRY_BASE_DELAY_MS * attempt
        Log.w(TAG, "Rate limited (429) uploading ${upload.relativePath}, retry $attempt/$MAX_UPLOAD_RETRIES in ${delayMs}ms")
        kotlinx.coroutines.delay(delayMs)
        // Rebuild the multipart part for retry (previous body was consumed)
        part = MultipartBody.Part.createFormData(
            "file", localFileInfo.name,
            tempFile.asRequestBody(
                (localFileInfo.mimeType ?: "application/octet-stream").toMediaTypeOrNull()
            )
        )
    } else if (e.code() == 503) {
        Log.w(TAG, "NAS sleeping (503) during upload of ${upload.relativePath}")
        val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
        syncNotificationManager.showSyncErrorNotification(
            folderId, folderName,
            "NAS schlaeft, Sync pausiert" + if (retryAfter != null) " (${retryAfter / 60} Min.)" else ""
        )
        return@withContext Result.failure(
            workDataOf("error" to "NAS sleeping", "retry_after_seconds" to (retryAfter ?: 3600L))
        )
    } else {
        throw e
    }
}
```

- [ ] **Step 2: Add 503 handling to the outer catch block**

In the outer `catch (e: Exception)` block (line 444), add specific 503 handling before the generic error path:

```kotlin
} catch (e: HttpException) {
    if (e.code() == 503) {
        Log.w(TAG, "NAS sleeping (503) during sync of folder: $folderId")
        val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
        syncNotificationManager.showSyncErrorNotification(
            folderId, folderId,
            "NAS schlaeft, Sync pausiert" + if (retryAfter != null) " (${retryAfter / 60} Min.)" else ""
        )
        Result.failure(
            workDataOf("error" to "NAS sleeping", "retry_after_seconds" to (retryAfter ?: 3600L))
        )
    } else {
        throw e
    }
} catch (e: Exception) {
    // ... existing generic error handling ...
}
```

Note: `HttpException` must be caught before the generic `Exception` catch. Split the existing catch chain so `HttpException` is caught first at the outer level. The existing `catch (e: kotlinx.coroutines.CancellationException)` (line 440) stays first, then `HttpException`, then generic `Exception`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt
git commit -m "feat(sync): handle 503 NAS-sleeping responses in FolderSyncWorker"
```

---

### Task 9: Build Verification

- [ ] **Step 1: Run full build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: All tests pass, including the new `SleepScheduleUtilTest`.

- [ ] **Step 3: Final commit if any fixups needed**

---

## Summary of Changes

1. **X-Sync-Trigger header** — `SyncTrigger` enum + `SyncTriggerInterceptor` reads Retrofit `@Tag` and sets header
2. **Preflight check** — `GET sync/preflight` in `SyncApi`, called by `ScheduledSyncWorker` before dispatching folder syncs
3. **503 handling** — In `FolderSyncWorker` upload loop and outer catch, parses `Retry-After` header, shows notification
4. **Offline awareness** — Sleep schedule cached in `PreferencesManager`, `SleepScheduleUtil.isInSleepWindow()` used as fallback when NAS unreachable

### Task 10: Thread SyncTrigger Tag Through Retrofit Calls

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/SyncApi.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/repository/SyncRepository.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/SyncRepositoryImpl.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt`

The `SyncTriggerInterceptor` (Task 1) reads `SyncTrigger` from OkHttp request tags. Now we need to actually set that tag on requests. Retrofit 2.8+ supports `@Tag` annotation on method parameters, which attaches the value as an OkHttp request tag.

- [ ] **Step 1: Add `@Tag` to SyncApi upload/download methods**

Add `@Tag trigger: SyncTrigger? = null` parameter to the methods called during sync:

```kotlin
// In SyncApi.kt — add import at top:
import com.baluhost.android.domain.model.sync.SyncTrigger
import retrofit2.http.Tag

// Update uploadFile:
@Multipart
@POST("files/upload")
suspend fun uploadFile(
    @Part file: okhttp3.MultipartBody.Part,
    @Part("path") remotePath: okhttp3.RequestBody,
    @Tag trigger: SyncTrigger? = null
)

// Update downloadFile:
@Streaming
@GET("files/download/{resource_path}")
suspend fun downloadFile(
    @Path("resource_path", encoded = true) resourcePath: String,
    @Tag trigger: SyncTrigger? = null
): okhttp3.ResponseBody

// Update initiateChunkedUpload:
@POST("files/upload/chunked/init")
suspend fun initiateChunkedUpload(
    @Body request: InitiateUploadDto,
    @Tag trigger: SyncTrigger? = null
): InitiateUploadResponseDto

// Update uploadChunk:
@POST("files/upload/chunked/{upload_id}/chunk")
suspend fun uploadChunk(
    @Path("upload_id") uploadId: String,
    @Query("chunk_index") chunkIndex: Int,
    @Body chunk: okhttp3.RequestBody,
    @Tag trigger: SyncTrigger? = null
): ChunkUploadResponseDto

// Update finalizeChunkedUpload:
@POST("files/upload/chunked/{upload_id}/complete")
suspend fun finalizeChunkedUpload(
    @Path("upload_id") uploadId: String,
    @Tag trigger: SyncTrigger? = null
)
```

- [ ] **Step 2: Add `trigger` parameter to SyncRepository interface**

Update `uploadFile` and `downloadFile` in the interface:

```kotlin
// In SyncRepository.kt:
import com.baluhost.android.domain.model.sync.SyncTrigger

suspend fun uploadFile(folderId: String, remotePath: String, file: okhttp3.MultipartBody.Part, trigger: SyncTrigger? = null)

suspend fun downloadFile(folderId: String, remotePath: String, trigger: SyncTrigger? = null): okhttp3.ResponseBody
```

- [ ] **Step 3: Pass trigger through SyncRepositoryImpl**

```kotlin
// In SyncRepositoryImpl.kt:
import com.baluhost.android.domain.model.sync.SyncTrigger

override suspend fun uploadFile(
    folderId: String,
    remotePath: String,
    file: okhttp3.MultipartBody.Part,
    trigger: SyncTrigger?
) {
    val pathBody = remotePath.toRequestBody("text/plain".toMediaTypeOrNull())
    syncApi.uploadFile(file, pathBody, trigger)
}

override suspend fun downloadFile(
    folderId: String,
    remotePath: String,
    trigger: SyncTrigger?
): okhttp3.ResponseBody {
    return syncApi.downloadFile(remotePath, trigger)
}
```

- [ ] **Step 4: Set trigger in FolderSyncWorker**

In `FolderSyncWorker`, derive the trigger from `isManual` and pass it to upload/download calls.

At the top of `doWork()`, after `val isManual = ...` (line 46):

```kotlin
val syncTrigger = if (isManual) SyncTrigger.MANUAL else SyncTrigger.AUTO
```

Add import: `import com.baluhost.android.domain.model.sync.SyncTrigger`

Then update the `uploadFile` call (around line 272-276):

```kotlin
syncRepository.uploadFile(
    folderId,
    fullRemotePath,
    part,
    syncTrigger
)
```

And the `downloadFile` call (around line 336-339):

```kotlin
val responseBody = syncRepository.downloadFile(
    folderId,
    fullDownloadPath,
    syncTrigger
)
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/api/SyncApi.kt app/src/main/java/com/baluhost/android/domain/repository/SyncRepository.kt app/src/main/java/com/baluhost/android/data/repository/SyncRepositoryImpl.kt app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt
git commit -m "feat(sync): thread SyncTrigger tag through Retrofit calls for X-Sync-Trigger header"
```

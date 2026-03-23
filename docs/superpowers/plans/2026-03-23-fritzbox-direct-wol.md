

# Direct Fritz!Box WoL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the app to send WoL directly to the Fritz!Box via TR-064 SOAP, bypassing the BaluHost backend, so WoL works when the NAS is sleeping.

**Architecture:** Fritz!Box credentials stored locally (DataStore + EncryptedSharedPreferences). A new `FritzBoxTR064Client` sends SOAP requests via OkHttp with Digest Auth. `PowerRepositoryImpl.sendWol()` is rewired from the Retrofit API to the TR-064 client. A new admin-only settings screen allows configuration.

**Tech Stack:** Kotlin, OkHttp (Digest Auth), EncryptedSharedPreferences, Jetpack Compose, Hilt DI

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `data/network/FritzBoxTR064Client.kt` | TR-064 SOAP client (WoL + connection test) |
| Modify | `data/local/security/SecurePreferencesManager.kt` | Fritz!Box password storage |
| Modify | `data/local/datastore/PreferencesManager.kt` | Fritz!Box config fields (host, port, username, mac) |
| Modify | `data/repository/PowerRepositoryImpl.kt` | Rewire `sendWol()` to TR-064 client |
| Create | `presentation/ui/screens/settings/FritzBoxSettingsViewModel.kt` | Settings screen ViewModel |
| Create | `presentation/ui/screens/settings/FritzBoxSettingsScreen.kt` | Settings screen UI |
| Modify | `presentation/navigation/Screen.kt` | Add FritzBoxSettings route |
| Modify | `presentation/navigation/NavGraph.kt` | Register FritzBoxSettings composable |
| Modify | `presentation/ui/screens/settings/SettingsScreen.kt` | Add Fritz!Box nav item (admin-only) |
| Modify | `presentation/ui/screens/dashboard/DashboardViewModel.kt` | Load Fritz!Box config status |
| Modify | `presentation/ui/screens/dashboard/DashboardScreen.kt` | Gate WoL button on config |
| Modify | `res/values/strings.xml` | Fritz!Box settings strings |

All paths relative to `app/src/main/java/com/baluhost/android/`.

---

### Task 1: Fritz!Box Config Storage

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/local/security/SecurePreferencesManager.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt`

- [ ] **Step 1: Add Fritz!Box password methods to SecurePreferencesManager**

Add after the Adapter Credentials section (after line 239), before the closing `}`:

```kotlin
    // ========== Fritz!Box Credentials ==========

    fun saveFritzBoxPassword(password: String) {
        sharedPreferences.edit()
            .putString("fritzbox_password", password)
            .apply()
    }

    fun getFritzBoxPassword(): String? {
        return sharedPreferences.getString("fritzbox_password", null)
    }

    fun clearFritzBoxPassword() {
        sharedPreferences.edit()
            .remove("fritzbox_password")
            .apply()
    }
```

- [ ] **Step 2: Add Fritz!Box config fields to PreferencesManager**

Add after the `getVpnType()` method (after line 452), before the `clearAll()` method:

```kotlin
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
```

- [ ] **Step 3: Add Fritz!Box password clearing to clearAll()**

In `PreferencesManager.kt`, in the `clearAll()` method, add `securePreferences.clearFritzBoxPassword()` after the existing clear calls so Fritz!Box credentials are cleaned up when the user logs out or deletes their device.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add Fritz!Box config storage in PreferencesManager and SecurePreferencesManager"
```

---

### Task 2: TR-064 SOAP Client

**Files:**
- Create: `app/src/main/java/com/baluhost/android/data/network/FritzBoxTR064Client.kt`

- [ ] **Step 1: Create FritzBoxTR064Client**

```kotlin
package com.baluhost.android.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FritzBoxTR064Client @Inject constructor() {

    companion object {
        private const val TAG = "FritzBoxTR064"
        private const val SERVICE_TYPE = "urn:dslforum-org:service:Hosts:1"
        private const val WOL_ACTION = "X_AVM-DE_WakeOnLANByMACAddress"
        private const val CONTROL_URL = "/upnp/control/hosts"
        private const val SCPD_URL = "/hostsSCPD.xml"
        private val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }

    private fun buildSoapEnvelope(macAddress: String): String {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
            " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$WOL_ACTION xmlns:u=\"$SERVICE_TYPE\">" +
            "<NewMACAddress>$macAddress</NewMACAddress>" +
            "</u:$WOL_ACTION>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun parseDigestChallenge(response: Response): Map<String, String>? {
        val header = response.header("WWW-Authenticate") ?: return null
        if (!header.startsWith("Digest ", ignoreCase = true)) return null
        val params = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)="([^"]*)"|((\w+)=([^\s,]+))""")
        regex.findAll(header).forEach { match ->
            val key = match.groupValues[1].ifEmpty { match.groupValues[4] }
            val value = match.groupValues[2].ifEmpty { match.groupValues[5] }
            if (key.isNotEmpty()) params[key.lowercase()] = value
        }
        return params
    }

    private fun buildDigestAuth(
        username: String,
        password: String,
        method: String,
        uri: String,
        challenge: Map<String, String>
    ): String {
        val realm = challenge["realm"] ?: ""
        val nonce = challenge["nonce"] ?: ""
        val qop = challenge["qop"]
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        val nc = "00000001"
        val cnonce = md5Hex(System.nanoTime().toString())

        val response = if (qop != null) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        return buildString {
            append("Digest username=\"$username\"")
            append(", realm=\"$realm\"")
            append(", nonce=\"$nonce\"")
            append(", uri=\"$uri\"")
            if (qop != null) {
                append(", qop=auth")
                append(", nc=$nc")
                append(", cnonce=\"$cnonce\"")
            }
            append(", response=\"$response\"")
            challenge["opaque"]?.let { append(", opaque=\"$it\"") }
        }
    }

    private fun buildClient(username: String, password: String): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .authenticator { _, response ->
                if (response.priorResponse != null) {
                    // Already retried once, give up to avoid infinite loop
                    return@authenticator null
                }
                val challenge = parseDigestChallenge(response) ?: return@authenticator null
                val method = response.request.method
                val uri = response.request.url.encodedPath
                val authHeader = buildDigestAuth(username, password, method, uri, challenge)
                response.request.newBuilder()
                    .header("Authorization", authHeader)
                    .build()
            }
            .build()
    }

    suspend fun sendWol(
        host: String,
        port: Int,
        username: String,
        password: String,
        macAddress: String
    ): WolResult = withContext(Dispatchers.IO) {
        try {
            val url = "http://$host:$port$CONTROL_URL"
            val body = buildSoapEnvelope(macAddress)
            val client = buildClient(username, password)

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(XML_MEDIA_TYPE))
                .header("SOAPAction", "\"$SERVICE_TYPE#$WOL_ACTION\"")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when {
                response.code == 401 -> WolResult.AuthError
                response.code != 200 -> WolResult.Error("HTTP ${response.code}")
                responseBody.contains("Fault", ignoreCase = true) -> {
                    val fault = parseSoapFault(responseBody)
                    WolResult.Error(fault ?: "SOAP Fault")
                }
                else -> WolResult.Success
            }
        } catch (e: IOException) {
            Log.e(TAG, "WoL request failed", e)
            WolResult.Unreachable
        } catch (e: Exception) {
            Log.e(TAG, "WoL unexpected error", e)
            WolResult.Error(e.message ?: "Unbekannter Fehler")
        }
    }

    suspend fun testConnection(
        host: String,
        port: Int,
        username: String,
        password: String
    ): WolResult = withContext(Dispatchers.IO) {
        try {
            val url = "http://$host:$port$SCPD_URL"
            val client = buildClient(username, password)

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            when {
                response.code == 401 -> WolResult.AuthError
                response.code == 200 -> WolResult.Success
                else -> WolResult.Error("HTTP ${response.code}")
            }
        } catch (e: IOException) {
            WolResult.Unreachable
        } catch (e: Exception) {
            WolResult.Error(e.message ?: "Unbekannter Fehler")
        }
    }

    private fun parseSoapFault(xml: String): String? {
        return try {
            val faultStart = xml.indexOf("<faultstring>")
            val faultEnd = xml.indexOf("</faultstring>")
            if (faultStart >= 0 && faultEnd > faultStart) {
                xml.substring(faultStart + "<faultstring>".length, faultEnd)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

sealed class WolResult {
    object Success : WolResult()
    object AuthError : WolResult()
    object Unreachable : WolResult()
    data class Error(val message: String) : WolResult()
}
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add FritzBoxTR064Client with SOAP WoL and connection test"
```

---

### Task 3: Rewire PowerRepository to use TR-064

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`

- [ ] **Step 1: Replace FritzBoxApi with TR064Client and PreferencesManager**

Replace the entire file content with:

```kotlin
package com.baluhost.android.data.repository

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.network.FritzBoxTR064Client
import com.baluhost.android.data.network.WolResult
import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject

class PowerRepositoryImpl @Inject constructor(
    private val sleepApi: SleepApi,
    private val fritzBoxClient: FritzBoxTR064Client,
    private val preferencesManager: PreferencesManager
) : PowerRepository {

    override suspend fun sendWol(): Result<String> {
        return try {
            val host = preferencesManager.getFritzBoxHost().first()
            val port = preferencesManager.getFritzBoxPort().first()
            val username = preferencesManager.getFritzBoxUsername().first()
            val password = preferencesManager.getFritzBoxPassword() ?: ""
            val mac = preferencesManager.getFritzBoxMacAddress().first()

            if (mac.isEmpty()) {
                return Result.Error(Exception("Fritz!Box nicht konfiguriert — MAC-Adresse fehlt"))
            }

            when (val result = fritzBoxClient.sendWol(host, port, username, password, mac)) {
                is WolResult.Success -> Result.Success("WoL-Signal gesendet")
                is WolResult.AuthError -> Result.Error(Exception("Fritz!Box Zugangsdaten ungültig"))
                is WolResult.Unreachable -> Result.Error(Exception("Fritz!Box nicht erreichbar. VPN aktiv?"))
                is WolResult.Error -> Result.Error(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.Error(Exception("WoL fehlgeschlagen: ${e.message}", e))
        }
    }

    override suspend fun sendSoftSleep(): Result<String> {
        return try {
            val response = sleepApi.sendSoftSleep()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Sleep fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun sendSuspend(): Result<String> {
        return try {
            val response = sleepApi.sendSuspend()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Suspend fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }
}
```

- [ ] **Step 2: Remove FritzBoxApi from NetworkModule**

In `app/src/main/java/com/baluhost/android/di/NetworkModule.kt`, remove the `provideFritzBoxApi` function and its import.

- [ ] **Step 3: Delete FritzBoxApi.kt**

Delete `app/src/main/java/com/baluhost/android/data/remote/api/FritzBoxApi.kt` — it's no longer used.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): rewire PowerRepository.sendWol() to use TR-064 client directly"
```

---

### Task 4: Fritz!Box Settings ViewModel

**Files:**
- Create: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/FritzBoxSettingsViewModel.kt`

- [ ] **Step 1: Create ViewModel**

```kotlin
package com.baluhost.android.presentation.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.network.FritzBoxTR064Client
import com.baluhost.android.data.network.WolResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FritzBoxSettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val tr064Client: FritzBoxTR064Client
) : ViewModel() {

    private val _uiState = MutableStateFlow(FritzBoxSettingsUiState())
    val uiState: StateFlow<FritzBoxSettingsUiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val host = preferencesManager.getFritzBoxHost().first()
            val port = preferencesManager.getFritzBoxPort().first()
            val username = preferencesManager.getFritzBoxUsername().first()
            val mac = preferencesManager.getFritzBoxMacAddress().first()
            val hasPassword = preferencesManager.getFritzBoxPassword() != null

            _uiState.value = FritzBoxSettingsUiState(
                host = host,
                port = port.toString(),
                username = username,
                macAddress = mac,
                hasPassword = hasPassword
            )
        }
    }

    fun updateHost(host: String) {
        _uiState.value = _uiState.value.copy(host = host)
    }

    fun updatePort(port: String) {
        _uiState.value = _uiState.value.copy(port = port)
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun updateMacAddress(mac: String) {
        _uiState.value = _uiState.value.copy(macAddress = mac.uppercase())
    }

    fun save() {
        val state = _uiState.value
        val port = state.port.toIntOrNull()

        if (port == null || port < 1 || port > 65535) {
            viewModelScope.launch { _snackbarEvent.emit("Ungültiger Port (1-65535)") }
            return
        }

        val macRegex = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        if (state.macAddress.isNotEmpty() && !macRegex.matches(state.macAddress)) {
            viewModelScope.launch { _snackbarEvent.emit("Ungültiges MAC-Format (XX:XX:XX:XX:XX:XX)") }
            return
        }

        viewModelScope.launch {
            preferencesManager.saveFritzBoxHost(state.host)
            preferencesManager.saveFritzBoxPort(port)
            preferencesManager.saveFritzBoxUsername(state.username)
            preferencesManager.saveFritzBoxMacAddress(state.macAddress)
            if (state.password.isNotEmpty()) {
                preferencesManager.saveFritzBoxPassword(state.password)
            }
            _uiState.value = _uiState.value.copy(hasPassword = state.password.isNotEmpty() || state.hasPassword)
            _snackbarEvent.emit("Einstellungen gespeichert")
        }
    }

    fun testConnection() {
        val state = _uiState.value
        val port = state.port.toIntOrNull() ?: 49000
        val password = if (state.password.isNotEmpty()) state.password
            else preferencesManager.getFritzBoxPassword() ?: ""

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true)
            val result = tr064Client.testConnection(state.host, port, state.username, password)
            _uiState.value = _uiState.value.copy(isTesting = false)

            when (result) {
                is WolResult.Success -> _snackbarEvent.emit("Verbindung erfolgreich!")
                is WolResult.AuthError -> _snackbarEvent.emit("Zugangsdaten ungültig")
                is WolResult.Unreachable -> _snackbarEvent.emit("Fritz!Box nicht erreichbar. VPN aktiv?")
                is WolResult.Error -> _snackbarEvent.emit("Fehler: ${result.message}")
            }
        }
    }
}

data class FritzBoxSettingsUiState(
    val host: String = "192.168.178.1",
    val port: String = "49000",
    val username: String = "",
    val password: String = "",
    val macAddress: String = "",
    val hasPassword: Boolean = false,
    val isTesting: Boolean = false
)
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add FritzBoxSettingsViewModel"
```

---

### Task 5: Fritz!Box Settings Screen + Strings

**Files:**
- Create: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/FritzBoxSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings to strings.xml**

Add before `</resources>`:

```xml
    <!-- Fritz!Box Settings -->
    <string name="fritzbox_settings_title">Fritz!Box Einstellungen</string>
    <string name="fritzbox_host">Host</string>
    <string name="fritzbox_port">Port</string>
    <string name="fritzbox_username">Benutzername</string>
    <string name="fritzbox_password">Passwort</string>
    <string name="fritzbox_mac_address">NAS MAC-Adresse</string>
    <string name="fritzbox_test_connection">Verbindung testen</string>
    <string name="fritzbox_save">Speichern</string>
    <string name="fritzbox_description">Konfiguriere die Fritz!Box-Verbindung für direktes Wake-on-LAN, wenn das NAS schläft.</string>
```

- [ ] **Step 2: Create FritzBoxSettingsScreen**

```kotlin
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
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add FritzBoxSettingsScreen with connection test"
```

---

### Task 6: Navigation + Settings Link

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/navigation/Screen.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: Add route to Screen.kt**

Add after `NotificationPreferences` (after line 37):

```kotlin
    object FritzBoxSettings : Screen("fritzbox_settings")
```

- [ ] **Step 2: Add composable to NavGraph.kt**

Add import at top:

```kotlin
import com.baluhost.android.presentation.ui.screens.settings.FritzBoxSettingsScreen
```

Add after the `NotificationPreferences` composable block (after line 242):

```kotlin
        composable(Screen.FritzBoxSettings.route) {
            FritzBoxSettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
```

- [ ] **Step 3: Add navigation callback to SettingsScreen**

Add `onNavigateToFritzBox` parameter to `SettingsScreen` function signature (after `onNavigateToNotificationPreferences`):

```kotlin
    onNavigateToFritzBox: () -> Unit = {},
```

- [ ] **Step 4: Add Fritz!Box card to SettingsScreen (admin-only)**

In the SettingsScreen composable, collect admin state. Add after the `showPinDialog` remember:

```kotlin
    val isAdmin by viewModel.isAdmin.collectAsState()
```

This requires adding `isAdmin` to the SettingsViewModel — see step 5.

Then add a Fritz!Box navigation card AFTER the "ORDNER-SYNCHRONISATION" card (after the `onNavigateToFolderSync` GlassCard block, around line 361) and BEFORE the "VPN-EINSTELLUNGEN" card. Wrap it in an `if (isAdmin)` block:

```kotlin
                // Fritz!Box Settings Card (admin only)
                if (isAdmin) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium,
                        onClick = onNavigateToFritzBox
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "FRITZ!BOX",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate500,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Wake-on-LAN Konfiguration",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate400
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Öffnen",
                                tint = Sky400
                            )
                        }
                    }
                }
```

- [ ] **Step 5: Add isAdmin to SettingsViewModel**

In `SettingsViewModel.kt`, add admin state. Add a `_isAdmin` / `isAdmin` StateFlow and load role in init, same pattern as DashboardViewModel:

```kotlin
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()
```

In `init`, add:
```kotlin
        viewModelScope.launch {
            val role = preferencesManager.getUserRole().first() ?: "user"
            _isAdmin.value = role == "admin"
        }
```

- [ ] **Step 6: Wire navigation in MainScreen or NavGraph caller**

Find where `SettingsScreen` is called and add the `onNavigateToFritzBox` callback. This will be in the `MainScreen` composable. Add:

```kotlin
onNavigateToFritzBox = {
    parentNavController.navigate(Screen.FritzBoxSettings.route)
}
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add Fritz!Box settings navigation and admin-only menu entry"
```

---

### Task 7: Dashboard WoL Button Gating

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Add Fritz!Box config status to DashboardViewModel**

Add a new StateFlow after the existing `_isAdmin` declarations:

```kotlin
    private val _isFritzBoxConfigured = MutableStateFlow(false)
    val isFritzBoxConfigured: StateFlow<Boolean> = _isFritzBoxConfigured.asStateFlow()
```

In the `loadUserRole()` method, also load Fritz!Box config status:

```kotlin
    private fun loadUserRole() {
        viewModelScope.launch {
            val role = preferencesManager.getUserRole().first() ?: "user"
            _isAdmin.value = role == "admin"
            _isFritzBoxConfigured.value = preferencesManager.isFritzBoxConfigured().first()
        }
    }
```

- [ ] **Step 2: Pass config status to ServerStatusStrip**

In `DashboardScreen`, collect the new state:

```kotlin
    val isFritzBoxConfigured by viewModel.isFritzBoxConfigured.collectAsState()
```

Update the `ServerStatusStrip` call to pass it:

```kotlin
                    ServerStatusStrip(
                        isOnline = uiState.telemetry != null,
                        uptimeSeconds = uiState.telemetry?.uptime?.toLong(),
                        isAdmin = isAdmin,
                        isActionInProgress = powerActionInProgress,
                        isFritzBoxConfigured = isFritzBoxConfigured,
                        onSendWol = { viewModel.sendWol() },
                        onSendSoftSleep = { viewModel.sendSoftSleep() },
                        onSendSuspend = { viewModel.sendSuspend() }
                    )
```

- [ ] **Step 3: Update ServerStatusStrip to gate WoL**

Add `isFritzBoxConfigured: Boolean` parameter to `ServerStatusStrip`.

In the offline branch of the power dialog, show a hint if not configured instead of the WoL button:

```kotlin
                    if (!isOnline) {
                        if (isFritzBoxConfigured) {
                            PowerOptionButton(
                                icon = Icons.Default.WbSunny,
                                label = "NAS aufwecken",
                                description = "Wake-on-LAN über Fritz!Box",
                                color = Green500,
                                onClick = {
                                    showPowerDialog = false
                                    confirmAction = PowerAction.WOL
                                }
                            )
                        } else {
                            Text(
                                text = "Fritz!Box in Einstellungen konfigurieren, um WoL zu nutzen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Slate400
                            )
                        }
                    }
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): gate WoL button on Fritz!Box config status"
```

---

### Task 8: Build Verification

- [ ] **Step 1: Run build**

```bash
cd /d/Programme\ \(x86\)/BaluApp && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation issues**

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: direct Fritz!Box WoL via TR-064 with admin settings screen"
```

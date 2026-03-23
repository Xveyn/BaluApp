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
        private const val HOST_ENTRY_ACTION = "GetSpecificHostEntry"
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

    private fun buildHostEntryEnvelope(macAddress: String): String {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
            " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$HOST_ENTRY_ACTION xmlns:u=\"$SERVICE_TYPE\">" +
            "<NewMACAddress>$macAddress</NewMACAddress>" +
            "</u:$HOST_ENTRY_ACTION>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    suspend fun checkHostActive(
        host: String,
        port: Int,
        username: String,
        password: String,
        macAddress: String
    ): WolResult = withContext(Dispatchers.IO) {
        try {
            val url = "http://$host:$port$CONTROL_URL"
            val body = buildHostEntryEnvelope(macAddress)
            val client = buildClient(username, password)

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(XML_MEDIA_TYPE))
                .header("SOAPAction", "\"$SERVICE_TYPE#$HOST_ENTRY_ACTION\"")
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
                else -> {
                    val active = parseNewActive(responseBody)
                    if (active) WolResult.Success else WolResult.Error("inactive")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Host check failed", e)
            WolResult.Unreachable
        } catch (e: Exception) {
            Log.e(TAG, "Host check unexpected error", e)
            WolResult.Error(e.message ?: "Unbekannter Fehler")
        }
    }

    private fun parseNewActive(xml: String): Boolean {
        return try {
            val start = xml.indexOf("<NewActive>")
            val end = xml.indexOf("</NewActive>")
            if (start >= 0 && end > start) {
                xml.substring(start + "<NewActive>".length, end).trim() == "1"
            } else false
        } catch (e: Exception) {
            false
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

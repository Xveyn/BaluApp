package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.dto.SleepConfigDto
import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.repository.SleepConfigRepository
import com.baluhost.android.util.Result
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.inject.Inject

class SleepConfigRepositoryImpl @Inject constructor(
    private val sleepApi: SleepApi
) : SleepConfigRepository {

    override suspend fun getAlwaysAwake(): Result<AlwaysAwake> = request {
        sleepApi.getSleepConfig()
    }

    override suspend fun setAlwaysAwake(enabled: Boolean, until: Instant?): Result<AlwaysAwake> =
        request { sleepApi.updateSleepConfig(buildBody(enabled, until)) }

    private suspend fun request(call: suspend () -> SleepConfigDto): Result<AlwaysAwake> {
        return try {
            Result.Success(call().toAlwaysAwake())
        } catch (e: HttpException) {
            val message =
                if (e.code() == 403) "Nur Admins dürfen das ändern"
                else "Always-Awake fehlgeschlagen: ${e.readableMessage()}"
            Result.Error(Exception(message, e))
        } catch (e: DateTimeParseException) {
            // A timestamp we cannot read is a broken contract, not a permanent
            // override. Saying so beats rendering "dauerhaft aktiv" at a user
            // whose override actually expires tonight.
            Result.Error(Exception("Unerwartetes Zeitformat vom Server", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    /**
     * `HttpException.message()` is only the HTTP reason phrase — not the response
     * body — and OkHttp reports an empty string for it on HTTP/2. The response
     * body is where the server's actual explanation lives (e.g. a 422's `detail`
     * naming exactly which validation failed), so read that first and only fall
     * back to the reason phrase if the body is empty or fails to read.
     */
    private fun HttpException.readableMessage(): String {
        val body = try {
            response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        if (body.isNullOrBlank()) return message()
        val detail = try {
            JsonParser.parseString(body).asJsonObject.get("detail")?.takeIf { !it.isJsonNull }?.asString
        } catch (_: Exception) {
            null
        }
        return detail ?: body
    }

    /**
     * Builds the request body by hand.
     *
     * The server applies partial updates from model_dump(exclude_unset=True) and
     * special-cases this one field: an explicit null clears the expiry and means
     * "permanent", while an omitted key leaves the previous expiry untouched.
     * Gson's reflective serialisation drops null fields, and GsonConverterFactory's
     * writer skips a JsonNull property too — JsonElement.toString() does not.
     */
    private fun buildBody(enabled: Boolean, until: Instant?): RequestBody {
        val json = JsonObject().apply {
            addProperty("always_awake_enabled", enabled)
            if (enabled) {
                if (until == null) add("always_awake_until", JsonNull.INSTANCE)
                // Instant.toString() is ISO-8601 with a trailing Z, which is what
                // the webapp sends and what the server's validator expects.
                else addProperty("always_awake_until", until.toString())
            }
            // Disabling needs no expiry: the server clears it on its own.
        }
        return json.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun SleepConfigDto.toAlwaysAwake() = AlwaysAwake(
        enabled = alwaysAwakeEnabled,
        until = alwaysAwakeUntil?.let(::parseServerInstant)
    )

    /**
     * The server normalises naive timestamps to UTC itself, so a response is not
     * guaranteed to carry an offset. Both shapes are accepted; anything else
     * throws and surfaces as an error rather than being guessed at.
     */
    private fun parseServerInstant(raw: String): Instant =
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

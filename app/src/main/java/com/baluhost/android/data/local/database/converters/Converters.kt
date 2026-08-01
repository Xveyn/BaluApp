package com.baluhost.android.data.local.database.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant

/**
 * Room type converters for database entities.
 */
class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? {
        return value?.let { Instant.ofEpochMilli(it) }
    }

    @TypeConverter
    fun instantToTimestamp(instant: Instant?): Long? {
        return instant?.toEpochMilli()
    }

    @TypeConverter
    fun fromStringAnyMap(value: Map<String, Any>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringAnyMap(value: String?): Map<String, Any>? {
        if (value == null) return null
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson(value, type)
        } catch (_: Exception) {
            // A row written by an older build, or a truncated value. Losing the
            // metadata beats failing the whole query.
            null
        }
    }
}

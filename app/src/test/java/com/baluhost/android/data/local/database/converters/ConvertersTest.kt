package com.baluhost.android.data.local.database.converters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `a null metadata map round-trips as null`() {
        assertNull(converters.fromStringAnyMap(null))
        assertNull(converters.toStringAnyMap(null))
    }

    @Test
    fun `a metadata map survives the round trip`() {
        val original = mapOf("disk" to "sda2", "count" to 3.0)

        val restored = converters.toStringAnyMap(converters.fromStringAnyMap(original))

        assertEquals(original, restored)
    }

    @Test
    fun `unparseable stored metadata yields null instead of throwing`() {
        // A row written by an older build, or a truncated value. Losing the
        // metadata is acceptable; crashing the query is not.
        assertNull(converters.toStringAnyMap("{not json"))
    }
}

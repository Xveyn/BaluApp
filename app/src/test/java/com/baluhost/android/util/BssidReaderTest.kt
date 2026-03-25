package com.baluhost.android.util

import org.junit.Assert.*
import org.junit.Test

class BssidReaderTest {

    @Test
    fun `normalizeBssid uppercases and keeps colons`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BssidReader.normalizeBssid("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `normalizeBssid returns null for Android placeholder`() {
        assertNull(BssidReader.normalizeBssid("02:00:00:00:00:00"))
    }

    @Test
    fun `normalizeBssid returns null for null input`() {
        assertNull(BssidReader.normalizeBssid(null))
    }

    @Test
    fun `normalizeBssid returns null for empty string`() {
        assertNull(BssidReader.normalizeBssid(""))
    }

    @Test
    fun `normalizeBssid returns null for blank string`() {
        assertNull(BssidReader.normalizeBssid("   "))
    }

    @Test
    fun `normalizeBssid preserves already uppercase BSSID`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BssidReader.normalizeBssid("AA:BB:CC:DD:EE:FF"))
    }
}

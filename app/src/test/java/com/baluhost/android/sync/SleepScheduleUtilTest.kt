package com.baluhost.android.sync

import com.baluhost.android.util.SleepScheduleUtil
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class SleepScheduleUtilTest {

    @Test
    fun `overnight window - during sleep returns true`() {
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

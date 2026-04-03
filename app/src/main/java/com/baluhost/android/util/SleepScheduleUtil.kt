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

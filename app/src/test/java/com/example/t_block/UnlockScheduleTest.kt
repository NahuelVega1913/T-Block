package com.example.t_block

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockScheduleTest {
    @Test
    fun allowsOnlyTenMinutesAfterStart() {
        assertTrue(UnlockSchedule.isWithinWindow(22 * 60, 22 * 60))
        assertTrue(UnlockSchedule.isWithinWindow(22 * 60, 22 * 60 + 9))
        assertFalse(UnlockSchedule.isWithinWindow(22 * 60, 22 * 60 + 10))
    }

    @Test
    fun supportsWindowAcrossMidnight() {
        assertTrue(UnlockSchedule.isWithinWindow(23 * 60 + 55, 4))
        assertFalse(UnlockSchedule.isWithinWindow(23 * 60 + 55, 15))
    }

    @Test
    fun configurationLockEndsTenMinutesAfterNextOccurrence() {
        val now = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 13, 21, 59, 30)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val end = UnlockSchedule.windowEndMillis(now, 22, 0)
        val expected = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 22)
            set(java.util.Calendar.MINUTE, 10)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis

        assertTrue(end == expected)
    }
}
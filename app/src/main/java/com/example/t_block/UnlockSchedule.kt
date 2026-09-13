package com.example.t_block

object UnlockSchedule {
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val WINDOW_MINUTES = 10

    fun windowEndMillis(nowMillis: Long, hour: Int, minute: Int): Long {
        val now = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMillis
        }
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis < nowMillis) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            add(java.util.Calendar.MINUTE, WINDOW_MINUTES)
        }
        return start.timeInMillis
    }

    fun isWithinWindow(startMinute: Int, currentMinute: Int): Boolean {
        if (startMinute !in 0 until 24 * 60 || currentMinute !in 0 until 24 * 60) {
            return false
        }

        val elapsed = (currentMinute - startMinute + 24 * 60) % (24 * 60)
        return elapsed < WINDOW_MINUTES
    }
}
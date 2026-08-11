package com.example.calendaralarm

/** Injectable clock — null in production (uses real time), set by tests. */
internal object AppClock {
    @Volatile var override: Long? = null
    fun now(): Long = override ?: System.currentTimeMillis()
}

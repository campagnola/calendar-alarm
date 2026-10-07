package com.example.calendaralarm

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Injectable clock — null in production (uses real time), set by tests. */
internal object AppClock {
    @Volatile var override: Long? = null
    fun now(): Long = override ?: System.currentTimeMillis()
    fun nowLocal(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now()), ZoneId.systemDefault())
}

package com.example.calendaralarm

import kotlin.math.pow

/**
 * The global alarm sound: [uri] (null for none) starts [delayMs] after an alarm starts ringing, then
 * fades from [startVolume] to [maxVolume] over [fadeMs], at a constant rate in decibels. Volumes are
 * percentages of the alarm stream volume; the fade starts no lower than [FADE_RANGE_DB] below [maxVolume].
 */
data class AlarmSound(
    val uri: String?,
    val delayMs: Long,
    val startVolume: Int,
    val maxVolume: Int,
    val fadeMs: Long
) {
    init {
        require(delayMs >= 0 && fadeMs >= 0) { "negative sound timing: $this" }
        require(startVolume in 0..100 && maxVolume in 0..100) { "sound volume out of range: $this" }
    }

    /** Volume (0..1) [elapsedMs] after ringing started; null while the sound is off (no sound, or still delayed). */
    fun volumeAt(elapsedMs: Long): Float? {
        if (uri == null || elapsedMs < delayMs) return null
        val progress = if (elapsedMs - delayMs >= fadeMs) 1f else (elapsedMs - delayMs).toFloat() / fadeMs
        val to = maxVolume / 100f
        val from = maxOf(startVolume / 100f, to * 10f.pow(-FADE_RANGE_DB / 20f))
        if (from == 0f) return 0f  // both volumes are 0
        return from * (to / from).pow(progress)
    }

    /** Ms after ringing started at which the volume next changes; null once it is constant. */
    fun nextChangeAt(elapsedMs: Long): Long? = when {
        uri == null                    -> null
        elapsedMs < delayMs            -> delayMs
        elapsedMs < delayMs + fadeMs   -> minOf(elapsedMs + FADE_STEP_MS, delayMs + fadeMs)
        else                           -> null
    }

    companion object {
        const val FADE_STEP_MS = 250L
        const val FADE_RANGE_DB = 40f
        val DEFAULT = AlarmSound(uri = null, delayMs = 60_000L, startVolume = 0, maxVolume = 50, fadeMs = 60_000L)
    }
}

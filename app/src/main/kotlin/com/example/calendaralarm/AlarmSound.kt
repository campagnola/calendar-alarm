package com.example.calendaralarm

/**
 * The global alarm sound: [uri] (null for none) starts [delayMs] after an alarm starts ringing, then
 * fades from [startVolume] to [maxVolume] over [fadeMs]. Volumes are percentages of the alarm stream volume.
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
        return (startVolume + (maxVolume - startVolume) * progress) / 100f
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
        val DEFAULT = AlarmSound(uri = null, delayMs = 60_000L, startVolume = 0, maxVolume = 50, fadeMs = 60_000L)
    }
}

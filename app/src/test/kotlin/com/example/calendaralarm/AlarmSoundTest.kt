package com.example.calendaralarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmSoundTest {

    private val sound = AlarmSound(uri = "content://alarm", delayMs = 60_000L, startVolume = 10, maxVolume = 50, fadeMs = 40_000L)

    @Test fun `silent during the delay`() {
        assertNull(sound.volumeAt(0L))
        assertNull(sound.volumeAt(59_999L))
        assertEquals(60_000L, sound.nextChangeAt(0L))
    }

    @Test fun `fades from the start to the max volume evenly in decibels, then holds`() {
        assertEquals(0.10f, sound.volumeAt(60_000L)!!, 1e-6f)
        assertEquals(0.2236068f, sound.volumeAt(80_000L)!!, 1e-6f)  // geometric mean of 0.10 and 0.50
        assertEquals(0.50f, sound.volumeAt(100_000L)!!, 1e-6f)
        assertEquals(0.50f, sound.volumeAt(10 * 60_000L)!!, 1e-6f)
    }

    @Test fun `fade steps end exactly at the end of the fade`() {
        assertEquals(60_000L + AlarmSound.FADE_STEP_MS, sound.nextChangeAt(60_000L))
        assertEquals(100_000L, sound.nextChangeAt(99_900L))
        assertNull(sound.nextChangeAt(100_000L))
    }

    @Test fun `a zero start volume fades in from 40 dB below the max`() {
        val fromSilence = sound.copy(startVolume = 0)
        assertEquals(0.005f, fromSilence.volumeAt(60_000L)!!, 1e-6f)
        assertEquals(0.05f, fromSilence.volumeAt(80_000L)!!, 1e-6f)
        assertEquals(0f, fromSilence.copy(maxVolume = 0).volumeAt(80_000L)!!, 0f)
    }

    @Test fun `no fade jumps to the max volume`() {
        val abrupt = sound.copy(fadeMs = 0L)
        assertEquals(0.50f, abrupt.volumeAt(60_000L)!!, 1e-6f)
        assertNull(abrupt.nextChangeAt(60_000L))
    }

    @Test fun `no sound never plays or schedules`() {
        val none = sound.copy(uri = null)
        assertNull(none.volumeAt(90_000L))
        assertNull(none.nextChangeAt(0L))
    }
}

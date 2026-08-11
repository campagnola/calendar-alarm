package com.example.calendaralarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Regression test for Bug B: PollReceiver skips scheduleAll whenever the ICS cache timestamp
 * is unchanged (maxCacheTs <= lastAlarmScheduleTs), so events that enter the 25-hour window
 * after the first poll are never scheduled.
 *
 * The fix should ensure that time advancing into a new event's window causes scheduleAll to
 * run even when the ICS content has not changed.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmSchedulingTest {

    private lateinit var savedTz: TimeZone
    private lateinit var sim: AlarmSimulator

    private val june18 = LocalDate.of(2026, 6, 18)
    private val june19 = june18.plusDays(1)

    private fun utcMs(date: LocalDate, h: Int, m: Int = 0) =
        date.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val startMs           = utcMs(june18, 7, 0)   // device boots at 07:00 on the 18th
    private val expectedTriggerMs = utcMs(june19, 8, 55)  // alarm should fire 5 min before 09:00

    private val baseIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:standup@test.example
        DTSTART:20260619T090000Z
        DTEND:20260619T110000Z
        SUMMARY:Morning Standup
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Before fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        sim = AlarmSimulator()
        sim.setIcsContent(baseIcs)
        sim.setUp(startMs)
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedTz)
        sim.tearDown()
    }

    @Test fun `verify AppClock override controls production clock`() {
        val target = utcMs(june18, 7, 0)
        AppClock.override = target
        assertEquals("AppClock.now() should return the override value", target, AppClock.now())
        AppClock.override = null
    }

    @Test fun `event alarm fires on time when ICS is stable and event enters parse window after boot`() {
        // Boot at 07:00 Jun 18. The 25-hour parse window is [07:00 Jun 18, 08:00 Jun 19].
        // The event at 09:00 Jun 19 is just outside this window on the first poll.
        // As time advances, the event enters the window, and the alarm should fire at 08:55.
        // Bug B: every subsequent poll skips scheduleAll because the ICS file hasn't changed,
        // so the alarm is never scheduled and fires late (or not at all).
        sim.boot()
        sim.runUntil(utcMs(june19, 9, 0))

        assertEquals("alarm fired exactly once", 1, sim.firedAlarms().size)
        assertEquals("standup@test.example", sim.firedAlarms()[0].uid)
        assertEquals(
            "alarm fired at 08:55, 5 minutes before the 09:00 event",
            expectedTriggerMs, sim.firedAlarms()[0].firedAtMs
        )
    }
}

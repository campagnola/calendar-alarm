package com.example.calendaralarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Button
import com.example.calendaralarm.AlarmInstance.State
import com.example.calendaralarm.AlarmSimulator.Companion.ics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmSimulatorScenarioTest {

    private lateinit var savedTz: TimeZone
    private lateinit var sim: AlarmSimulator
    private val context get() = RuntimeEnvironment.getApplication()

    private val june17 = LocalDate.of(2026, 6, 17)
    private val june18 = LocalDate.of(2026, 6, 18)
    private val june19 = LocalDate.of(2026, 6, 19)
    private val june22 = LocalDate.of(2026, 6, 22)  // Monday

    private fun utcMs(date: LocalDate, h: Int, m: Int = 0) =
        date.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        sim = AlarmSimulator()
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedTz)
        sim.tearDown()
    }

    // ── Test 1: Rescheduled after dismiss ─────────────────────────────────────────
    // If an event is rescheduled to a new time after the alarm was dismissed,
    // the alarm fires again because DTSTART changes → alarm key changes.

    @Test fun `alarm fires again when event is rescheduled to a later time after being dismissed`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:meeting@test.example
            DTSTART:20260618T090000Z
            DTEND:20260618T100000Z
            SUMMARY:Team Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val rescheduledIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:meeting@test.example
            DTSTART:20260618T100000Z
            DTEND:20260618T110000Z
            SUMMARY:Team Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(originalIcs)
        sim.setUp(utcMs(june18, 7, 0))
        sim.boot()

        // Original alarm fires at 08:55 (5 min before 09:00 start)
        sim.runUntil(utcMs(june18, 9, 0))
        assertEquals("original alarm fired once", 1, sim.firedAlarms().size)
        assertEquals("original alarm is for the team meeting", "meeting@test.example", sim.firedAlarms()[0].uid)
        sim.dismiss(sim.firedAlarms()[0].alarmId)

        // Calendar reschedules the event to 10:00 AM — same UID, new start time.
        // The alarm key encodes start and end ms, so the dismissed instance no longer matches.
        sim.setIcsContent(rescheduledIcs)
        sim.runUntil(utcMs(june18, 10, 5))

        assertEquals("second alarm fired for the rescheduled event", 2, sim.firedAlarms().size)
        val second = sim.firedAlarms()[1]
        assertEquals("rescheduled alarm is for the same UID", "meeting@test.example", second.uid)
        assertTrue(
            "rescheduled alarm fires at or after 09:55 (5 min before new 10:00 start)",
            second.firedAtMs >= utcMs(june18, 9, 55)
        )
    }

    // ── Test 2: Quiet hours ───────────────────────────────────────────────────────
    // Events that start during quiet hours (22:00–07:00) receive their alarm
    // at the preceding start of the quiet period, not at the normal advance time.

    @Test fun `event starting during quiet hours receives alarm at quiet hours start`() {
        // 22:30 is inside quiet hours 22:00–07:00.
        // quietHoursStartFor(22:30) = 22:00 same day.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:late-meeting@test.example
            DTSTART:20260618T223000Z
            DTEND:20260618T233000Z
            SUMMARY:Late Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(ics)
        sim.setUp(utcMs(june18, 18, 0))
        sim.boot()
        sim.runUntil(utcMs(june18, 22, 5))

        assertEquals("one quiet-hours alarm fired", 1, sim.firedAlarms().size)
        val alarm = sim.firedAlarms()[0]
        assertEquals("alarm is for the late meeting", "late-meeting@test.example", alarm.uid)
        assertFalse("quiet-hours early alarm is not silent", alarm.silent)
        assertEquals(
            "alarm fired exactly at quiet hours start (22:00)",
            utcMs(june18, 22, 0), alarm.firedAtMs
        )
    }

    // ── Test 3: Recurring event from a year ago ───────────────────────────────────
    // A weekly series that began a year ago still produces a timely alarm
    // for the current week's occurrence.

    @Test fun `recurring weekly event that started a year ago still raises alarm`() {
        // Series started 2025-06-16 (Monday). 2026-06-22 is also a Monday (53 weeks later).
        // No COUNT/UNTIL so the parser expands as far as needed (up to the 3000-iteration cap).
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:weekly-standup@test.example
            DTSTART:20250616T090000Z
            DTEND:20250616T100000Z
            SUMMARY:Weekly Standup
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(ics)
        sim.setUp(utcMs(june22, 7, 0))
        sim.boot()
        sim.runUntil(utcMs(june22, 9, 5))

        assertEquals("one alarm fired for the recurring event", 1, sim.firedAlarms().size)
        val alarm = sim.firedAlarms()[0]
        assertEquals("alarm is for the weekly standup", "weekly-standup@test.example", alarm.uid)
        assertTrue(
            "alarm fired close to 08:55 (5 min before 09:00)",
            alarm.firedAtMs in utcMs(june22, 8, 55)..utcMs(june22, 9, 1)
        )
    }

    // ── Test 4: Event in a non-UTC timezone ───────────────────────────────────────
    // An event defined in America/New_York fires its alarm at the correct UTC time.

    @Test fun `event defined in America New York timezone fires alarm at correct UTC time`() {
        // 09:00 America/New_York (EDT = UTC-4) = 13:00 UTC.
        // Alarm trigger: 13:00 - 5 min = 12:55 UTC.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:ny-meeting@test.example
            DTSTART;TZID=America/New_York:20260619T090000
            DTEND;TZID=America/New_York:20260619T100000
            SUMMARY:New York Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(ics)
        sim.setUp(utcMs(june19, 11, 0))  // boot at 11:00 UTC; event (13:00 UTC) is in the 25-hour window
        sim.boot()
        sim.runUntil(utcMs(june19, 13, 5))

        assertEquals("one alarm fired for the NY meeting", 1, sim.firedAlarms().size)
        val alarm = sim.firedAlarms()[0]
        assertEquals("alarm is for the NY meeting", "ny-meeting@test.example", alarm.uid)
        assertEquals(
            "alarm fired at 12:55 UTC (= 08:55 AM EDT, 5 min before 09:00 AM EDT)",
            utcMs(june19, 12, 55), alarm.firedAtMs
        )
    }

    // ── Test 5: Dismissed instance blocks re-fire ─────────────────────────────────
    // Changing quiet hours re-plans the event, but its dismissed alarm instance keeps it from firing again.

    @Test fun `a dismissed alarm does not fire again when quiet hours change`() {
        // Event at 06:30 Jun 18. Default quiet hours are 22:00–07:00 (EARLY mode).
        // 06:30 falls inside quiet hours → alarm is scheduled at 22:00 Jun 17
        // (the preceding start of the quiet period that contains 06:30 AM).
        sim.setIcsContent(ics(Triple("early-bird@test.example", "20260618T063000Z", "20260618T073000Z")))
        sim.setUp(utcMs(june17, 18, 0))
        sim.boot()

        sim.runUntil(utcMs(june17, 22, 5))
        assertEquals("quiet-hours alarm fired", 1, sim.firedAlarms().size)
        assertEquals(utcMs(june17, 22, 0), sim.firedAlarms()[0].firedAtMs)
        val id = sim.firedAlarms()[0].alarmId
        sim.dismiss(id)

        // With quietHoursEnd = 06:00, 06:30 is outside quiet hours, so a fresh plan would ring at 06:25.
        Prefs(context).quietHoursEndMinutes = 6 * 60
        sim.runUntil(utcMs(june18, 6, 45))

        assertEquals("the dismissed alarm does not fire again", 1, sim.firedAlarms().size)
        assertEquals(State.DISMISSED, sim.instance(id).state)
    }

    // ── Alarm instance lifecycle ──────────────────────────────────────────────────
    // Boot at 07:00 Jun 18; polls run every 15 minutes from then on.

    private val standup = Triple("standup", "20260618T090000Z", "20260618T100000Z")   // rings 08:55
    private val review  = Triple("review", "20260618T090200Z", "20260618T100000Z")    // rings 08:57
    private val retro   = Triple("retro", "20260618T093000Z", "20260618T100000Z")     // rings 09:25

    private fun bootWith(vararg events: Triple<String, String, String>) {
        sim.setIcsContent(ics(*events))
        sim.setUp(utcMs(june18, 7, 0))
        sim.boot()
    }

    private fun idOf(uid: String) = sim.firedAlarms().last { it.uid == uid }.alarmId

    @Test fun `a ringing alarm rings until the ring timeout makes it missed`() {
        bootWith(standup)
        sim.runUntil(utcMs(june18, 8, 56))
        val id = idOf("standup")
        assertEquals(listOf(id), sim.ringingIds())
        assertEquals(setOf(id), sim.notifiedRingingIds())
        assertTrue(sim.serviceRunning())
        assertTrue(sim.isVibrating())

        sim.runUntil(utcMs(june18, 9, 6))

        assertEquals(State.MISSED, sim.instance(id).state)
        assertEquals(emptySet<String>(), sim.notifiedRingingIds())
        assertFalse(sim.serviceRunning())
        assertFalse(sim.isVibrating())
        val missed = sim.missedNotification(id)!!
        assertEquals("Missed alarm: standup", missed.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, missed.flags and Notification.FLAG_ONGOING_EVENT)
        sim.runUntil(utcMs(june18, 9, 50))
        assertEquals("a missed alarm does not fire again", 1, sim.firedAlarms().size)
    }

    @Test fun `swiping the notification keeps the alarm ringing and re-posts it under the same id`() {
        bootWith(standup)
        sim.runUntil(utcMs(june18, 8, 56))
        val id = idOf("standup")

        sim.swipeNotification(id)

        assertEquals(State.RINGING, sim.instance(id).state)
        assertNotNull(sim.ringingNotification(id))
        assertEquals(setOf(id), sim.notifiedRingingIds())
    }

    @Test fun `process killed while ringing is restored to ringing by the next poll`() {
        bootWith(standup)
        sim.runUntil(utcMs(june18, 8, 56))
        val id = idOf("standup")

        sim.killProcess()
        assertNull(sim.ringingNotification(id))
        assertEquals(State.RINGING, sim.instance(id).state)

        sim.runUntil(utcMs(june18, 9, 1))  // poll at 09:00
        assertTrue(sim.serviceRunning())
        assertEquals(setOf(id), sim.notifiedRingingIds())
        assertEquals("restoring is not a second alarm", 1, sim.firedAlarms().size)
    }

    @Test fun `process killed while ringing is restored by the sticky service restart`() {
        bootWith(standup, review)
        sim.runUntil(utcMs(june18, 8, 58))
        val ids = setOf(idOf("standup"), idOf("review"))

        sim.killProcess()
        assertFalse(sim.serviceRunning())
        sim.restartKilledService()

        assertTrue(sim.serviceRunning())
        assertTrue(sim.isVibrating())
        assertEquals(ids, sim.notifiedRingingIds())
        assertEquals(ids, sim.ringingIds().toSet())
    }

    @Test fun `reboot with a pending snooze re-arms the snooze`() {
        bootWith(standup)
        sim.runUntil(utcMs(june18, 8, 56))
        val id = idOf("standup")
        sim.snooze(id)  // until 08:59: 3/4 of the 5 minutes from ringing to the start, but at least 4 minutes
        assertEquals(State.SNOOZED, sim.instance(id).state)
        assertNull(sim.ringingNotification(id))

        sim.runUntil(utcMs(june18, 8, 57))
        sim.reboot()
        assertTrue(id in sim.pendingTriggerIds())

        sim.runUntil(utcMs(june18, 9, 1))
        assertEquals(State.RINGING, sim.instance(id).state)
        assertEquals(listOf(utcMs(june18, 8, 55), utcMs(june18, 8, 59)), sim.firedAlarms().map { it.firedAtMs })
        assertEquals(setOf(id), sim.notifiedRingingIds())
    }

    @Test fun `the snooze time shown is the snooze time used`() {
        bootWith(retro)
        sim.runUntil(utcMs(june18, 9, 25))
        val id = idOf("retro")
        val label = sim.ringingNotification(id)!!.actions.single {
            Shadows.shadowOf(it.actionIntent).savedIntent.action == ActionReceiver.ACTION_SNOOZE
        }.title.toString()

        sim.runUntil(utcMs(june18, 9, 27))
        sim.snooze(id)

        assertEquals("Snooze until ${formatTime(context, sim.instance(id).triggerMs)}", label)
    }

    @Test fun `two ringing alarms have their own notifications and are dismissed independently`() {
        bootWith(standup, review)
        sim.runUntil(utcMs(june18, 8, 58))
        val (first, second) = listOf(idOf("standup"), idOf("review"))
        assertEquals(setOf(first, second), sim.ringingIds().toSet())
        assertEquals(setOf(first, second), sim.notifiedRingingIds())
        assertNotEquals(NotificationHelper.ringingNotificationId(first), NotificationHelper.ringingNotificationId(second))

        sim.dismiss(second)
        assertEquals(listOf(first), sim.ringingIds())
        assertEquals(setOf(first), sim.notifiedRingingIds())
        assertTrue(sim.serviceRunning())
        assertTrue(sim.isVibrating())

        sim.dismiss(first)
        assertEquals(emptyList<String>(), sim.ringingIds())
        assertEquals(emptySet<String>(), sim.notifiedRingingIds())
        assertFalse(sim.serviceRunning())
        assertFalse(sim.isVibrating())
    }

    @Test fun `dismiss from a stale notification action does not dismiss a newer alarm`() {
        bootWith(standup, retro)
        sim.runUntil(utcMs(june18, 8, 56))
        val old = idOf("standup")
        val staleDismiss = sim.actionIntent(old, ActionReceiver.ACTION_DISMISS)
        sim.dismiss(old)

        sim.runUntil(utcMs(june18, 9, 26))
        val newer = idOf("retro")
        sim.deliverAction(staleDismiss)

        assertEquals(listOf(newer), sim.ringingIds())
        assertEquals(setOf(newer), sim.notifiedRingingIds())
    }

    @Test fun `dismiss from the notification closes the alarm screen`() {
        bootWith(standup)
        sim.runUntil(utcMs(june18, 8, 56))
        val id = idOf("standup")
        val screen = Robolectric.buildActivity(AlarmActivity::class.java,
            Intent(context, AlarmActivity::class.java).putExtra(EXTRA_ALARM_ID, id)).setup().get()
        assertFalse(screen.isFinishing)

        sim.dismiss(id)

        assertTrue(screen.isFinishing)
    }

    @Test fun `dismiss from a stale alarm screen does not dismiss a newer alarm`() {
        bootWith(standup, retro)
        sim.runUntil(utcMs(june18, 8, 56))
        val old = idOf("standup")
        val staleScreen = Robolectric.buildActivity(AlarmActivity::class.java,
            Intent(context, AlarmActivity::class.java).putExtra(EXTRA_ALARM_ID, old)).setup().get()
        sim.dismiss(old)  // from the notification

        sim.runUntil(utcMs(june18, 9, 26))
        val newer = idOf("retro")
        staleScreen.findViewById<Button>(R.id.dismissButton).performClick()

        assertEquals(listOf(newer), sim.ringingIds())
        assertEquals(setOf(newer), sim.notifiedRingingIds())
    }

    @Test fun `an alarm whose service fails to start is not treated as handled`() {
        bootWith(standup)
        sim.failNextServiceStart()
        sim.runUntil(utcMs(june18, 8, 56))
        val id = idOf("standup")
        assertEquals(1, sim.deliveryFailures().size)
        assertEquals(State.RINGING, sim.instance(id).state)
        assertFalse(sim.serviceRunning())

        sim.runUntil(utcMs(june18, 9, 1))  // poll at 09:00 retries

        assertTrue(sim.serviceRunning())
        assertEquals(setOf(id), sim.notifiedRingingIds())
    }

    @Test fun `alarms raised before the upgrade are migrated as dismissed and do not fire again`() {
        val startMs = utcMs(june18, 9, 0)
        context.getSharedPreferences("cal_alarm", Context.MODE_PRIVATE).edit()
            .putStringSet("raised_alarm_keys", setOf(AlarmKey.of("standup", startMs, utcMs(june18, 10, 0))))
            .putStringSet("active_alarm_codes", setOf("123"))
            .commit()

        migrateLegacyAlarmState(context)
        bootWith(standup)
        sim.runUntil(utcMs(june18, 9, 30))

        assertEquals(emptyList<FiredAlarm>(), sim.firedAlarms())
        assertNull(Prefs(context).legacyAlarmState())
    }

    @Test fun `migration cancels the legacy snooze and alarm notification`() {
        val am = context.getSystemService(AlarmManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        sim.setUp(utcMs(june18, 8, 56))
        val legacySnooze = PendingIntent.getBroadcast(context, "standup".hashCode() xor 0xABCD,
            Intent(context, AlarmReceiver::class.java).putExtra("events_json", "[]"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, utcMs(june18, 9, 0), legacySnooze)
        nm.notify(42, Notification.Builder(context, NotificationHelper.CHANNEL_ALARM).build())
        context.getSharedPreferences("cal_alarm", Context.MODE_PRIVATE).edit()
            .putStringSet("raised_alarm_keys", setOf(AlarmKey.of("standup", utcMs(june18, 9, 0), utcMs(june18, 10, 0))))
            .commit()

        migrateLegacyAlarmState(context)

        assertEquals(emptyList<Any>(), Shadows.shadowOf(am).scheduledAlarms)
        assertNull(Shadows.shadowOf(nm).getNotification(42))
    }

    @Test fun `migration skips raised keys from older key formats`() {
        sim.setUp(utcMs(june18, 8, 56))
        val current = AlarmKey.of("standup", utcMs(june18, 9, 0), utcMs(june18, 10, 0))
        context.getSharedPreferences("cal_alarm", Context.MODE_PRIVATE).edit()
            .putStringSet("raised_alarm_keys", setOf(current, "040000008200E000", "uid_${utcMs(june18, 9, 0)}"))
            .commit()

        migrateLegacyAlarmState(context)

        assertNull(Prefs(context).legacyAlarmState())
        assertEquals(1, AlarmStore.from(context).all().size)
    }
}

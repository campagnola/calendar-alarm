package com.example.calendaralarm

import com.example.calendaralarm.AlarmEffect.CancelRingTimeout
import com.example.calendaralarm.AlarmEffect.Ring
import com.example.calendaralarm.AlarmEffect.ScheduleRingTimeout
import com.example.calendaralarm.AlarmEffect.ScheduleTrigger
import com.example.calendaralarm.AlarmEffect.ShowMissed
import com.example.calendaralarm.AlarmEffect.StopRinging
import com.example.calendaralarm.AlarmInstance.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val noon = LocalDateTime.of(2026, 6, 18, 12, 0)
    private val now = noon.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val min = 60_000L

    private lateinit var file: File
    private val applied = mutableListOf<AlarmEffect>()
    private val controller get() = AlarmController(AlarmStore(file), { applied.add(it) }, snoozeMinutes = 4)

    /** A fresh store on the same file: what a new process would read. */
    private fun persisted() = AlarmStore(file).all()
    private fun persisted(id: String) = persisted().single { it.id == id }

    private fun event(uid: String, startHour: Int = 13) =
        CalendarEvent(uid, uid, noon.withHour(startHour), noon.withHour(startHour + 1), isAllDay = false)

    private fun sync(vararg events: CalendarEvent) =
        controller.syncFromCalendar("test", events.toList()) { handled ->
            planAlarms(events.toList(), emptyMap(), 5, true, 22 * 60, 7 * 60, AppClock.now(), handled)
        }

    private fun idOf(event: CalendarEvent) = AlarmInstance.idFor(listOf(AlarmEvent.of(event)))

    /** Plans [event] and delivers its trigger; returns its id. */
    private fun ring(event: CalendarEvent): String {
        sync(event)
        controller.onTrigger(idOf(event))
        return idOf(event)
    }

    @Before fun setUp() {
        file = File(tmp.root, "alarms.json")
        AppClock.override = now
    }

    @After fun tearDown() {
        AppClock.override = null
    }

    @Test fun `sync persists planned alarms and schedules their triggers`() {
        val event = event("a")
        sync(event)
        assertEquals(State.SCHEDULED, persisted(idOf(event)).state)
        assertEquals(listOf(ScheduleTrigger(idOf(event), now + 55 * min)), applied)
    }

    @Test fun `trigger persists the ringing state and rings`() {
        val id = ring(event("a"))
        assertEquals(State.RINGING, persisted(id).state)
        assertEquals(listOf(ScheduleRingTimeout(id, now + AlarmStateMachine.RING_TIMEOUT_MS), Ring(id)),
                     applied.drop(1))
    }

    @Test fun `a ringing alarm is not planned again`() {
        val event = event("a")
        ring(event)
        var handled = emptySet<String>()
        controller.syncFromCalendar("test", listOf(event)) { handled = it; emptyList() }
        assertEquals(setOf(AlarmEvent.of(event).key), handled)
        assertEquals(State.RINGING, persisted(idOf(event)).state)
    }

    @Test fun `dismiss with a stale id leaves the ringing alarm alone`() {
        val first = ring(event("first"))
        controller.dismiss(first)
        val second = ring(event("second", startHour = 14))
        applied.clear()

        controller.dismiss(first)
        controller.dismiss("no-such-id")

        assertEquals(emptyList<AlarmEffect>(), applied)
        assertEquals(State.RINGING, persisted(second).state)
    }

    @Test fun `two ringing alarms are dismissed independently`() {
        val first = ring(event("first"))
        val second = ring(event("second", startHour = 14))
        applied.clear()

        controller.dismiss(second)

        assertEquals(listOf(CancelRingTimeout(second), StopRinging(second)), applied)
        assertEquals(listOf(first), persisted().filter { it.state == State.RINGING }.map { it.id })
    }

    @Test fun `snooze persists the snooze time`() {
        val id = ring(event("a"))
        controller.snooze(id)
        val snoozedUntil = now + 45 * min  // 3/4 of the hour until the 13:00 start
        assertEquals(State.SNOOZED, persisted(id).state)
        assertEquals(snoozedUntil, persisted(id).triggerMs)
        assertEquals(ScheduleTrigger(id, snoozedUntil), applied.last())
    }

    @Test fun `reconcile re-arms a snooze and restores a ringing alarm`() {
        val snoozedEvent = event("snoozed")
        val ringingEvent = event("ringing", startHour = 14)
        val (snoozed, ringing) = listOf(idOf(snoozedEvent), idOf(ringingEvent))
        sync(snoozedEvent, ringingEvent)
        controller.onTrigger(snoozed)
        controller.snooze(snoozed)
        controller.onTrigger(ringing)
        applied.clear()

        controller.reconcile("boot")

        assertEquals(setOf(ScheduleTrigger(snoozed, now + 45 * min),
                           ScheduleRingTimeout(ringing, now + AlarmStateMachine.RING_TIMEOUT_MS), Ring(ringing)),
                     applied.toSet())
    }

    @Test fun `ring timeout makes the alarm missed`() {
        val id = ring(event("a"))
        AppClock.override = now + AlarmStateMachine.RING_TIMEOUT_MS
        controller.ringTimeout(id)
        assertEquals(State.MISSED, persisted(id).state)
        assertEquals(listOf(StopRinging(id), ShowMissed(persisted(id))), applied.takeLast(2))
    }

    @Test fun `swiping the notification re-posts it`() {
        val id = ring(event("a"))
        applied.clear()
        controller.onNotificationDeleted(id)
        assertEquals(listOf(Ring(id)), applied)
        assertEquals(State.RINGING, persisted(id).state)
    }

    @Test fun `a failed ring keeps the alarm ringing so reconcile retries it`() {
        val event = event("a")
        sync(event)
        val failing = AlarmController(AlarmStore(file), { if (it is Ring) error("service start failed") }, 4)

        assertThrows(IllegalStateException::class.java) { failing.onTrigger(idOf(event)) }

        assertEquals(State.RINGING, persisted(idOf(event)).state)
        applied.clear()
        controller.reconcile("poll")
        assertEquals(Ring(idOf(event)), applied.last())
    }

    @Test fun `migrated keys are dismissed and block planning`() {
        val event = event("a")
        controller.seedDismissed(setOf(AlarmEvent.of(event).key))
        sync(event)
        assertEquals(listOf(State.DISMISSED), persisted().map { it.state })
        assertEquals(emptyList<AlarmEffect>(), applied)
    }
}

package com.example.calendaralarm

import com.example.calendaralarm.AlarmEffect.CancelRingTimeout
import com.example.calendaralarm.AlarmEffect.CancelTrigger
import com.example.calendaralarm.AlarmEffect.Ring
import com.example.calendaralarm.AlarmEffect.ScheduleRingTimeout
import com.example.calendaralarm.AlarmEffect.ScheduleTrigger
import com.example.calendaralarm.AlarmEffect.ShowMissed
import com.example.calendaralarm.AlarmEffect.ShowSilent
import com.example.calendaralarm.AlarmEffect.StopRinging
import com.example.calendaralarm.AlarmInstance.Kind
import com.example.calendaralarm.AlarmInstance.State
import com.example.calendaralarm.AlarmStateMachine.RING_TIMEOUT_MS
import com.example.calendaralarm.AlarmStateMachine.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The transition table: one test per (state, event) row. */
class AlarmStateMachineTest {
    private val sm = AlarmStateMachine
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    private fun event(uid: String, startMs: Long = now + 10 * min) =
        AlarmEvent(uid, summary = uid, startMs = startMs, endMs = startMs + hour)

    private fun instance(state: State, kind: Kind = Kind.RING, events: List<AlarmEvent> = listOf(event("a")),
                         triggerMs: Long = now, updatedMs: Long = now - min) =
        AlarmInstance(AlarmInstance.idFor(events), events, kind, state, triggerMs, updatedMs)

    private val nonRinging = State.values().filter { it != State.RINGING }

    // ── trigger delivered ────────────────────────────────────────────────────

    @Test fun `trigger rings a scheduled or snoozed alarm and arms the ring timeout`() {
        for (state in listOf(State.SCHEDULED, State.SNOOZED)) {
            val inst = instance(state)
            val ringing = inst.copy(state = State.RINGING, updatedMs = now)
            assertEquals(Step(ringing, listOf(ScheduleRingTimeout(inst.id, now + RING_TIMEOUT_MS), Ring(inst.id))),
                         sm.trigger(inst, now))
        }
    }

    @Test fun `trigger of a silent alarm shows it and is done`() {
        val inst = instance(State.SCHEDULED, Kind.SILENT)
        val shown = inst.copy(state = State.DISMISSED, updatedMs = now)
        assertEquals(Step(shown, listOf(ShowSilent(shown))), sm.trigger(inst, now))
    }

    @Test fun `trigger after every event ended is missed`() {
        val inst = instance(State.SNOOZED, events = listOf(event("a", startMs = now - 2 * hour)))
        assertEquals(Step(inst.copy(state = State.MISSED, updatedMs = now), emptyList()), sm.trigger(inst, now))
    }

    @Test fun `trigger is ignored when ringing or finished`() {
        for (state in listOf(State.RINGING, State.DISMISSED, State.MISSED)) {
            assertNull(state.name, sm.trigger(instance(state), now))
        }
    }

    // ── user commands ────────────────────────────────────────────────────────

    @Test fun `dismiss ends a ringing alarm`() {
        val inst = instance(State.RINGING)
        assertEquals(Step(inst.copy(state = State.DISMISSED, updatedMs = now),
                          listOf(CancelRingTimeout(inst.id), StopRinging(inst.id))),
                     sm.dismiss(inst, now))
    }

    @Test fun `snooze stops ringing and schedules the alarm again`() {
        val inst = instance(State.RINGING)
        val until = now + 5 * min
        assertEquals(Step(inst.copy(state = State.SNOOZED, triggerMs = until, updatedMs = now),
                          listOf(CancelRingTimeout(inst.id), StopRinging(inst.id), ScheduleTrigger(inst.id, until))),
                     sm.snooze(inst, until, now))
    }

    @Test fun `commands for an alarm that is not ringing are ignored`() {
        for (state in nonRinging) {
            val inst = instance(state)
            assertNull("dismiss $state", sm.dismiss(inst, now))
            assertNull("snooze $state", sm.snooze(inst, now + min, now))
            assertNull("timeout $state", sm.ringTimeout(inst, now))
            assertNull("notification deleted $state", sm.notificationDeleted(inst))
        }
    }

    // ── ringing ends without the user ────────────────────────────────────────

    @Test fun `ring timeout makes a ringing alarm missed and says so`() {
        val inst = instance(State.RINGING)
        val missed = inst.copy(state = State.MISSED, updatedMs = now)
        assertEquals(Step(missed, listOf(StopRinging(inst.id), ShowMissed(missed))), sm.ringTimeout(inst, now))
    }

    @Test fun `deleting the notification keeps ringing and re-posts it`() {
        val inst = instance(State.RINGING)
        assertEquals(Step(inst, listOf(Ring(inst.id))), sm.notificationDeleted(inst))
    }

    // ── reconcile (boot, process start, poll) ────────────────────────────────

    @Test fun `reconcile re-arms scheduled and snoozed alarms`() {
        for (state in listOf(State.SCHEDULED, State.SNOOZED)) {
            val inst = instance(state, triggerMs = now + 3 * min)
            assertEquals(Step(inst, listOf(ScheduleTrigger(inst.id, now + 3 * min))), sm.reconcile(inst, now))
        }
    }

    @Test fun `reconcile restores a ringing alarm with its original timeout`() {
        val inst = instance(State.RINGING, updatedMs = now - 4 * min)
        assertEquals(Step(inst, listOf(ScheduleRingTimeout(inst.id, now + 6 * min), Ring(inst.id))),
                     sm.reconcile(inst, now))
    }

    @Test fun `reconcile of an alarm that rang past its timeout makes it missed`() {
        val inst = instance(State.RINGING, updatedMs = now - RING_TIMEOUT_MS)
        assertEquals(sm.ringTimeout(inst, now), sm.reconcile(inst, now))
    }

    @Test fun `reconcile leaves finished alarms alone`() {
        assertNull(sm.reconcile(instance(State.DISMISSED), now))
        assertNull(sm.reconcile(instance(State.MISSED), now))
    }

    // ── calendar sync ────────────────────────────────────────────────────────

    @Test fun `sync schedules planned alarms and drops scheduled alarms no longer planned`() {
        val kept    = instance(State.SCHEDULED, events = listOf(event("kept")), triggerMs = now + min)
        val dropped = instance(State.SCHEDULED, events = listOf(event("dropped")))
        val moved   = kept.copy(triggerMs = now + 2 * min, updatedMs = now)
        val added   = instance(State.SCHEDULED, events = listOf(event("new")), updatedMs = now)

        val outcome = sm.sync(listOf(kept, dropped), listOf(moved, added), setOf("kept", "new").keys(), now)

        assertEquals(listOf(moved, added), outcome.instances)
        assertEquals(setOf(CancelTrigger(dropped.id), ScheduleTrigger(moved.id, moved.triggerMs),
                           ScheduleTrigger(added.id, added.triggerMs)),
                     outcome.effects.toSet())
    }

    @Test fun `sync drops a snoozed alarm whose events left the calendar`() {
        val gone = instance(State.SNOOZED, events = listOf(event("gone")))
        val stays = instance(State.SNOOZED, events = listOf(event("stays")))
        val outcome = sm.sync(listOf(gone, stays), emptyList(), setOf("stays").keys(), now)
        assertEquals(listOf(stays), outcome.instances)
        assertEquals(listOf(CancelTrigger(gone.id)), outcome.effects)
    }

    @Test fun `sync never stops a ringing alarm`() {
        val ringing = instance(State.RINGING)
        assertEquals(AlarmStateMachine.Outcome(listOf(ringing), emptyList()),
                     sm.sync(listOf(ringing), emptyList(), emptySet(), now))
    }

    @Test fun `sync keeps finished alarms until two days after their events end`() {
        val recent = instance(State.DISMISSED, events = listOf(event("recent", startMs = now - 2 * 24 * hour)))
        val old    = instance(State.MISSED, events = listOf(event("old", startMs = now - 3 * 24 * hour)))
        assertEquals(listOf(recent), sm.sync(listOf(recent, old), emptyList(), emptySet(), now).instances)
    }

    @Test fun `handled keys are the events of every alarm past scheduling`() {
        val instances = State.values().map { instance(it, events = listOf(event(it.name))) }
        assertEquals(setOf("RINGING", "SNOOZED", "DISMISSED", "MISSED").keys(), sm.handledKeys(instances))
    }

    // ── snooze policy ────────────────────────────────────────────────────────

    @Test fun `snooze lasts three quarters of the time from ringing until the latest start, at least the snooze minutes`() {
        val rang = now - min
        val inst = instance(State.RINGING, events = listOf(event("a", rang + 4 * min), event("b", rang + 20 * min)),
                            updatedMs = rang)
        assertEquals(rang + 15 * min, sm.snoozeUntilMs(inst, now, snoozeMinutes = 4))
        assertEquals(rang + 16 * min, sm.snoozeUntilMs(inst, now, snoozeMinutes = 16))
        val started = instance(State.RINGING, events = listOf(event("a", rang - min)), updatedMs = rang)
        assertEquals(rang + 4 * min, sm.snoozeUntilMs(started, now, snoozeMinutes = 4))
    }

    @Test fun `snooze after the snooze time has passed lasts the snooze minutes from now`() {
        val inst = instance(State.RINGING, events = listOf(event("a", now - 6 * min)), updatedMs = now - 10 * min)
        assertEquals(now + 4 * min, sm.snoozeUntilMs(inst, now, snoozeMinutes = 4))
    }

    private fun Set<String>.keys() = map { event(it).key }.toSet()
}

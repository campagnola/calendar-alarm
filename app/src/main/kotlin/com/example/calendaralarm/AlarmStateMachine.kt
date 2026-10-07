package com.example.calendaralarm

import com.example.calendaralarm.AlarmEffect.CancelRingTimeout
import com.example.calendaralarm.AlarmEffect.CancelTrigger
import com.example.calendaralarm.AlarmEffect.Ring
import com.example.calendaralarm.AlarmEffect.ScheduleRingTimeout
import com.example.calendaralarm.AlarmEffect.ScheduleTrigger
import com.example.calendaralarm.AlarmEffect.ShowMissed
import com.example.calendaralarm.AlarmEffect.ShowSilent
import com.example.calendaralarm.AlarmEffect.StopRinging
import com.example.calendaralarm.AlarmInstance.State

/** A side effect a transition asks for; carried out by [AlarmEffects]. */
sealed interface AlarmEffect {
    data class ScheduleTrigger(val id: String, val atMs: Long) : AlarmEffect
    data class CancelTrigger(val id: String) : AlarmEffect
    data class ScheduleRingTimeout(val id: String, val atMs: Long) : AlarmEffect
    data class CancelRingTimeout(val id: String) : AlarmEffect
    /** Start ringing, or re-post the ringing notification. */
    data class Ring(val id: String) : AlarmEffect
    data class StopRinging(val id: String) : AlarmEffect
    data class ShowSilent(val instance: AlarmInstance) : AlarmEffect
    data class ShowMissed(val instance: AlarmInstance) : AlarmEffect
}

/** Pure alarm-instance transitions. A null result means the event does not apply in the current state. */
object AlarmStateMachine {
    const val RING_TIMEOUT_MS = 10 * 60_000L
    private const val TERMINAL_RETENTION_MS = 2 * 24 * 60 * 60_000L

    data class Step(val next: AlarmInstance, val effects: List<AlarmEffect>)
    data class Outcome(val instances: List<AlarmInstance>, val effects: List<AlarmEffect>)

    private fun AlarmInstance.moveTo(state: State, nowMs: Long) = copy(state = state, updatedMs = nowMs)

    fun trigger(inst: AlarmInstance, nowMs: Long): Step? {
        if (inst.state != State.SCHEDULED && inst.state != State.SNOOZED) return null
        if (inst.events.all { it.endMs <= nowMs }) return Step(inst.moveTo(State.MISSED, nowMs), emptyList())
        return when (inst.kind) {
            AlarmInstance.Kind.SILENT -> inst.moveTo(State.DISMISSED, nowMs).let { Step(it, listOf(ShowSilent(it))) }
            AlarmInstance.Kind.RING -> Step(inst.moveTo(State.RINGING, nowMs), ringEffects(inst.id, nowMs + RING_TIMEOUT_MS))
        }
    }

    fun dismiss(inst: AlarmInstance, nowMs: Long): Step? = ifRinging(inst) {
        Step(inst.moveTo(State.DISMISSED, nowMs), listOf(CancelRingTimeout(inst.id), StopRinging(inst.id)))
    }

    fun snooze(inst: AlarmInstance, untilMs: Long, nowMs: Long): Step? = ifRinging(inst) {
        Step(inst.moveTo(State.SNOOZED, nowMs).copy(triggerMs = untilMs),
             listOf(CancelRingTimeout(inst.id), StopRinging(inst.id), ScheduleTrigger(inst.id, untilMs)))
    }

    fun ringTimeout(inst: AlarmInstance, nowMs: Long): Step? = ifRinging(inst) {
        inst.moveTo(State.MISSED, nowMs).let { Step(it, listOf(StopRinging(inst.id), ShowMissed(it))) }
    }

    fun notificationDeleted(inst: AlarmInstance): Step? = ifRinging(inst) {
        Step(inst, listOf(Ring(inst.id)))
    }

    /** Re-establishes the side effects of the current state, e.g. after a reboot or process death. */
    fun reconcile(inst: AlarmInstance, nowMs: Long): Step? = when (inst.state) {
        State.SCHEDULED, State.SNOOZED -> Step(inst, listOf(ScheduleTrigger(inst.id, inst.triggerMs)))
        State.RINGING -> {
            val timeoutMs = inst.updatedMs + RING_TIMEOUT_MS
            if (nowMs >= timeoutMs) ringTimeout(inst, nowMs) else Step(inst, ringEffects(inst.id, timeoutMs))
        }
        State.DISMISSED, State.MISSED -> null
    }

    /**
     * Replaces the scheduled alarms with [planned]. A snoozed alarm is dropped once none of its events
     * remain in [calendarKeys]; a ringing alarm is left to the user or its timeout. Finished alarms are
     * kept (they block re-planning) until their events ended [TERMINAL_RETENTION_MS] ago.
     */
    fun sync(existing: List<AlarmInstance>, planned: List<AlarmInstance>,
             calendarKeys: Set<String>, nowMs: Long): Outcome {
        val plannedIds = planned.map { it.id }.toSet()
        val effects = mutableListOf<AlarmEffect>()
        val kept = existing.filter { inst ->
            val keep = when (inst.state) {
                State.SCHEDULED -> inst.id in plannedIds
                State.SNOOZED   -> inst.events.any { it.key in calendarKeys }
                State.RINGING   -> true
                State.DISMISSED, State.MISSED ->
                    inst.events.maxOf { it.endMs } >= nowMs - TERMINAL_RETENTION_MS
            }
            if (!keep && !inst.state.isTerminal) effects.add(CancelTrigger(inst.id))
            keep && inst.state != State.SCHEDULED
        }
        check(kept.none { it.id in plannedIds }) { "planned an alarm that was already raised" }
        planned.forEach { effects.add(ScheduleTrigger(it.id, it.triggerMs)) }
        return Outcome(kept + planned, effects)
    }

    /** Event keys that must not be planned again because their alarm already went off. */
    fun handledKeys(instances: List<AlarmInstance>): Set<String> =
        instances.filter { it.state != State.SCHEDULED }.flatMap { it.events }.map { it.key }.toSet()

    /**
     * When a snooze of the ringing [inst] ends: 3/4 of the time from when it started ringing until the
     * latest event start, but at least [snoozeMinutes]. It depends only on the instance, so the time
     * shown on the Snooze button is the time used, unless it has already passed: then [snoozeMinutes] from now.
     */
    fun snoozeUntilMs(inst: AlarmInstance, nowMs: Long, snoozeMinutes: Int): Long {
        val rangMs  = inst.updatedMs
        val eventMs = inst.events.maxOf { it.startMs }
        val minMs   = snoozeMinutes * 60_000L
        val untilMs = rangMs + if (eventMs > rangMs) maxOf((eventMs - rangMs) * 3 / 4, minMs) else minMs
        return if (untilMs > nowMs) untilMs else nowMs + minMs
    }

    private fun ringEffects(id: String, timeoutMs: Long) = listOf(ScheduleRingTimeout(id, timeoutMs), Ring(id))

    private inline fun ifRinging(inst: AlarmInstance, step: () -> Step): Step? =
        if (inst.state == State.RINGING) step() else null
}

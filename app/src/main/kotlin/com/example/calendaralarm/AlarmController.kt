package com.example.calendaralarm

import android.content.Context
import com.example.calendaralarm.AlarmStateMachine.Outcome
import com.example.calendaralarm.AlarmStateMachine.Step
import java.time.Instant

fun interface AlarmEffects {
    fun apply(effect: AlarmEffect)
}

/**
 * The only writer of [AlarmStore]. Each operation applies [AlarmStateMachine] transitions, commits
 * the result, then carries out the effects, all under the store lock so operations never interleave.
 * State is committed before effects run, so an effect that fails is retried by the next [reconcile].
 */
class AlarmController(
    private val store: AlarmStore,
    private val effects: AlarmEffects,
    private val snoozeMinutes: Int
) {
    fun onTrigger(id: String) = command(id, "trigger") { inst, now ->
        AppLog.i("Alarm $id: trigger due ${Instant.ofEpochMilli(inst.triggerMs)}, delay=${now - inst.triggerMs}ms")
        AlarmStateMachine.trigger(inst, now)
    }

    fun dismiss(id: String) = command(id, "dismiss", AlarmStateMachine::dismiss)

    fun snooze(id: String) = command(id, "snooze") { inst, now ->
        AlarmStateMachine.snooze(inst, AlarmStateMachine.snoozeUntilMs(inst, now, snoozeMinutes), now)
    }

    fun ringTimeout(id: String) = command(id, "ring timeout", AlarmStateMachine::ringTimeout)

    fun onNotificationDeleted(id: String) = command(id, "notification deleted") { inst, _ ->
        AlarmStateMachine.notificationDeleted(inst)
    }

    /** Re-establishes every alarm's side effects (AlarmManager entries, ringing) from the store. */
    fun reconcile(reason: String) = transact("reconcile: $reason") { all, now ->
        val steps = all.map { AlarmStateMachine.reconcile(it, now) ?: Step(it, emptyList()) }
        Outcome(steps.map { it.next }, steps.flatMap { it.effects })
    }

    /**
     * Replaces the scheduled alarms with the plan for [events]. [plan] receives the keys of events
     * whose alarm already went off; it runs under the store lock, so no alarm fires in between.
     */
    fun syncFromCalendar(reason: String, events: List<CalendarEvent>,
                         plan: (handledKeys: Set<String>) -> List<PlannedAlarm>) = transact("sync: $reason") { all, now ->
        val planned = plan(AlarmStateMachine.handledKeys(all)).map { AlarmInstance.scheduled(it, now) }
        AlarmStateMachine.sync(all, planned, events.map { AlarmEvent.of(it).key }.toSet(), now)
    }

    /** Records [keys] (alarms raised by an older app version) as dismissed. */
    fun seedDismissed(keys: Set<String>) = transact("legacy migration") { all, now ->
        val known = all.flatMap { it.events }.map { it.key }.toSet()
        Outcome(all + (keys - known).map { AlarmInstance.legacyDismissed(it, now) }, emptyList())
    }

    private fun command(id: String, reason: String, transition: (AlarmInstance, Long) -> Step?) =
        transact(reason) { all, now ->
            val inst = all.find { it.id == id }
            val step = inst?.let { transition(it, now) }
            if (step == null) {
                AppLog.w("Alarm $id: $reason ignored in state ${inst?.state ?: "unknown"}")
                Outcome(all, emptyList())
            } else {
                Outcome(all.map { if (it.id == id) step.next else it }, step.effects)
            }
        }

    private fun transact(reason: String, operation: (List<AlarmInstance>, Long) -> Outcome) = store.locked {
        val before = store.read()
        val outcome = operation(before, AppClock.now())
        if (outcome.instances != before) {
            store.write(outcome.instances)
            logChanges(before, outcome.instances, reason)
        }
        outcome.effects.forEach(effects::apply)
    }

    private fun logChanges(before: List<AlarmInstance>, after: List<AlarmInstance>, reason: String) {
        val old = before.associateBy { it.id }
        val new = after.associateBy { it.id }
        for (id in old.keys + new.keys) {
            val from = old[id]
            val to = new[id]
            if (from?.state == to?.state && from?.triggerMs == to?.triggerMs) continue
            val due = to?.takeIf { it.state == AlarmInstance.State.SCHEDULED || it.state == AlarmInstance.State.SNOOZED }
                ?.let { " due ${Instant.ofEpochMilli(it.triggerMs)}" } ?: ""
            val keys = (to ?: from)!!.events.map { it.key }
            AppLog.i("Alarm $id: ${from?.state ?: "new"} -> ${to?.state ?: "dropped"}$due ($reason) $keys")
        }
    }

    companion object {
        fun from(context: Context): AlarmController {
            val store = AlarmStore.from(context)
            return AlarmController(store, AndroidAlarmEffects(context, store), Prefs(context).snoozeMinutes)
        }
    }
}

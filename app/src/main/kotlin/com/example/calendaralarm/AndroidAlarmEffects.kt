package com.example.calendaralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Intent extra naming the [AlarmInstance] a component or command is for. */
const val EXTRA_ALARM_ID = "alarm_id"

/**
 * The alarm id [intent] carries. Null for an intent without one: those come from PendingIntents created
 * by app versions before [AlarmStore] (e.g. a snooze pending across the upgrade), and are ignored.
 */
fun alarmIdOf(intent: Intent, receiver: String): String? =
    intent.getStringExtra(EXTRA_ALARM_ID)
        ?: null.also { AppLog.w("$receiver: ignoring intent without alarm id from an older version: $intent") }

class AndroidAlarmEffects(private val context: Context, private val store: AlarmStore) : AlarmEffects {

    override fun apply(effect: AlarmEffect) {
        when (effect) {
            is AlarmEffect.ScheduleTrigger     -> setAlarm(AlarmReceiver.ACTION_TRIGGER, effect.id, effect.atMs)
            is AlarmEffect.CancelTrigger       -> cancelAlarm(AlarmReceiver.ACTION_TRIGGER, effect.id)
            is AlarmEffect.ScheduleRingTimeout -> setAlarm(AlarmReceiver.ACTION_RING_TIMEOUT, effect.id, effect.atMs)
            is AlarmEffect.CancelRingTimeout   -> cancelAlarm(AlarmReceiver.ACTION_RING_TIMEOUT, effect.id)
            is AlarmEffect.Ring                -> updateService(effect.id)
            is AlarmEffect.StopRinging         -> updateService(effect.id)
            is AlarmEffect.ShowSilent          -> NotificationHelper.showSilentAlarm(context, effect.instance)
            is AlarmEffect.ShowMissed          -> NotificationHelper.showMissedAlarm(context, effect.instance)
        }
    }

    private fun alarmIntent(action: String, id: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ALARM_ID, id),
        flags or PendingIntent.FLAG_IMMUTABLE
    )

    private fun setAlarm(action: String, id: String, atMs: Long) {
        val pi = checkNotNull(alarmIntent(action, id, PendingIntent.FLAG_UPDATE_CURRENT))
        AlarmScheduler.setExact(context, atMs, pi, "${action.substringAfterLast('.')} $id")
    }

    private fun cancelAlarm(action: String, id: String) {
        val pi = alarmIntent(action, id, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    // The service renders every ringing alarm from the store, so it only needs to know whether to run.
    private fun updateService(id: String) {
        val intent = Intent(context, AlarmService::class.java).putExtra(EXTRA_ALARM_ID, id)
        if (store.ringing().isEmpty()) context.stopService(intent) else context.startForegroundService(intent)
    }
}

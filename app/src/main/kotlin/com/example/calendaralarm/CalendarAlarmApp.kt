package com.example.calendaralarm

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class CalendarAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        migrateLegacyAlarmState(this)
    }
}

/**
 * Moves alarm state kept in prefs by older versions into [AlarmStore]: raised alarms become
 * DISMISSED instances so they do not fire again. Their AlarmManager entries (the next schedule sync
 * registers them anew), any pending snooze and the old alarm notification are cancelled.
 */
internal fun migrateLegacyAlarmState(context: Context) {
    val prefs = Prefs(context)
    val (allRaisedKeys, requestCodes) = prefs.legacyAlarmState() ?: return
    val (raisedKeys, oldFormatKeys) = allRaisedKeys.partition { AlarmKey.isCurrentFormat(it) }
    AppLog.i("Migrating legacy alarm state: ${raisedKeys.size} raised, ${oldFormatKeys.size} in older key formats " +
        "(dropped), ${requestCodes.size} registered")
    // Old snoozes were registered under `uid.hashCode() xor 0xABCD` and were not in requestCodes.
    val snoozeCodes = raisedKeys.map { AlarmKey.parse(it).first.hashCode() xor 0xABCD }
    val am = context.getSystemService(AlarmManager::class.java)
    for (code in requestCodes + snoozeCodes) {
        val pi = PendingIntent.getBroadcast(context, code, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: continue
        am.cancel(pi)
        pi.cancel()
    }
    context.getSystemService(NotificationManager::class.java).cancel(LEGACY_ALARM_NOTIFICATION_ID)
    AlarmController.from(context).seedDismissed(raisedKeys.toSet())
    prefs.clearLegacyAlarmState()
}

private const val LEGACY_ALARM_NOTIFICATION_ID = 42

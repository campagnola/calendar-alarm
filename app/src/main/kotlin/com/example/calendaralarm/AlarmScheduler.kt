package com.example.calendaralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant

/** AlarmManager access: exact alarms, and the poll chain that keeps the alarms in sync with the calendar. */
object AlarmScheduler {
    private const val POLL_REQUEST_CODE = 9001

    /**
     * Sets an exact alarm. Without the exact-alarm permission (only possible on API 31-32) the alarm
     * is set inexactly, which may make it late, and the user is asked to grant the permission.
     */
    fun setExact(context: Context, atMs: Long, pi: PendingIntent, what: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (!PermissionPrompt.EXACT_ALARM.isGranted(context)) {
            AppLog.e("AlarmManager: no exact-alarm permission, $what at ${Instant.ofEpochMilli(atMs)} may be late")
            NotificationHelper.showPermissionReminder(context, PermissionPrompt.EXACT_ALARM)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            return
        }
        AppLog.i("AlarmManager: $what at ${Instant.ofEpochMilli(atMs)}")
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    private fun pollIntent(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, POLL_REQUEST_CODE,
        Intent(context, PollReceiver::class.java),
        flags or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedulePoll(context: Context, delayMs: Long) {
        val pi = pollIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)!!
        setExact(context, AppClock.now() + delayMs, pi, "poll in ${delayMs / 1000}s")
    }

    /** Restarts the poll chain if it is gone (e.g. after a force-stop cleared all alarms). */
    fun ensurePollScheduled(context: Context) {
        if (pollIntent(context, PendingIntent.FLAG_NO_CREATE) != null) return
        AppLog.w("ensurePollScheduled: no pending poll, scheduling one now")
        schedulePoll(context, 0L)
    }
}

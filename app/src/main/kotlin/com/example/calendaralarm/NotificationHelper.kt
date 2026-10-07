package com.example.calendaralarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_REMINDER = "reminder"

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // Offsets from an alarm's hash giving each of its PendingIntents its own request code.
    private const val RC_DISMISS = 0
    private const val RC_SNOOZE  = 1
    private const val RC_SCREEN  = 2
    private const val RC_JOIN    = 3
    private const val RC_DELETE  = 4
    private const val RC_MAIN    = 5

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Event Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** The ongoing notification of a ringing alarm. */
    fun ringingNotificationId(alarmId: String): Int = "ringing:$alarmId".hashCode()

    /** The one-off notification of a silent or missed alarm. */
    fun noticeNotificationId(alarmId: String): Int = alarmId.hashCode()

    /** The reminder to grant [prompt]. */
    fun permissionNotificationId(prompt: PermissionPrompt): Int = 1 + prompt.ordinal

    fun showSilentAlarm(context: Context, instance: AlarmInstance) = postNotice(context, instance, titlePrefix = "")

    fun showMissedAlarm(context: Context, instance: AlarmInstance) =
        postNotice(context, instance, titlePrefix = "Missed alarm: ")

    /** Asks the user to grant [prompt]; re-posting it while shown does not alert again. */
    fun showPermissionReminder(context: Context, prompt: PermissionPrompt) {
        val id = permissionNotificationId(prompt)
        val settingsPi = PendingIntent.getActivity(context, id,
            prompt.settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PI_FLAGS)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(prompt.title)
            .setContentText(prompt.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt.message))
            .setContentIntent(settingsPi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        AppLog.i("NotificationHelper: permission reminder posted for $prompt")
    }

    fun buildRingingNotification(context: Context, instance: AlarmInstance): Notification {
        val presentation = AlarmPresentation(context, instance)
        fun actionPi(action: String, rc: Int) = PendingIntent.getBroadcast(context, requestCode(instance, rc),
            Intent(context, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ALARM_ID, instance.id),
            PI_FLAGS)
        val screenPi = PendingIntent.getActivity(context, requestCode(instance, RC_SCREEN),
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ALARM_ID, instance.id), PI_FLAGS)
        return builder(context, CHANNEL_ALARM, instance, presentation, presentation.title)
            .setContentIntent(screenPi)
            .setFullScreenIntent(screenPi, true)
            .addAction(0, "Dismiss", actionPi(ActionReceiver.ACTION_DISMISS, RC_DISMISS))
            .addAction(0, presentation.snoozeLabel, actionPi(ActionReceiver.ACTION_SNOOZE, RC_SNOOZE))
            .addJoinAction(context, instance, presentation)
            .setDeleteIntent(actionPi(ActionReceiver.ACTION_NOTIFICATION_DELETED, RC_DELETE))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** A finished alarm's notification: nothing to dismiss or snooze. */
    private fun postNotice(context: Context, instance: AlarmInstance, titlePrefix: String) {
        val presentation = AlarmPresentation(context, instance)
        val title = titlePrefix + presentation.title
        val mainPi = PendingIntent.getActivity(context, requestCode(instance, RC_MAIN),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PI_FLAGS)
        val notification = builder(context, CHANNEL_REMINDER, instance, presentation, title)
            .setContentIntent(mainPi)
            .addJoinAction(context, instance, presentation)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setSilent(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(noticeNotificationId(instance.id), notification)
        AppLog.i("NotificationHelper: '$title' posted for ${instance.id}")
    }

    private fun builder(context: Context, channel: String, instance: AlarmInstance,
                        presentation: AlarmPresentation, title: String): NotificationCompat.Builder {
        val text = listOfNotNull(presentation.subtitle, presentation.location).joinToString(" · ")
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
        if (instance.events.size > 1) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        return builder
    }

    // Opens the meeting from an activity, since Android 12+ blocks activity starts from a
    // receiver that a notification action started (notification trampolines).
    private fun NotificationCompat.Builder.addJoinAction(context: Context, instance: AlarmInstance,
                                                         presentation: AlarmPresentation) = apply {
        val url = presentation.meetingUrl ?: return@apply
        val joinPi = PendingIntent.getActivity(context, requestCode(instance, RC_JOIN),
            JoinActivity.intent(context, instance.id, url), PI_FLAGS)
        addAction(0, presentation.joinLabel, joinPi)
    }

    private fun requestCode(instance: AlarmInstance, offset: Int) = instance.id.hashCode() + offset
}

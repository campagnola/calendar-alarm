package com.example.calendaralarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.example.calendaralarm.AlarmInstance.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
class AlarmUiTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = shadowOf(RuntimeEnvironment.getApplication())
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val store get() = AlarmStore.from(context)

    @After fun tearDown() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    /** Plans and triggers an alarm for one event; its service start is left queued. Returns its id. */
    private fun ringAlarm(summary: String, silent: Boolean = false, meetingUrl: String? = null): String {
        val start = LocalDateTime.now().plusMinutes(5)
        val event = CalendarEvent(uid = "$summary@test", summary = summary, start = start,
                                  end = start.plusHours(1), isAllDay = false, meetingUrl = meetingUrl)
        val controller = AlarmController.from(context)
        controller.syncFromCalendar("test", listOf(event)) {
            listOf(PlannedAlarm(listOf(event), AppClock.now(), silent))
        }
        val id = AlarmInstance.idFor(listOf(AlarmEvent.of(event)))
        controller.onTrigger(id)
        return id
    }

    /** Runs the service for the queued alarm starts. */
    private fun startRingingService() {
        val service = Robolectric.buildService(AlarmService::class.java).create().get()
        while (true) service.onStartCommand(app.nextStartedService ?: break, 0, 1)
    }

    private fun ringingNotification(id: String) =
        shadowOf(nm).getNotification(NotificationHelper.ringingNotificationId(id))

    private fun Notification.action(title: String) = actions.single { it.title.toString().startsWith(title) }

    // ── Application / log ────────────────────────────────────────────────────

    @Test fun `log lines are written from process start with an offset timestamp`() {
        AppLog.i("hello from test")
        val line = File(context.filesDir, "calarm.log").readLines().last()
        assertTrue(line.endsWith("hello from test"))
        OffsetDateTime.parse(line.substringBefore(' '))
    }

    // ── Service and notifications ────────────────────────────────────────────

    @Test fun `swipe delivered after the alarm was dismissed does not resurrect the notification`() {
        val id = ringAlarm("Standup")
        startRingingService()
        val deleteIntent = shadowOf(ringingNotification(id).deleteIntent).savedIntent
        AlarmController.from(context).dismiss(id)
        app.clearStartedServices()

        ActionReceiver().onReceive(context, deleteIntent)

        assertNull(app.nextStartedService)
        assertEquals(State.DISMISSED, store.find(id)!!.state)
    }

    @Test fun `screen on re-posts the notification instead of launching the activity`() {
        val id = ringAlarm("Standup")
        startRingingService()
        nm.cancel(NotificationHelper.ringingNotificationId(id))
        app.clearNextStartedActivities()

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(app.nextStartedActivity)
        assertNotNull(ringingNotification(id))
    }

    @Test fun `a silent alarm does not replace a ringing alarm's notification`() {
        val ringing = ringAlarm("Standup")
        startRingingService()
        val silent = ringAlarm("Lunch", silent = true)

        assertEquals("Standup", ringingNotification(ringing).extras.getString(Notification.EXTRA_TITLE))
        val notice = shadowOf(nm).getNotification(NotificationHelper.noticeNotificationId(silent))
        assertEquals("Lunch", notice.extras.getString(Notification.EXTRA_TITLE))
    }

    // API 32 exercises the AudioAttributes path (the platform converts it to VibrationAttributes).
    @Test @Config(sdk = [32, 34])
    fun `vibration is classified as an alarm`() {
        ringAlarm("Standup")
        startRingingService()
        val attrs = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)
            .vibrationAttributesFromLastVibration as VibrationAttributes
        assertEquals(VibrationAttributes.USAGE_ALARM, attrs.usage)
    }

    @Test fun `ringing without the full-screen permission posts a reminder to grant it`() {
        assertFalse(PermissionPrompt.FULL_SCREEN_INTENT.isGranted(context))
        ringAlarm("Standup")
        startRingingService()

        val reminder = shadowOf(nm).getNotification(NotificationHelper.permissionNotificationId(PermissionPrompt.FULL_SCREEN_INTENT))
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                     shadowOf(reminder.contentIntent).savedIntent.action)
    }

    @Test @Config(sdk = [31])
    fun `without the exact alarm permission alarms are set inexactly and a reminder is posted`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val start = LocalDateTime.now().plusHours(1)
        val event = CalendarEvent("a@test", "A", start, start.plusHours(1), isAllDay = false)

        AlarmController.from(context).syncFromCalendar("test", listOf(event)) {
            listOf(PlannedAlarm(listOf(event), AppClock.now() + 60_000L, silent = false))
        }

        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        val reminder = shadowOf(nm).getNotification(NotificationHelper.permissionNotificationId(PermissionPrompt.EXACT_ALARM))
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                     shadowOf(reminder.contentIntent).savedIntent.action)
    }

    @Test fun `join opens the meeting from an activity and dismisses the alarm`() {
        val url = "https://zoom.us/j/1"
        val id = ringAlarm("Standup", meetingUrl = url)
        startRingingService()
        val join = ringingNotification(id).action("Join Zoom").actionIntent
        assertTrue("no broadcast trampoline", shadowOf(join).isActivity)

        Robolectric.buildActivity(JoinActivity::class.java, shadowOf(join).savedIntent).setup()

        assertEquals(State.DISMISSED, store.find(id)!!.state)
        val opened = app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(url, opened.dataString)
    }

    @Test fun `intents without an alarm id from older versions are ignored`() {
        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).putExtra("events_json", "[]"))
        ActionReceiver().onReceive(context, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_SNOOZE))
        assertNull(app.nextStartedService)
    }

    // ── Activity ─────────────────────────────────────────────────────────────

    private fun activityIntent(id: String) =
        Intent(context, AlarmActivity::class.java).putExtra(EXTRA_ALARM_ID, id)

    @Test fun `the alarm screen runs in its own task outside recents`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, AlarmActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals("${context.packageName}.alarm", info.taskAffinity)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test fun `back does not close the alarm screen or end the alarm`() {
        val id = ringAlarm("Standup")
        val activity = Robolectric.buildActivity(AlarmActivity::class.java, activityIntent(id)).setup().get()
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        assertEquals(State.RINGING, store.find(id)!!.state)
    }

    @Test fun `the alarm screen shows what the notification shows`() {
        val id = ringAlarm("Standup", meetingUrl = "https://teams.microsoft.com/l/1")
        startRingingService()
        val activity = Robolectric.buildActivity(AlarmActivity::class.java, activityIntent(id)).setup().get()
        val notification = ringingNotification(id)

        assertEquals(notification.extras.getString(Notification.EXTRA_TITLE),
                     activity.findViewById<TextView>(R.id.alarmTitle).text.toString())
        assertEquals(notification.action("Snooze").title.toString(),
                     activity.findViewById<Button>(R.id.snoozeButton).text.toString())
        assertEquals(notification.action("Join").title.toString(),
                     activity.findViewById<Button>(R.id.joinButton).text.toString())
    }

    @Test fun `new intent re-renders the alarm screen with the new alarm`() {
        val controller = Robolectric.buildActivity(AlarmActivity::class.java, activityIntent(ringAlarm("First"))).setup()
        controller.newIntent(activityIntent(ringAlarm("Second")))
        assertEquals("Second", controller.get().findViewById<TextView>(R.id.alarmTitle).text.toString())
    }

    @Test fun `the alarm screen closes itself when its alarm is no longer ringing`() {
        val id = ringAlarm("Standup")
        AlarmController.from(context).snooze(id)
        val activity = Robolectric.buildActivity(AlarmActivity::class.java, activityIntent(id)).setup().get()
        assertTrue(activity.isFinishing)
    }

    @Test fun `dismiss on the alarm screen ends the alarm and closes the screen`() {
        val id = ringAlarm("Standup")
        val activity = Robolectric.buildActivity(AlarmActivity::class.java, activityIntent(id)).setup().get()
        activity.findViewById<Button>(R.id.dismissButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(State.DISMISSED, store.find(id)!!.state)
        assertTrue(activity.isFinishing)
    }

    @Test fun `alarm screen without an alarm id fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            Robolectric.buildActivity(AlarmActivity::class.java,
                Intent(context, AlarmActivity::class.java)).setup()
        }
    }

    @Test fun `opening the app while an alarm rings shows that alarm`() {
        val id = ringAlarm("Standup")
        Robolectric.buildActivity(MainActivity::class.java).setup()
        val started = app.nextStartedActivity
        assertEquals(AlarmActivity::class.java.name, started.component?.className)
        assertEquals(id, started.getStringExtra(EXTRA_ALARM_ID))
    }
}

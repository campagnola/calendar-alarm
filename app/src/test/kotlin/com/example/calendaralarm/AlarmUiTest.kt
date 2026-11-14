package com.example.calendaralarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibratorManager
import android.provider.Settings
import android.text.Spanned
import android.text.style.URLSpan
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.IOException
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
class AlarmUiTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = shadowOf(RuntimeEnvironment.getApplication())
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val store get() = AlarmStore.from(context)

    @After fun tearDown() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AppClock.override = null
    }

    /** Plans and triggers an alarm for one event; its service start is left queued. Returns its id. */
    private fun ringAlarm(summary: String, silent: Boolean = false, meetingUrl: String? = null): String {
        val start = Instant.now().plus(Duration.ofMinutes(5))
        val event = CalendarEvent(uid = "$summary@test", summary = summary, start = start,
                                  end = start.plus(Duration.ofHours(1)), isAllDay = false, meetingUrl = meetingUrl)
        val controller = AlarmController.from(context)
        controller.syncFromCalendar("test", listOf(event)) {
            listOf(PlannedAlarm(listOf(event), AppClock.now(), silent))
        }
        val id = AlarmInstance.idFor(listOf(AlarmEvent.of(event)))
        controller.onTrigger(id)
        return id
    }

    /** Runs the service for the queued alarm starts. */
    private fun startRingingService(): ServiceController<AlarmService> =
        Robolectric.buildService(AlarmService::class.java).create().also { deliverStarts(it) }

    private fun deliverStarts(service: ServiceController<AlarmService>) {
        while (true) service.get().onStartCommand(app.nextStartedService ?: break, 0, 1)
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
        val start = Instant.now().plus(Duration.ofHours(1))
        val event = CalendarEvent("a@test", "A", start, start.plus(Duration.ofHours(1)), isAllDay = false)

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
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val started = app.nextStartedActivity
            assertEquals(AlarmActivity::class.java.name, started.component?.className)
            assertEquals(id, started.getStringExtra(EXTRA_ALARM_ID))
        } finally {
            main.pause().stop().destroy()  // stops its FeedSync.attempts collector before later tests
        }
    }

    @Test fun `event details show URLs as links in selectable text`() {
        val start = Instant.now()
        val event = CalendarEvent(uid = "links@test", summary = "Links", start = start,
                                  end = start.plus(Duration.ofHours(1)), isAllDay = false,
                                  location = "https://example.com/room",
                                  description = "Agenda at https://example.com/agenda")
        val intent = Intent(context, EventDetailActivity::class.java)
            .putExtra(EventDetailActivity.EXTRA_EVENT_JSON, event.toJson())
        val detail = Robolectric.buildActivity(EventDetailActivity::class.java, intent).setup().get()

        for (viewId in listOf(R.id.detailDescription, R.id.detailLocation)) {
            val view = detail.findViewById<TextView>(viewId)
            assertTrue(view.isTextSelectable)
            val links = (view.text as Spanned).getSpans(0, view.text.length, URLSpan::class.java)
            assertEquals(1, links.size)
        }
        assertEquals("https://example.com/agenda",
            (detail.findViewById<TextView>(R.id.detailDescription).text as Spanned)
                .getSpans(0, Int.MAX_VALUE, URLSpan::class.java).single().url)
    }

    // ── Sound ────────────────────────────────────────────────────────────────

    private val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    private val mediaPlayers = mutableListOf<MediaPlayer>()

    /** Players the app creates, in order. */
    private fun soundSetup(uri: Uri? = alarmUri): List<ShadowMediaPlayer> {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, alarmUri), ShadowMediaPlayer.MediaInfo(3_600_000, 0))
        Prefs(context).alarmSound = AlarmSound.DEFAULT.copy(uri = uri?.toString(), startVolume = 10)
        AppClock.override = System.currentTimeMillis()
        val players = mutableListOf<ShadowMediaPlayer>()
        ShadowMediaPlayer.setCreateListener { player, shadow -> players.add(shadow); mediaPlayers.add(player) }
        return players
    }

    /** Advances the wall clock and runs what the main looper had scheduled up to then. */
    private fun advance(seconds: Long) {
        AppClock.override = AppClock.now() + seconds * 1000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    private val ShadowMediaPlayer.isPlaying get() = state == ShadowMediaPlayer.State.STARTED

    @Test fun `the sound starts after its delay as an alarm and fades to its max volume`() {
        val players = soundSetup()
        ringAlarm("Standup")
        startRingingService()

        advance(59)
        assertTrue(players.isEmpty())
        advance(1)
        val player = players.single()
        assertTrue(player.isPlaying)
        assertTrue(mediaPlayers.single().isLooping)
        assertEquals(0.10f, player.leftVolume, 1e-6f)
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest
        assertEquals(AudioAttributes.USAGE_ALARM, focus.audioFocusRequest.audioAttributes.usage)

        advance(30)
        assertEquals(0.30f, player.leftVolume, 1e-6f)
        advance(60)
        assertEquals(0.50f, player.leftVolume, 1e-6f)
        assertEquals(1, players.size)
    }

    @Test fun `dismissing stops the sound and it does not come back`() {
        val players = soundSetup()
        val id = ringAlarm("Standup")
        val service = startRingingService()
        advance(70)
        assertTrue(players.single().isPlaying)

        AlarmController.from(context).dismiss(id)
        service.destroy()
        advance(10 * 60)

        assertEquals(ShadowMediaPlayer.State.END, players.single().state)
        assertEquals(1, players.size)
    }

    @Test fun `an alarm dismissed during the delay never sounds`() {
        val players = soundSetup()
        val id = ringAlarm("Standup")
        val service = startRingingService()
        advance(30)

        AlarmController.from(context).dismiss(id)
        service.destroy()
        advance(10 * 60)

        assertTrue(players.isEmpty())
    }

    @Test fun `snoozing silences the sound and the delay restarts when it rings again`() {
        val players = soundSetup()
        val id = ringAlarm("Standup")
        val service = startRingingService()
        advance(70)
        val controller = AlarmController.from(context)

        controller.snooze(id)
        assertNull(app.nextStartedService)  // nothing rings, so the service is stopped
        service.destroy()
        assertEquals(ShadowMediaPlayer.State.END, players.single().state)

        controller.onTrigger(id)
        startRingingService()
        advance(59)
        assertEquals(1, players.size)
        advance(1)
        assertTrue(players.last().isPlaying)
        assertEquals(0.10f, players.last().leftVolume, 1e-6f)
    }

    @Test fun `the sound follows the alarm that has rung longest`() {
        val players = soundSetup()
        val first = ringAlarm("First")
        val service = startRingingService()
        advance(50)
        ringAlarm("Second")
        deliverStarts(service)
        advance(20)
        assertTrue(players.single().isPlaying)

        AlarmController.from(context).dismiss(first)
        deliverStarts(service)
        assertEquals(ShadowMediaPlayer.State.END, players.single().state)

        advance(40)
        assertTrue(players.last().isPlaying)
        assertEquals(2, players.size)
    }

    @Test fun `a restarted service resumes the fade where it was`() {
        val players = soundSetup()
        ringAlarm("Standup")
        AppClock.override = AppClock.now() + 90_000L

        startRingingService()

        assertEquals(0.30f, players.single().leftVolume, 1e-6f)
    }

    @Test fun `no sound selected plays nothing`() {
        val players = soundSetup(uri = null)
        ringAlarm("Standup")
        startRingingService()
        advance(5 * 60)
        assertTrue(players.isEmpty())
    }

    @Test fun `an unplayable sound falls back to the default alarm sound`() {
        val broken = Uri.parse("content://media/external/audio/media/404")
        val players = soundSetup(uri = broken)
        ShadowMediaPlayer.addException(DataSource.toDataSource(context, broken), IOException("gone"))
        ringAlarm("Standup")
        startRingingService()

        advance(60)

        assertEquals(ShadowMediaPlayer.State.END, players.first().state)
        assertTrue(players.last().isPlaying)
        assertEquals(DataSource.toDataSource(context, alarmUri), players.last().dataSource)
    }
}

package com.example.calendaralarm

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.os.VibratorManager
import com.example.calendaralarm.AlarmInstance.State
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

data class FiredAlarm(
    val firedAtMs: Long,   // simulated time when the alarm started ringing (or was shown, if silent)
    val alarmId: String,
    val summary: String,
    val uid: String,
    val startMs: Long,
    val endMs: Long,
    val silent: Boolean
)

/**
 * Test helper for simulating the full alarm lifecycle under Robolectric: calendar polls,
 * AlarmManager deliveries, the AlarmService, user actions, process death and reboots.
 * Must be used inside a @RunWith(RobolectricTestRunner::class) test class.
 */
class AlarmSimulator {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val store = AlarmStore.from(context)
    private val server = MockWebServer()

    private val icsByPath = ConcurrentHashMap<String, String>()
    private val failingPaths = ConcurrentHashMap.newKeySet<String>()
    private val heldPaths = ConcurrentHashMap<String, CountDownLatch>()
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val firedAlarmsList = mutableListOf<FiredAlarm>()
    private val deliveryFailuresList = mutableListOf<Throwable>()

    private var service: ServiceController<AlarmService>? = null
    private var serviceStartId = 0
    private var lastStartResult = 0
    private var stickyServiceKilled = false
    private var failNextServiceStart = false

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                requestCounts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                heldPaths[path]?.await()
                if (path in failingPaths) return MockResponse().setResponseCode(500)
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"sim-etag\"")
                    .setBody(icsByPath.getValue(path))
            }
        }
        server.start()
    }

    /** Set the ICS content returned by the fake server for [path]. */
    fun setIcsContent(content: String, path: String = DEFAULT_PATH) {
        icsByPath[path] = content
    }

    /** Call once at the start: sets the simulated time and stores the default source URL in Prefs. */
    fun setUp(startTimeMs: Long) {
        setTime(startTimeMs)
        Prefs(context).icsSources = listOf(server.url(DEFAULT_PATH).toString())
    }

    fun url(path: String): String = server.url(path).toString()

    /** Add another calendar source served at [path]. */
    fun addSource(path: String) {
        val prefs = Prefs(context)
        prefs.icsSources = prefs.icsSources + server.url(path).toString()
    }

    /** Requests for [path] get HTTP 500 from now on. */
    fun failSource(path: String) {
        failingPaths.add(path)
    }

    /** Requests for [path] succeed again. */
    fun recoverSource(path: String) {
        failingPaths.remove(path)
    }

    /** Requests for [path] block until the returned latch is counted down. */
    fun holdRequests(path: String): CountDownLatch =
        CountDownLatch(1).also { heldPaths[path] = it }

    fun requestCount(path: String): Int = requestCounts[path]?.get() ?: 0

    // ── Device and process events ────────────────────────────────────────────

    /** Simulate device boot: sends ACTION_BOOT_COMPLETED to BootReceiver. */
    fun boot() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        pumpServices()
    }

    /**
     * The process dies: the service and its foreground notification vanish; other notifications,
     * the store and AlarmManager survive.
     */
    fun killProcess() {
        service?.let { notificationManager.cancel(Shadows.shadowOf(it.get()).lastForegroundNotificationId) }
        stickyServiceKilled = service != null && lastStartResult == Service.START_STICKY
        service = null
        val app = Shadows.shadowOf(context)
        while (app.nextStartedService != null) Unit
        while (app.nextStoppedService != null) Unit
    }

    /** The system restarts the sticky service that [killProcess] killed, with a null intent. */
    fun restartKilledService() {
        check(stickyServiceKilled) { "no sticky service was killed" }
        stickyServiceKilled = false
        startService(null)
        pumpServices()
    }

    /** The device restarts: the process dies, AlarmManager is cleared, then BootReceiver runs. */
    fun reboot() {
        killProcess()
        Shadows.shadowOf(alarmManager).scheduledAlarms.forEach { alarmManager.cancel(it.operation) }
        boot()
    }

    /** The next alarm delivery fails to start the foreground service, as Android may refuse it. */
    fun failNextServiceStart() {
        failNextServiceStart = true
    }

    /**
     * Advance simulated time to targetMs, firing polls and alarms in chronological order.
     * Repeats until no pending alarm has triggerAtTime <= targetMs.
     */
    fun runUntil(targetMs: Long) {
        val shadowAlarmManager = Shadows.shadowOf(alarmManager)
        while (true) {
            val nextAlarm = shadowAlarmManager.scheduledAlarms
                .filter { it.triggerAtTime <= targetMs }
                .minByOrNull { it.triggerAtTime }
                ?: break

            setTime(nextAlarm.triggerAtTime)

            // Read intent BEFORE cancelling — cancel() may clear savedIntent in Robolectric.
            val savedIntent = Shadows.shadowOf(nextAlarm.operation).savedIntent

            // Remove from scheduler so the loop doesn't re-fire it.
            alarmManager.cancel(nextAlarm.operation)

            when (savedIntent.component?.className) {
                PollReceiver::class.java.name -> runBlocking { PollReceiver().poll(context) }
                AlarmReceiver::class.java.name -> deliverAlarm(savedIntent)
            }
            pumpServices()
        }
        setTime(targetMs)
    }

    // ── User actions ─────────────────────────────────────────────────────────

    /** The user taps Dismiss on the ringing notification of alarm [id]. */
    fun dismiss(id: String) = deliverAction(actionIntent(id, ActionReceiver.ACTION_DISMISS))

    /** The user taps Snooze on the ringing notification of alarm [id]. */
    fun snooze(id: String) = deliverAction(actionIntent(id, ActionReceiver.ACTION_SNOOZE))

    /** The user swipes away the ringing notification of alarm [id]. */
    fun swipeNotification(id: String) {
        val notification = checkNotNull(ringingNotification(id)) { "no ringing notification for $id" }
        notificationManager.cancel(NotificationHelper.ringingNotificationId(id))
        deliverAction(Shadows.shadowOf(notification.deleteIntent).savedIntent)
    }

    /** The intent behind the [action] button of the ringing notification of alarm [id]. */
    fun actionIntent(id: String, action: String): Intent {
        val notification = checkNotNull(ringingNotification(id)) { "no ringing notification for $id" }
        return notification.actions.map { Shadows.shadowOf(it.actionIntent).savedIntent }.single { it.action == action }
    }

    /** Delivers a notification action intent to [ActionReceiver], as the system would. */
    fun deliverAction(intent: Intent) {
        ActionReceiver().onReceive(context, intent)
        pumpServices()
    }

    // ── Observations ─────────────────────────────────────────────────────────

    fun instance(id: String): AlarmInstance = store.all().single { it.id == id }

    fun ringingIds(): List<String> = store.ringing().map { it.id }

    fun ringingNotification(id: String): Notification? =
        Shadows.shadowOf(notificationManager).getNotification(NotificationHelper.ringingNotificationId(id))

    /** The alarms that have a ringing notification posted. */
    fun notifiedRingingIds(): Set<String> =
        notificationManager.activeNotifications
            .filter { it.notification.channelId == NotificationHelper.CHANNEL_ALARM }
            .map { Shadows.shadowOf(it.notification.deleteIntent).savedIntent.getStringExtra(EXTRA_ALARM_ID)!! }
            .toSet()

    /** The "missed alarm" notification of alarm [id], if posted. */
    fun missedNotification(id: String): Notification? =
        Shadows.shadowOf(notificationManager).getNotification(NotificationHelper.noticeNotificationId(id))

    fun serviceRunning(): Boolean = service != null

    fun isVibrating(): Boolean =
        Shadows.shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator).isVibrating

    /** Current trigger times of pending alarm triggers (not polls or ring timeouts). */
    fun pendingEventAlarmTriggers(): List<Long> = pendingTriggers().map { it.first }.sorted()

    /** Alarm ids of pending alarm triggers. */
    fun pendingTriggerIds(): Set<String> = pendingTriggers().map { it.second }.toSet()

    /** All alarms that started ringing (or were shown, if silent), in chronological order. */
    fun firedAlarms(): List<FiredAlarm> = firedAlarmsList.toList()

    /** Exceptions from alarm deliveries whose service start was made to fail. */
    fun deliveryFailures(): List<Throwable> = deliveryFailuresList.toList()

    /** Stop MockWebServer. Call in @After. */
    fun tearDown() {
        heldPaths.values.forEach(CountDownLatch::countDown)
        server.shutdown()
        AppClock.override = null
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun setTime(ms: Long) {
        AppClock.override = ms
        SystemClock.setCurrentTimeMillis(ms)
    }

    private fun pendingTriggers(): List<Pair<Long, String>> =
        Shadows.shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = Shadows.shadowOf(alarm.operation).savedIntent
            if (intent.action != AlarmReceiver.ACTION_TRIGGER) null
            else alarm.triggerAtTime to intent.getStringExtra(EXTRA_ALARM_ID)!!
        }

    private fun deliverAlarm(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ALARM_ID)!!
        val before = instance(id).state
        if (failNextServiceStart) {
            failNextServiceStart = false
            val refusing = object : ContextWrapper(context) {
                override fun startForegroundService(service: Intent): ComponentName =
                    throw IllegalStateException("simulated: foreground service start not allowed")
            }
            try {
                AlarmReceiver().onReceive(refusing, intent)
            } catch (e: IllegalStateException) {
                deliveryFailuresList.add(e)
            }
        } else {
            AlarmReceiver().onReceive(context, intent)
        }
        val after = instance(id)
        val shown = after.state == State.RINGING ||
                    (after.kind == AlarmInstance.Kind.SILENT && after.state == State.DISMISSED)
        if (after.state != before && shown) recordFired(after)
    }

    private fun recordFired(instance: AlarmInstance) {
        for (event in instance.events) {
            firedAlarmsList.add(FiredAlarm(
                firedAtMs = AppClock.now(),
                alarmId   = instance.id,
                summary   = event.summary,
                uid       = event.uid,
                startMs   = event.startMs,
                endMs     = event.endMs,
                silent    = instance.kind == AlarmInstance.Kind.SILENT
            ))
        }
    }

    private fun startService(intent: Intent?) {
        val controller = service ?: Robolectric.buildService(AlarmService::class.java).create()
        service = controller
        lastStartResult = controller.get().onStartCommand(intent, 0, ++serviceStartId)
        if (Shadows.shadowOf(controller.get()).isStoppedBySelf) {
            controller.destroy()
            service = null
        }
    }

    /** Runs the service starts and stops that the app requested, and pending main-thread work, as the system would. */
    private fun pumpServices() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val app = Shadows.shadowOf(context)
        var stopped = false
        while (app.nextStoppedService != null) stopped = true
        if (stopped) {
            service?.destroy()
            service = null
        }
        while (true) {
            val started = app.nextStartedService ?: break
            startService(started)
        }
    }

    companion object {
        const val DEFAULT_PATH = "/calendar.ics"

        /** A calendar of events given as (uid, DTSTART, DTEND); the uid doubles as the summary. */
        fun ics(vararg events: Triple<String, String, String>) = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Test//Test//EN")
            for ((uid, start, end) in events) {
                appendLine("BEGIN:VEVENT")
                appendLine("UID:$uid")
                appendLine("DTSTART:$start")
                appendLine("DTEND:$end")
                appendLine("SUMMARY:$uid")
                appendLine("END:VEVENT")
            }
            append("END:VCALENDAR")
        }
    }
}

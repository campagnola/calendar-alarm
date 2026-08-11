package com.example.calendaralarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import android.os.SystemClock
import org.robolectric.RuntimeEnvironment
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.robolectric.Shadows

data class FiredAlarm(
    val firedAtMs: Long,   // simulated time when the alarm fired
    val summary: String,   // event summary
    val uid: String,
    val startMs: Long,
    val endMs: Long,
    val silent: Boolean
)

/**
 * Test helper for simulating the full alarm scheduling lifecycle under Robolectric.
 * Must be used inside a @RunWith(RobolectricTestRunner::class) test class.
 */
class AlarmSimulator {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val server = MockWebServer()

    @Volatile private var currentIcsContent = ""
    private val firedAlarmsList = mutableListOf<FiredAlarm>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"sim-etag\"")
                    .setBody(currentIcsContent)
        }
        server.start()
    }

    /** Set the ICS content returned by the fake server. */
    fun setIcsContent(content: String) {
        currentIcsContent = content
    }

    /** Call once at the start: sets the simulated time and stores the server URL in Prefs. */
    fun setUp(startTimeMs: Long) {
        AppClock.override = startTimeMs
        SystemClock.setCurrentTimeMillis(startTimeMs)
        Prefs(context).icsSources = listOf(server.url("/calendar.ics").toString())
    }

    /** Simulate device boot: sends ACTION_BOOT_COMPLETED to BootReceiver, which schedules an immediate poll. */
    fun boot() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    /**
     * Advance simulated time to targetMs, firing polls and event alarms in chronological order.
     * Repeats until no pending alarm has triggerAtTime <= targetMs.
     */
    fun runUntil(targetMs: Long) {
        val shadowAlarmManager = Shadows.shadowOf(alarmManager)
        while (true) {
            val nextAlarm = shadowAlarmManager.scheduledAlarms
                .filter { it.triggerAtTime <= targetMs }
                .minByOrNull { it.triggerAtTime }
                ?: break

            AppClock.override = nextAlarm.triggerAtTime
            SystemClock.setCurrentTimeMillis(nextAlarm.triggerAtTime)

            // Read intent BEFORE cancelling — cancel() may clear savedIntent in Robolectric.
            val savedIntent = Shadows.shadowOf(nextAlarm.operation).savedIntent
            val className = savedIntent?.component?.className

            // Remove from scheduler so the loop doesn't re-fire it.
            alarmManager.cancel(nextAlarm.operation)

            when (className) {
                PollReceiver::class.java.name -> {
                    runBlocking { PollReceiver().poll(context) }
                }
                AlarmReceiver::class.java.name -> {
                    AlarmReceiver().onReceive(context, savedIntent)
                    val eventsJson = savedIntent.getStringExtra("events_json")
                    val silent = savedIntent.getBooleanExtra("silent", false)
                    if (eventsJson != null) {
                        recordFiredAlarms(nextAlarm.triggerAtTime, eventsJson, silent)
                    }
                }
            }
        }

        AppClock.override = targetMs
        SystemClock.setCurrentTimeMillis(targetMs)
    }

    /** Current trigger times of pending event alarms (AlarmReceiver), not polls. */
    fun pendingEventAlarmTriggers(): List<Long> =
        Shadows.shadowOf(alarmManager).scheduledAlarms
            .filter { alarm ->
                Shadows.shadowOf(alarm.operation).savedIntent
                    ?.component?.className == AlarmReceiver::class.java.name
            }
            .map { it.triggerAtTime }
            .sorted()

    /** All event alarms that fired, in chronological order. */
    fun firedAlarms(): List<FiredAlarm> = firedAlarmsList.toList()

    /** Stop MockWebServer. Call in @After. */
    fun tearDown() {
        server.shutdown()
        AppClock.override = null
    }

    private fun recordFiredAlarms(firedAtMs: Long, eventsJson: String, silent: Boolean) {
        try {
            val arr = JSONArray(eventsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                firedAlarmsList.add(
                    FiredAlarm(
                        firedAtMs = firedAtMs,
                        summary   = obj.optString("summary", ""),
                        uid       = obj.optString("uid", ""),
                        startMs   = obj.optLong("start_time_ms", 0L),
                        endMs     = obj.optLong("end_time_ms", 0L),
                        silent    = silent
                    )
                )
            }
        } catch (_: Exception) {
            // JSON parse errors surface as missing fired alarms, not crashes
        }
    }
}

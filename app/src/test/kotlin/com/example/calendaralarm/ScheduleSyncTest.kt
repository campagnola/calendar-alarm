package com.example.calendaralarm

import android.os.Looper
import android.view.View
import android.widget.TextView
import com.example.calendaralarm.AlarmSimulator.Companion.ics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.fakes.RoboMenuItem
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleSyncTest {

    private lateinit var savedTz: TimeZone
    private lateinit var sim: AlarmSimulator
    private val context get() = RuntimeEnvironment.getApplication()

    private val june18 = LocalDate.of(2026, 6, 18)
    private val june19 = june18.plusDays(1)
    private val startMs = utcMs(june18, 12, 0)

    private fun utcMs(date: LocalDate, h: Int, m: Int = 0) =
        date.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    // Today 14:00 (shown by MainActivity) and tomorrow 08:00 (now + 20h, alarm at 07:55).
    private val todayEvent    = Triple("today@test", "20260618T140000Z", "20260618T150000Z")
    private val tomorrowEvent = Triple("tomorrow@test", "20260619T080000Z", "20260619T090000Z")
    private val tomorrowTrigger = utcMs(june19, 7, 55)

    @Before fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        sim = AlarmSimulator()
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedTz)
        sim.tearDown()
    }

    private fun bootAndFirstPoll() {
        sim.setUp(startMs)
        sim.boot()
        sim.runUntil(startMs + 60_000L)
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for condition" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test fun `opening and refreshing MainActivity keeps the alarm for an event 20h ahead`() {
        sim.setIcsContent(ics(todayEvent, tomorrowEvent))
        bootAndFirstPoll()
        assertTrue("poll scheduled tomorrow's alarm", tomorrowTrigger in sim.pendingEventAlarmTriggers())

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val status = activity.findViewById<TextView>(R.id.statusText)
        idleUntil { status.visibility == View.GONE }
        assertTrue("opening the app keeps tomorrow's alarm", tomorrowTrigger in sim.pendingEventAlarmTriggers())

        activity.onOptionsItemSelected(RoboMenuItem(R.id.action_refresh))
        idleUntil { status.visibility == View.GONE }
        assertTrue("refreshing keeps tomorrow's alarm", tomorrowTrigger in sim.pendingEventAlarmTriggers())
    }

    @Test fun `concurrent syncs run one at a time and every registered alarm is tracked`() {
        sim.setIcsContent(ics(todayEvent))
        sim.setIcsContent(ics(tomorrowEvent), "/second.ics")
        sim.setUp(startMs)
        sim.addSource("/second.ics")

        // First sync fetches the default source, then blocks on the second.
        val release = sim.holdRequests("/second.ics")
        val first = thread { runBlocking { PollReceiver().poll(context) } }
        idleUntil { sim.requestCount("/second.ics") == 1 }

        val second = thread { runBlocking { PollReceiver().poll(context) } }
        Thread.sleep(500)
        assertEquals("second sync waits for the first", 1, sim.requestCount(AlarmSimulator.DEFAULT_PATH))

        release.countDown()
        first.join()
        second.join()
        assertEquals(2, sim.requestCount(AlarmSimulator.DEFAULT_PATH))
        val scheduled = AlarmStore.from(context).all()
            .filter { it.state == AlarmInstance.State.SCHEDULED }.map { it.id }.toSet()
        assertEquals(scheduled, sim.pendingTriggerIds())
        assertEquals(2, scheduled.size)
    }

    @Test fun `a source whose fetch fails keeps its alarms`() {
        sim.setIcsContent(ics(todayEvent))
        sim.setIcsContent(ics(tomorrowEvent), "/second.ics")
        bootAndFirstPoll()
        sim.addSource("/second.ics")
        sim.runUntil(startMs + 20 * 60_000L)
        assertTrue(tomorrowTrigger in sim.pendingEventAlarmTriggers())

        sim.failSource("/second.ics")
        sim.runUntil(startMs + 40 * 60_000L)

        assertEquals(listOf(utcMs(june18, 13, 55), tomorrowTrigger), sim.pendingEventAlarmTriggers())
    }

    @Test fun `a sync with no calendar data keeps existing alarms`() {
        sim.setIcsContent(ics(tomorrowEvent))
        bootAndFirstPoll()
        assertEquals(listOf(tomorrowTrigger), sim.pendingEventAlarmTriggers())

        sim.failSource(AlarmSimulator.DEFAULT_PATH)
        context.cacheDir.listFiles()!!.filter { it.name.endsWith(".ics") }.forEach(File::delete)
        sim.runUntil(startMs + 20 * 60_000L)

        assertEquals(listOf(tomorrowTrigger), sim.pendingEventAlarmTriggers())
    }
}

package com.example.calendaralarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendaralarm.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var lastLoadMs   = 0L
    private var lastLoadDate: LocalDate? = null
    private var lastVisibleDay: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        NotificationHelper.createChannels(this)
        requestNotificationPermissionIfNeeded()
        loadSchedule()
    }

    override fun onResume() {
        super.onResume()
        val today   = LocalDate.now()
        val visDay  = visibleDay(LocalDateTime.now())
        val elapsed = System.currentTimeMillis() - lastLoadMs
        val pollMs  = prefs.pollIntervalMinutes * 60_000L
        if (lastLoadDate != today || lastVisibleDay != visDay || elapsed >= pollMs) {
            loadSchedule()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_refresh  -> { loadSchedule(forceDownload = true); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Visible-day logic ─────────────────────────────────────────────────────

    private fun visibleDay(now: LocalDateTime = LocalDateTime.now()): LocalDate {
        val minuteOfDay = now.hour * 60 + now.minute
        return if (minuteOfDay >= prefs.showTomorrowAtMinutes) now.toLocalDate().plusDays(1)
               else now.toLocalDate()
    }

    // ── Schedule loading ──────────────────────────────────────────────────────

    private fun loadSchedule(forceDownload: Boolean = false) {
        val now    = LocalDateTime.now()
        val today  = now.toLocalDate()
        val visDay = visibleDay(now)

        lastLoadMs     = System.currentTimeMillis()
        lastLoadDate   = today
        lastVisibleDay = visDay

        if (prefs.icsSources.isEmpty() && !prefs.localCalendarEnabled) {
            showMessage("Tap the gear icon to add a calendar source.")
            return
        }

        binding.scheduleContainer.removeAllViews()
        binding.statusText.text = "Loading…"
        binding.statusText.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Returns events for the visible window paired with their alarm-enabled flag,
            // or null on total fetch failure.
            val displayEvents: List<Pair<CalendarEvent, Boolean>>? = withContext(Dispatchers.IO) {
                val visDayStart = visDay.atStartOfDay()
                val windowStart = if (visDay == today) visDayStart else now
                val windowEnd   = visDayStart.plusHours(25)

                val icsSources = IcsFetcher.fetchAll(this@MainActivity, forceDownload)

                val localEvents = if (prefs.localCalendarEnabled && prefs.localCalendarIds.isNotEmpty()) {
                    LocalCalendarReader.fetchEvents(this@MainActivity, prefs.localCalendarIds, windowStart, windowEnd)
                } else emptyList()

                // Fail only if ICS was configured but all fetches failed and there's no local fallback
                if (prefs.icsSources.isNotEmpty() && icsSources.isEmpty() && localEvents.isEmpty()) {
                    return@withContext null
                }

                val parsedPerSource = icsSources.map { source ->
                    val id = prefs.urlId(source.url)
                    source to IcsParser.parse(source.text, windowStart, windowEnd, id)
                }
                for ((source, sourceEvents) in parsedPerSource) {
                    prefs.setIcsLastFetchEventCount(
                        prefs.urlId(source.url),
                        sourceEvents.count { it.start.toLocalDate() == today }
                    )
                }
                if (prefs.localCalendarEnabled) {
                    prefs.setLocalCalendarEventCount(
                        localEvents.count { it.start.toLocalDate() == today })
                }
                val allEvents = parsedPerSource.flatMap { it.second } + localEvents

                val filters    = prefs.eventFilters
                val eventFlags = allEvents.associateWith { FilterEngine.evaluate(it, filters) }

                for (filter in filters) {
                    prefs.setFilterMatchCount(filter.id,
                        allEvents.count { it.start.toLocalDate() == today
                                       && FilterEngine.matches(it, filter.criteria) })
                }

                scheduleAlarms(allEvents, eventFlags)

                allEvents
                    .filter { it.start >= windowStart
                           && it.start.toLocalDate() <= visDay
                           && eventFlags[it]?.visible != false }
                    .sortedBy { it.start }
                    .map { it to (eventFlags[it]?.alarmEnabled != false) }
            }

            when {
                displayEvents == null    -> showMessage("Failed to load calendar. Check URL and network.")
                displayEvents.isEmpty()  ->
                    showMessage(if (visDay == today) "No events today." else "No events tomorrow.")
                else -> {
                    binding.statusText.visibility = View.GONE
                    val fmt        = timeFormatter(this@MainActivity)
                    val displayNow = LocalDateTime.now()
                    val showingTomorrow = visDay != today
                    var headerInserted  = false

                    for ((event, alarmEnabled) in displayEvents) {
                        // Insert "tomorrow" divider before the first tomorrow-day event
                        if (showingTomorrow && !headerInserted
                                && event.start.toLocalDate() == visDay) {
                            addDayHeader("------ tomorrow ------")
                            headerInserted = true
                        }
                        addEventCard(event, fmt, displayNow, alarmEnabled)
                    }
                }
            }
        }
    }

    // ── Alarm scheduling ──────────────────────────────────────────────────────

    private fun scheduleAlarms(events: List<CalendarEvent>,
                               flags: Map<CalendarEvent, FilterEngine.EventFlags>) {
        prefs.activeAlarmCodes = AlarmScheduler.scheduleAll(this, events, flags, prefs)
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun addDayHeader(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.setTextColor(0xFF888888.toInt())
        tv.textSize = 12f
        val pad = (10 * resources.displayMetrics.density).toInt()
        tv.setPadding(0, pad, 0, pad)
        binding.scheduleContainer.addView(tv)
    }

    private fun addEventCard(event: CalendarEvent, fmt: java.time.format.DateTimeFormatter,
                             now: LocalDateTime, alarmEnabled: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_event, binding.scheduleContainer, false)

        val bg = when {
            event.end   <= now -> R.drawable.event_card_bg_past
            event.start <= now -> R.drawable.event_card_bg_active
            else               -> R.drawable.event_card_bg
        }
        view.setBackgroundResource(bg)

        view.findViewById<TextView>(R.id.eventTitle).text = event.summary
        view.findViewById<TextView>(R.id.eventTime).text = if (event.isAllDay) "All day"
            else "${event.start.format(fmt)} – ${event.end.format(fmt)}"

        view.findViewById<ImageView>(R.id.bellIcon).visibility =
            if (alarmEnabled) View.VISIBLE else View.GONE

        val locationView = view.findViewById<TextView>(R.id.eventLocation)
        if (event.location.isNotEmpty()) {
            locationView.text = event.location
            locationView.visibility = View.VISIBLE
        }

        val joinButton = view.findViewById<MaterialButton>(R.id.joinButton)
        val meetingUrl = event.meetingUrl
        if (meetingUrl != null) {
            joinButton.text = if (meetingUrl.contains("zoom.us", ignoreCase = true)) "Join Zoom" else "Join Teams"
            joinButton.visibility = View.VISIBLE
            joinButton.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(meetingUrl)))
            }
        }

        binding.scheduleContainer.addView(view)
    }

    private fun showMessage(msg: String) {
        binding.scheduleContainer.removeAllViews()
        binding.statusText.text = msg
        binding.statusText.visibility = View.VISIBLE
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

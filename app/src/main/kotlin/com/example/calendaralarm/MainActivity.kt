package com.example.calendaralarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var lastLoadMs = 0L
    private var lastLoadDate: LocalDate? = null

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
        val today = LocalDate.now()
        val elapsed = System.currentTimeMillis() - lastLoadMs
        val pollMs = prefs.pollIntervalMinutes * 60_000L
        if (lastLoadDate != today || elapsed >= pollMs) {
            loadSchedule()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_refresh -> {
            loadSchedule(forceDownload = true)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadSchedule(forceDownload: Boolean = false) {
        lastLoadMs   = System.currentTimeMillis()
        lastLoadDate = LocalDate.now()

        if (prefs.icsSources.isEmpty()) {
            showMessage("Tap the gear icon to add a calendar source.")
            return
        }

        binding.scheduleContainer.removeAllViews()
        binding.statusText.text = "Loading…"
        binding.statusText.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Returns list of (event, alarmEnabled) for today, or null on total failure.
            val todayEvents: List<Pair<CalendarEvent, Boolean>>? = withContext(Dispatchers.IO) {
                val sources = IcsFetcher.fetchAll(this@MainActivity, forceDownload)
                if (sources.isEmpty()) return@withContext null

                val now      = LocalDateTime.now()
                val today    = now.toLocalDate()
                val dayStart = today.atStartOfDay()

                val parsedPerSource = sources.map { source ->
                    val id = prefs.urlId(source.url)
                    source to IcsParser.parse(source.text, dayStart, dayStart.plusHours(25), id)
                }
                for ((source, sourceEvents) in parsedPerSource) {
                    prefs.setIcsLastFetchEventCount(
                        prefs.urlId(source.url),
                        sourceEvents.count { it.start.toLocalDate() == today }
                    )
                }
                val allEvents = parsedPerSource.flatMap { it.second }

                val filters    = prefs.eventFilters
                val eventFlags = allEvents.associateWith { FilterEngine.evaluate(it, filters) }

                for (filter in filters) {
                    prefs.setFilterMatchCount(filter.id,
                        allEvents.count { it.start.toLocalDate() == today
                                       && FilterEngine.matches(it, filter.criteria) })
                }

                scheduleAlarms(allEvents, eventFlags)

                allEvents
                    .filter { it.start.toLocalDate() == today && eventFlags[it]?.visible != false }
                    .sortedBy { it.start }
                    .map { it to (eventFlags[it]?.alarmEnabled != false) }
            }

            when {
                todayEvents == null   -> showMessage("Failed to load calendar. Check URL and network.")
                todayEvents.isEmpty() -> showMessage("No events today.")
                else -> {
                    binding.statusText.visibility = View.GONE
                    val fmt = timeFormatter(this@MainActivity)
                    val now = LocalDateTime.now()
                    for ((event, alarmEnabled) in todayEvents) addEventCard(event, fmt, now, alarmEnabled)
                }
            }
        }
    }

    private fun scheduleAlarms(events: List<CalendarEvent>,
                               flags: Map<CalendarEvent, FilterEngine.EventFlags>) {
        AlarmScheduler.cancelAllEventAlarms(this, prefs.activeAlarmCodes)

        val defaultAdvanceMs = prefs.advanceMinutes * 60_000L
        val now = System.currentTimeMillis()
        val newCodes = mutableSetOf<Int>()
        for (event in events) {
            val ef = flags[event] ?: FilterEngine.EventFlags()
            if (!ef.alarmEnabled) continue
            val advanceMs = (ef.advanceMinutes ?: prefs.advanceMinutes) * 60_000L
            val startMs   = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val triggerMs = startMs - advanceMs
            if (triggerMs > now) {
                Log.i("CalAlarm", "MainActivity scheduling alarm for '${event.summary}' trigger=${triggerMs - now}ms from now")
                AlarmScheduler.scheduleEventAlarm(this, event, triggerMs)
                newCodes.add(AlarmScheduler.alarmRequestCode(event))
            }
        }
        prefs.activeAlarmCodes = newCodes
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

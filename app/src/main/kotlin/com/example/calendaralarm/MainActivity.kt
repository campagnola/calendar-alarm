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
        lastLoadMs = System.currentTimeMillis()
        lastLoadDate = LocalDate.now()

        if (prefs.icsSources.isEmpty()) {
            showMessage("Tap the gear icon to add a calendar source.")
            return
        }

        binding.scheduleContainer.removeAllViews()
        binding.statusText.text = "Loading…"
        binding.statusText.visibility = View.VISIBLE

        lifecycleScope.launch {
            val todayEvents = withContext(Dispatchers.IO) {
                val sources = IcsFetcher.fetchAll(this@MainActivity, forceDownload)
                if (sources.isEmpty()) return@withContext null

                val now      = LocalDateTime.now()
                val today    = now.toLocalDate()
                val dayStart = today.atStartOfDay()

                val allEvents = sources.flatMap { IcsParser.parse(it.text, dayStart, dayStart.plusHours(25)) }
                scheduleAlarms(allEvents)

                allEvents.filter { it.start.toLocalDate() == today }.sortedBy { it.start }
            }

            when {
                todayEvents == null   -> showMessage("Failed to load calendar. Check URL and network.")
                todayEvents.isEmpty() -> showMessage("No events today.")
                else -> {
                    binding.statusText.visibility = View.GONE
                    val fmt = timeFormatter(this@MainActivity)
                    val now = LocalDateTime.now()
                    for (event in todayEvents) addEventCard(event, fmt, now)
                }
            }
        }
    }

    private fun scheduleAlarms(events: List<CalendarEvent>) {
        AlarmScheduler.cancelAllEventAlarms(this, prefs.activeAlarmCodes)

        val advanceMs = prefs.advanceMinutes * 60_000L
        val now = System.currentTimeMillis()
        val newCodes = mutableSetOf<Int>()
        for (event in events) {
            val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val triggerMs = startMs - advanceMs
            if (triggerMs > now) {
                Log.i("CalAlarm", "MainActivity scheduling alarm for '${event.summary}' trigger=${triggerMs - now}ms from now")
                AlarmScheduler.scheduleEventAlarm(this, event, triggerMs)
                newCodes.add(AlarmScheduler.alarmRequestCode(event))
            }
        }
        prefs.activeAlarmCodes = newCodes
    }

    private fun addEventCard(event: CalendarEvent, fmt: java.time.format.DateTimeFormatter, now: LocalDateTime) {
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

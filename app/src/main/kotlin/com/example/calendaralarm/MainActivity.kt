package com.example.calendaralarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendaralarm.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

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
            loadSchedule()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadSchedule() {
        lastLoadMs = System.currentTimeMillis()
        lastLoadDate = LocalDate.now()

        val url = prefs.icsUrl
        if (url.isEmpty()) {
            showMessage("Tap the gear icon to enter your ICS URL.")
            return
        }

        binding.scheduleContainer.removeAllViews()
        binding.statusText.text = "Loading…"
        binding.statusText.visibility = View.VISIBLE

        lifecycleScope.launch {
            val todayEvents = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val now = LocalDateTime.now()
                    val today = now.toLocalDate()

                    // Parse a 25-hour window so we can schedule alarms for events
                    // starting later today or early tomorrow, then filter to today for display.
                    val upcoming = IcsParser.parse(text, now, now.plusHours(25))
                    scheduleAlarms(upcoming)

                    upcoming.filter { it.start.toLocalDate() == today }.sortedBy { it.start }
                }.getOrNull()
            }

            when {
                todayEvents == null  -> showMessage("Failed to load calendar. Check URL and network.")
                todayEvents.isEmpty() -> showMessage("No events today.")
                else -> {
                    binding.statusText.visibility = View.GONE
                    for (event in todayEvents) addEventCard(event)
                }
            }
        }
    }

    private fun scheduleAlarms(events: List<CalendarEvent>) {
        val advanceMs = prefs.advanceMinutes * 60_000L
        val validKeys = mutableSetOf<String>()
        for (event in events) {
            val key = AlarmScheduler.alarmKey(event)
            validKeys.add(key)
            val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val triggerMs = startMs - advanceMs
            if (triggerMs > System.currentTimeMillis() && !prefs.isAlarmScheduled(key)) {
                AlarmScheduler.scheduleEventAlarm(this, event, triggerMs)
                prefs.addScheduledAlarm(key)
            }
        }
        prefs.retainScheduledAlarms(validKeys)
    }

    private fun addEventCard(event: CalendarEvent) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_event, binding.scheduleContainer, false)
        view.findViewById<TextView>(R.id.eventTitle).text = event.summary
        view.findViewById<TextView>(R.id.eventTime).text = if (event.isAllDay) "All day"
            else "${event.start.format(timeFmt)} – ${event.end.format(timeFmt)}"
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

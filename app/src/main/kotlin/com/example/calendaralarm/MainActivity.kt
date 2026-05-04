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
            val events = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val today = LocalDate.now()
                    IcsParser.parse(text, today.atStartOfDay(), today.atTime(23, 59, 59))
                        .sortedBy { it.start }
                }.getOrNull()
            }

            when {
                events == null -> showMessage("Failed to load calendar. Check URL and network.")
                events.isEmpty() -> showMessage("No events today.")
                else -> {
                    binding.statusText.visibility = View.GONE
                    for (event in events) addEventCard(event)
                }
            }
        }
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

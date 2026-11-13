package com.example.calendaralarm

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.calendaralarm.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var refreshMenuItem: MenuItem? = null

    /** The background sync started by this screen, if any. */
    internal var syncJob: Job? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val minuteRefreshRunnable = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        NotificationHelper.createChannels(this)
        requestNotificationPermissionIfNeeded()
        promptForAlarmPermissions()
        AlarmScheduler.ensurePollScheduled(this)

        // Every feed sync (ours, the poll's, or a settings reload) may change what is shown.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FeedSync.attempts.collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val ringing = AlarmStore.from(this).latestRinging()
        if (ringing != null) {
            AppLog.i("MainActivity: alarm ${ringing.id} ringing, redirecting to AlarmActivity")
            startActivity(Intent(this, AlarmActivity::class.java).putExtra(EXTRA_ALARM_ID, ringing.id))
            return
        }
        render()
        val now = AppClock.now()
        if (prefs.icsSources.any { prefs.feedStatus(prefs.urlId(it)).isSyncDue(now, prefs.pollIntervalMinutes) }) {
            sync("app opened")
        }
        handler.postDelayed(minuteRefreshRunnable, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(minuteRefreshRunnable)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        refreshMenuItem = menu.findItem(R.id.action_refresh)
        refreshMenuItem?.isEnabled = syncJob?.isActive != true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_refresh  -> { sync("user refresh"); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Visible-day logic ─────────────────────────────────────────────────────

    private fun visibleDay(now: LocalDateTime = AppClock.nowLocal()): LocalDate {
        val minuteOfDay = now.hour * 60 + now.minute
        return if (minuteOfDay >= prefs.showTomorrowAtMinutes) now.toLocalDate().plusDays(1)
               else now.toLocalDate()
    }

    // ── Schedule loading ──────────────────────────────────────────────────────

    /** Syncs feeds and alarms in the background; [render] runs as each feed finishes. */
    private fun sync(reason: String) {
        if (syncJob?.isActive == true) return
        refreshMenuItem?.isEnabled = false
        syncJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) { ScheduleSync.run(this@MainActivity, reason) }
            refreshMenuItem?.isEnabled = true
            render()  // also covers local calendars and settings, which have no feed attempt
        }
    }

    /** Shows the schedule from the most recently downloaded feeds; never waits on the network. */
    private fun render() {
        val now    = AppClock.nowLocal()
        val today  = now.toLocalDate()
        val visDay = visibleDay(now)

        if (prefs.icsSources.isEmpty() && !prefs.localCalendarEnabled) {
            showMessage("Tap the gear icon to add a calendar source.")
            return
        }

        lifecycleScope.launch {
            // Returns events for the visible window paired with their alarm-enabled flag,
            // or null if the settings are unreadable or there is no calendar data at all.
            val displayEvents: List<Pair<CalendarEvent, Boolean>>? = withContext(Dispatchers.IO) {
                val (filters, preferences) = ScheduleSync.readEventSettings(prefs) ?: return@withContext null

                val zone        = ZoneId.systemDefault()
                val visDayStart = visDay.atStartOfDay(zone).toInstant()
                val windowStart = if (visDay == today) visDayStart else now.atZone(zone).toInstant()
                val windowEnd   = visDay.plusDays(1).atStartOfDay(zone).toInstant()

                val icsTexts = prefs.icsSources.mapNotNull { url ->
                    IcsFetcher.cached(this@MainActivity, url)?.let { url to it }
                }

                val localEvents = if (prefs.localCalendarEnabled && prefs.localCalendarIds.isNotEmpty()) {
                    LocalCalendarReader.fetchEvents(this@MainActivity, prefs.localCalendarIds, windowStart, windowEnd)
                } else emptyList()

                if (prefs.icsSources.isNotEmpty() && icsTexts.isEmpty() && localEvents.isEmpty()) {
                    return@withContext null
                }

                val allEvents = icsTexts.flatMap { (url, text) ->
                    IcsParser.parse(text, windowStart, windowEnd, prefs.urlId(url)).events
                } + localEvents
                val eventFlags = FilterEngine.effectiveFlags(allEvents, filters, preferences)

                allEvents
                    .filter { it.start >= windowStart
                           && it.localStart.toLocalDate() <= visDay
                           && eventFlags.getValue(it).visible }
                    .sortedBy { it.start }
                    .map { it to eventFlags.getValue(it).alarmEnabled }
            }

            showSyncProblems()
            when {
                displayEvents == null    -> showMessage(
                    if (prefs.settingsProblem != null) "Event settings could not be read."
                    else "No calendar data has been downloaded yet.")
                displayEvents.isEmpty()  ->
                    showMessage(if (visDay == today) "No events today." else "No events tomorrow.")
                else -> {
                    binding.scheduleContainer.removeAllViews()
                    binding.statusText.visibility = View.GONE
                    val fmt        = timeFormatter(this@MainActivity)
                    val displayNow = Instant.ofEpochMilli(AppClock.now())
                    val showingTomorrow = visDay != today
                    var headerInserted  = false

                    for ((event, alarmEnabled) in displayEvents) {
                        // Insert "tomorrow" divider before the first tomorrow-day event
                        if (showingTomorrow && !headerInserted
                                && event.localStart.toLocalDate() == visDay) {
                            addDayHeader("------ tomorrow ------")
                            headerInserted = true
                        }
                        addEventCard(event, fmt, displayNow, alarmEnabled)
                    }
                }
            }
        }
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

    /** Measured height of an event card whose title and time each fit on one line. */
    private val singleLineCardHeight: Int by lazy {
        val card = LayoutInflater.from(this).inflate(R.layout.item_event, binding.scheduleContainer, false)
        card.findViewById<TextView>(R.id.eventTitle).text = "X"
        card.findViewById<TextView>(R.id.eventTime).text = "X"
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        card.measure(unspecified, unspecified)
        card.measuredHeight
    }

    private fun addEventCard(event: CalendarEvent, fmt: java.time.format.DateTimeFormatter,
                             now: Instant, alarmEnabled: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_event, binding.scheduleContainer, false)

        view.setOnClickListener {
            startActivity(Intent(this, EventDetailActivity::class.java)
                .putExtra(EventDetailActivity.EXTRA_EVENT_JSON, event.toJson()))
        }

        val bg = when {
            event.end   <= now -> R.drawable.event_card_bg_past
            event.start <= now -> R.drawable.event_card_bg_active
            else               -> R.drawable.event_card_bg
        }
        view.setBackgroundResource(bg)

        view.findViewById<TextView>(R.id.eventTitle).text = event.summary
        view.findViewById<TextView>(R.id.eventTime).text = if (event.isAllDay) "All day"
            else "${event.localStart.format(fmt)} – ${event.localEnd.format(fmt)}"

        val bellIcon = view.findViewById<ImageView>(R.id.bellIcon)
        bellIcon.layoutParams.width  = singleLineCardHeight
        bellIcon.layoutParams.height = singleLineCardHeight
        bellIcon.alpha = if (alarmEnabled) 1f else 0.3f
        bellIcon.contentDescription = if (alarmEnabled) "Alarm enabled" else "Alarm disabled"
        bellIcon.setOnClickListener {
            setEventAlarm(this, event, EventPreference.Scope.EVENT, !alarmEnabled)
            render()
        }

        val locationView = view.findViewById<TextView>(R.id.eventLocation)
        if (event.location.isNotEmpty()) {
            locationView.text = event.location
            locationView.visibility = View.VISIBLE
        }

        val joinButton = view.findViewById<MaterialButton>(R.id.joinButton)
        val meetingUrl = event.meetingUrl
        if (meetingUrl != null) {
            joinButton.text = AlarmPresentation.joinLabel(meetingUrl)
            joinButton.visibility = View.VISIBLE
            joinButton.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(meetingUrl)))
            }
        }

        binding.scheduleContainer.addView(view)
    }

    /**
     * Lists unreadable settings and the feeds whose latest sync failed; ScheduleSync keeps
     * [Prefs.settingsProblem] and each [FeedStatus] current.
     */
    private fun showSyncProblems() {
        val settingsProblem = prefs.settingsProblem?.let { "⚠ Event filters and alarm choices cannot be used\n$it" }
        val problems = listOfNotNull(settingsProblem) + prefs.icsSources.mapNotNull { url ->
            val status = prefs.feedStatus(prefs.urlId(url))
            if (status.problem == null) null
            else "⚠ ${FeedStatus.problemTitle(prefs.icsDisplayName(url))}\n${status.problemDetails()}"
        }
        binding.feedProblemsText.text = problems.joinToString("\n\n")
        binding.feedProblemsText.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
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

    private fun promptForAlarmPermissions() {
        for (prompt in PermissionPrompt.values().filterNot { it.isGranted(this) }) {
            AlertDialog.Builder(this)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton("Open Settings") { _, _ -> startActivity(prompt.settingsIntent(this)) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }
}

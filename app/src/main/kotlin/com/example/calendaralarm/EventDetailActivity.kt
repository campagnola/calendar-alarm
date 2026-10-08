package com.example.calendaralarm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.calendaralarm.databinding.ActivityEventDetailBinding
import java.util.UUID

class EventDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EVENT_JSON = "event_json"
    }

    private lateinit var binding: ActivityEventDetailBinding
    private lateinit var prefs: Prefs
    private lateinit var event: CalendarEvent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Event details"

        prefs = Prefs(this)

        val eventJson = intent.getStringExtra(EXTRA_EVENT_JSON)
        if (eventJson == null) { finish(); return }
        event = CalendarEvent.fromJson(eventJson)

        populateEventDetails()
        populateFilters()
        populateAlarmPreferences()

        binding.addFilterButton.setOnClickListener {
            if (binding.filterCreatePanel.visibility == View.GONE) {
                populateFilterCheckboxes()
                binding.filterCreatePanel.visibility = View.VISIBLE
                binding.scrollView.post {
                    binding.scrollView.smoothScrollTo(0, binding.filterCreatePanel.bottom)
                }
            } else {
                binding.filterCreatePanel.visibility = View.GONE
            }
        }

        binding.createFilterButton.setOnClickListener { createFilter() }
    }

    // ── Event details ─────────────────────────────────────────────────────────

    private fun populateEventDetails() {
        val fmt = timeFormatter(this)

        binding.detailTitle.text = event.summary
        binding.detailTime.text  = if (event.isAllDay) "All day"
            else "${event.localStart.format(fmt)} – ${event.localEnd.format(fmt)}"

        if (event.location.isNotEmpty()) {
            binding.detailLocation.text = event.location
            binding.detailLocation.visibility = View.VISIBLE
            binding.detailLocation.setTextColor(themeColor(android.R.attr.colorAccent))
            binding.detailLocation.paintFlags = binding.detailLocation.paintFlags or TextPaint.UNDERLINE_TEXT_FLAG
            binding.detailLocation.setOnClickListener { openLocationInMaps(event.location) }
        }

        val meetingUrl = event.meetingUrl
        if (meetingUrl != null) {
            binding.detailJoinButton.text =
                if (meetingUrl.contains("zoom.us", ignoreCase = true)) "Join Zoom" else "Join Teams"
            binding.detailJoinButton.visibility = View.VISIBLE
            binding.detailJoinButton.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(meetingUrl)))
            }
        }

        if (event.description.isNotEmpty()) {
            binding.detailDescriptionHeader.visibility = View.VISIBLE
            binding.detailDescription.text = event.description
            binding.detailDescription.visibility = View.VISIBLE
        }

        if (event.organizer.isNotEmpty()) {
            binding.detailOrganizerHeader.visibility = View.VISIBLE
            binding.detailOrganizer.text = event.organizer
            binding.detailOrganizer.visibility = View.VISIBLE
        }

        if (event.attendees.isNotEmpty()) {
            binding.detailAttendeesHeader.visibility = View.VISIBLE
            for (attendee in event.attendees) {
                val tv = TextView(this)
                tv.text = attendee
                tv.textSize = 14f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (2 * resources.displayMetrics.density).toInt()
                tv.layoutParams = lp
                binding.detailAttendeesContainer.addView(tv)
            }
        }
    }

    private fun openLocationInMaps(location: String) {
        val encodedLocation = Uri.encode(location)
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedLocation"))
        val mapsIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/?api=1&query=$encodedLocation")
        )

        startActivity(
            if (geoIntent.resolveActivity(packageManager) != null) geoIntent else mapsIntent
        )
    }

    // ── Alarm preferences ────────────────────────────────────────────────────

    private fun populateAlarmPreferences() {
        binding.disableSeriesAlarmButton.visibility = if (event.isSeries) View.VISIBLE else View.GONE
        binding.enableSeriesAlarmButton.visibility = if (event.isSeries) View.VISIBLE else View.GONE
        updateAlarmPreferenceStatus()

        binding.disableEventAlarmButton.setOnClickListener {
            saveAlarmPreference(EventPreference.Scope.EVENT, false)
        }
        binding.enableEventAlarmButton.setOnClickListener {
            saveAlarmPreference(EventPreference.Scope.EVENT, true)
        }
        binding.disableSeriesAlarmButton.setOnClickListener {
            saveAlarmPreference(EventPreference.Scope.SERIES, false)
        }
        binding.enableSeriesAlarmButton.setOnClickListener {
            saveAlarmPreference(EventPreference.Scope.SERIES, true)
        }
    }

    private fun updateAlarmPreferenceStatus() {
        val prefsByKey = prefs.eventPreferences.associateBy { it.key }
        val eventPref = prefsByKey[EventPreference.eventKey(event)]?.alarmEnabled
        val seriesPref = if (event.isSeries) {
            prefsByKey[EventPreference.seriesKey(event)]?.alarmEnabled
        } else {
            null
        }

        binding.alarmPreferenceStatus.text = when {
            eventPref == true -> "Alarm enabled for this event."
            eventPref == false -> "Alarm disabled for this event."
            seriesPref == true -> "Alarm enabled for this series."
            seriesPref == false -> "Alarm disabled for this series."
            else -> "Using filter and default alarm settings."
        }
    }

    private fun saveAlarmPreference(scope: EventPreference.Scope, alarmEnabled: Boolean) {
        val key = when (scope) {
            EventPreference.Scope.EVENT -> EventPreference.eventKey(event)
            EventPreference.Scope.SERIES -> EventPreference.seriesKey(event)
        }
        val endMs = event.end.toEpochMilli()
        prefs.saveEventPreference(EventPreference(key, scope, alarmEnabled, endMs))
        AlarmScheduler.schedulePoll(this, 0L)
        updateAlarmPreferenceStatus()
        Toast.makeText(this, "Alarm preference saved", Toast.LENGTH_SHORT).show()
    }

    // ── Filter matching ───────────────────────────────────────────────────────

    private fun populateFilters() {
        val filters = prefs.eventFilters
        if (filters.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No filters configured"
            tv.textSize = 13f
            binding.filterMatchContainer.addView(tv)
            return
        }

        val matchColor   = themeColor(android.R.attr.colorPrimary)
        val noMatchColor = themeColor(android.R.attr.textColorSecondary)
        val dp = resources.displayMetrics.density

        for (filter in filters) {
            val matches = FilterEngine.matches(event, filter.criteria)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            row.isClickable = true
            row.isFocusable = true

            val nameView = TextView(this)
            nameView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            nameView.text = filter.name.ifEmpty { "Unnamed filter" }
            nameView.textSize = 14f
            row.addView(nameView)

            val statusView = TextView(this)
            statusView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            statusView.text      = if (matches) "✓ matches" else "no match"
            statusView.textSize  = 13f
            statusView.setTextColor(if (matches) matchColor else noMatchColor)
            row.addView(statusView)

            row.setOnClickListener {
                startActivity(Intent(this, FilterSettingsActivity::class.java)
                    .putExtra(FilterSettingsActivity.EXTRA_FILTER_ID, filter.id))
            }

            binding.filterMatchContainer.addView(row)
        }
    }

    // ── Filter creation ───────────────────────────────────────────────────────

    private data class CheckboxEntry(val checkbox: CheckBox, val criterion: FilterCriterion)

    private val checkboxEntries = mutableListOf<CheckboxEntry>()

    private fun populateFilterCheckboxes() {
        binding.filterCheckboxContainer.removeAllViews()
        checkboxEntries.clear()

        fun addEntry(label: String, criterion: FilterCriterion) {
            val cb = CheckBox(this)
            cb.text = label
            cb.textSize = 14f
            binding.filterCheckboxContainer.addView(cb)
            checkboxEntries.add(CheckboxEntry(cb, criterion))
        }

        addEntry(
            "Event name: \"${event.summary}\"",
            FilterCriterion.StringCriterion(FilterCriterion.StringField.EVENT_NAME,
                FilterCriterion.StringOp.EQUALS, event.summary)
        )

        if (event.location.isNotEmpty()) addEntry(
            "Location: \"${event.location}\"",
            FilterCriterion.StringCriterion(FilterCriterion.StringField.LOCATION,
                FilterCriterion.StringOp.EQUALS, event.location)
        )

        if (event.organizer.isNotEmpty()) addEntry(
            "Sender: \"${event.organizer}\"",
            FilterCriterion.StringCriterion(FilterCriterion.StringField.SENDER,
                FilterCriterion.StringOp.EQUALS, event.organizer)
        )

        if (event.sourceId.isNotEmpty()) {
            val name = sourceName(event.sourceId)
            if (name.isNotEmpty()) addEntry(
                "Calendar: \"$name\"",
                FilterCriterion.SourceCriterion(event.sourceId)
            )
        }

        for (attendee in event.attendees) addEntry(
            "Attendee: \"$attendee\"",
            FilterCriterion.StringCriterion(FilterCriterion.StringField.ATTENDEES,
                FilterCriterion.StringOp.EQUALS, attendee)
        )
    }

    private fun createFilter() {
        val criteria = checkboxEntries
            .filter { it.checkbox.isChecked }
            .map { it.criterion }
            .toMutableList()

        if (criteria.isEmpty()) {
            Toast.makeText(this, "Check at least one item", Toast.LENGTH_SHORT).show()
            return
        }

        val filter = EventFilter(
            id       = UUID.randomUUID().toString(),
            name     = event.summary,
            criteria = criteria
        )
        prefs.saveFilter(filter)

        startActivity(Intent(this, FilterSettingsActivity::class.java)
            .putExtra(FilterSettingsActivity.EXTRA_FILTER_ID, filter.id))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun themeColor(attr: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun sourceName(sourceId: String): String {
        if (sourceId.startsWith("local_")) {
            val calId = sourceId.removePrefix("local_").toLongOrNull() ?: return ""
            return prefs.localCalendarName(calId)
        }
        return prefs.icsSourceName(sourceId)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

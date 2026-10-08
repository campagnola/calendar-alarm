package com.example.calendaralarm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendaralarm.databinding.ActivitySourceSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class SourceSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL      = "url"
        const val EXTRA_IS_LOCAL = "is_local"
        private const val PERM_REQUEST_CALENDAR = 200
    }

    private lateinit var binding: ActivitySourceSettingsBinding
    private lateinit var prefs: Prefs

    // ICS mode state
    private var originalUrl = ""
    private var isNewIcs    = false

    // true when operating in local-calendar mode
    private var isLocalMode = false

    // Populated after calendar permission is granted
    private var deviceCalendars: List<LocalCalendarReader.DeviceCalendar> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs       = Prefs(this)
        originalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val forceLocal = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)

        // Determine mode:
        //   forceLocal  → editing the existing local source
        //   originalUrl → editing an existing ICS source
        //   neither     → new source: default local if not yet added, else ICS
        isLocalMode = when {
            forceLocal          -> true
            originalUrl.isNotEmpty() -> false
            else                -> !prefs.localCalendarEnabled
        }
        isNewIcs = !isLocalMode && originalUrl.isEmpty()

        supportActionBar?.title = when {
            forceLocal              -> "Local calendars"
            isLocalMode             -> "New calendar"
            originalUrl.isNotEmpty()-> "Calendar source"
            else                    -> "New calendar"
        }

        // Show the type selector only when adding a brand-new source (no type determined yet).
        val showTypeSelector = originalUrl.isEmpty() && !forceLocal
        if (showTypeSelector) {
            binding.typeGroup.visibility = View.VISIBLE
            if (isLocalMode) binding.localRadio.isChecked = true
            else             binding.icsRadio.isChecked   = true
            binding.typeGroup.setOnCheckedChangeListener { _, checkedId ->
                isLocalMode = (checkedId == binding.localRadio.id)
                applyMode()
            }
        } else {
            binding.typeGroup.visibility = View.GONE
        }

        applyMode()

        // ICS: pre-fill fields when editing
        if (!isLocalMode && originalUrl.isNotEmpty()) {
            binding.urlInput.setText(originalUrl)
            val id = prefs.urlId(originalUrl)
            binding.nameInput.setText(prefs.icsSourceName(id))
            refreshStatus(id)
        }

        // Show Delete when editing something that already exists.
        binding.deleteButton.visibility =
            if (forceLocal || (!isLocalMode && originalUrl.isNotEmpty()))
                View.VISIBLE else View.GONE

        binding.reloadButton.setOnClickListener { doReload() }
        binding.saveButton.setOnClickListener   { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

    private fun applyMode() {
        binding.icsSectionLayout.visibility   = if (isLocalMode) View.GONE  else View.VISIBLE
        binding.localSectionLayout.visibility = if (isLocalMode) View.VISIBLE else View.GONE
        if (isLocalMode) loadCalendarsIfPermitted()
    }

    // ── Local calendar list ───────────────────────────────────────────────────

    private fun loadCalendarsIfPermitted() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            binding.calendarPermissionText.visibility = View.GONE
            if (deviceCalendars.isEmpty()) loadCalendarList()
        } else {
            binding.calendarPermissionText.visibility = View.VISIBLE
            binding.calendarPermissionText.setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), PERM_REQUEST_CALENDAR)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CALENDAR &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            binding.calendarPermissionText.visibility = View.GONE
            loadCalendarList()
        }
    }

    private fun loadCalendarList() {
        lifecycleScope.launch {
            deviceCalendars = withContext(Dispatchers.IO) {
                LocalCalendarReader.availableCalendars(this@SourceSettingsActivity)
            }
            populateCalendarList()
        }
    }

    private fun populateCalendarList() {
        val container   = binding.calendarListContainer
        val selectedIds = prefs.localCalendarIds
        container.removeAllViews()
        for (cal in deviceCalendars) {
            val cb = CheckBox(this).apply {
                text      = if (cal.accountName.isNotEmpty() && cal.accountName != cal.name)
                                "${cal.name}  (${cal.accountName})" else cal.name
                isChecked = cal.id in selectedIds
                tag       = cal.id
                val px = (4 * resources.displayMetrics.density).toInt()
                setPadding(px, px, px, px)
            }
            container.addView(cb, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun selectedCalendarIds(): Set<Long> {
        val container = binding.calendarListContainer
        return (0 until container.childCount)
            .mapNotNull { container.getChildAt(it) as? CheckBox }
            .filter { it.isChecked }
            .map { it.tag as Long }
            .toSet()
    }

    // ── ICS helpers ───────────────────────────────────────────────────────────

    private fun doReload() {
        val url = binding.urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.statusText.text = "Loading…"
        binding.reloadButton.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = Instant.ofEpochMilli(AppClock.now())
                FeedSync.sync(this@SourceSettingsActivity, url, now, now.plus(Duration.ofDays(1)))
            }
            val id = prefs.urlId(url)
            if (binding.nameInput.text.isNullOrEmpty()) {
                val stored = prefs.icsSourceName(id)
                if (stored.isNotEmpty()) binding.nameInput.setText(stored)
            }
            refreshStatus(id)
            binding.reloadButton.isEnabled = true
        }
    }

    private fun refreshStatus(id: String) {
        val status  = prefs.feedStatus(id)
        val summary = status.summary(prefs.icsLastFetchEventCount(id))
        binding.statusText.text = if (status.lastAttemptMs == 0L) summary
            else "Checked ${DateUtils.getRelativeTimeSpanString(status.lastAttemptMs)} – $summary"
    }

    // ── Save / Delete ─────────────────────────────────────────────────────────

    private fun save() {
        if (isLocalMode) saveLocal() else saveIcs()
    }

    private fun saveLocal() {
        val ids = selectedCalendarIds()
        for (cal in deviceCalendars) {
            if (cal.id in ids) prefs.setLocalCalendarName(cal.id, cal.name)
        }
        prefs.localCalendarIds     = ids
        prefs.localCalendarEnabled = true
        AlarmScheduler.schedulePoll(this, 0L)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveIcs() {
        val newUrl  = binding.urlInput.text.toString().trim()
        val newName = binding.nameInput.text.toString().trim()
        if (newUrl.isEmpty()) {
            Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
            return
        }
        val sources = prefs.icsSources.toMutableList()
        if (isNewIcs) {
            if (newUrl !in sources) sources.add(newUrl)
            prefs.icsSources = sources
        } else if (newUrl != originalUrl) {
            FeedSync.forget(this, originalUrl)
            val idx = sources.indexOf(originalUrl)
            if (idx >= 0) sources[idx] = newUrl else sources.add(newUrl)
            prefs.icsSources = sources
        }
        prefs.setIcsSourceName(prefs.urlId(newUrl), newName)
        AlarmScheduler.schedulePoll(this, 0L)
        Toast.makeText(this, if (isNewIcs) "Calendar added" else "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        if (isLocalMode) {
            AlertDialog.Builder(this)
                .setTitle("Remove local calendars")
                .setMessage("Remove the local calendar source?")
                .setPositiveButton("Remove") { _, _ ->
                    prefs.localCalendarEnabled = false
                    prefs.localCalendarIds     = emptySet()
                    AlarmScheduler.schedulePoll(this, 0L)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Remove calendar")
                .setMessage("Remove this calendar source? Cached data will be deleted.")
                .setPositiveButton("Remove") { _, _ ->
                    val sources = prefs.icsSources.toMutableList()
                    sources.remove(originalUrl)
                    prefs.icsSources = sources
                    FeedSync.forget(this, originalUrl)
                    AlarmScheduler.schedulePoll(this, 0L)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

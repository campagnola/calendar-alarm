package com.example.calendaralarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.calendaralarm.databinding.ActivitySettingsBinding
import com.example.calendaralarm.databinding.ItemFilterRowBinding
import com.example.calendaralarm.databinding.ItemSourceRowBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var soundUri: String? = null  // the picked sound, stored by save()

    private val pickSound = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        @Suppress("DEPRECATION")
        // A null pick is the picker's "Silent" choice.
        soundUri = checkNotNull(result.data) { "sound picker returned no data" }
            .getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()
        showSound()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        prefs = Prefs(this)

        binding.addUrlButton.setOnClickListener {
            startActivity(Intent(this, SourceSettingsActivity::class.java))
        }

        binding.addFilterButton.setOnClickListener {
            startActivity(Intent(this, FilterSettingsActivity::class.java))
        }

        binding.advanceInput.setText(prefs.advanceMinutes.toString())
        binding.pollIntervalInput.setText(prefs.pollIntervalMinutes.toString())
        binding.snoozeInput.setText(prefs.snoozeMinutes.toString())
        binding.showTomorrowInput.setText(minutesToHhmm(prefs.showTomorrowAtMinutes))
        binding.quietStartInput.setText(minutesToHhmm(prefs.quietHoursStartMinutes))
        binding.quietEndInput.setText(minutesToHhmm(prefs.quietHoursEndMinutes))
        binding.quietHoursAlarmCheckbox.isChecked = prefs.quietHoursAlarmEnabled

        val sound = prefs.alarmSound
        soundUri = if (savedInstanceState != null) savedInstanceState.getString(STATE_SOUND_URI) else sound.uri
        showSound()
        binding.soundButton.setOnClickListener { pickSound.launch(soundPickerIntent()) }
        binding.soundDelayInput.setText((sound.delayMs / 1000).toString())
        binding.soundFadeInput.setText((sound.fadeMs / 1000).toString())
        binding.soundStartVolumeInput.setText(sound.startVolume.toString())
        binding.soundMaxVolumeInput.setText(sound.maxVolume.toString())

        binding.saveButton.setOnClickListener { save() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SOUND_URI, soundUri)
    }

    override fun onResume() {
        super.onResume()
        populateSources()
        populateFilters()
    }

    private fun populateSources() {
        binding.urlSourcesContainer.removeAllViews()

        if (prefs.localCalendarEnabled) {
            val row = ItemSourceRowBinding.inflate(LayoutInflater.from(this),
                                                   binding.urlSourcesContainer, false)
            val count = prefs.localCalendarEventCount()
            row.sourceNameText.text   = "Local calendars"
            row.sourceStatusText.text = if (count >= 0) "$count event${if (count == 1) "" else "s"} today"
                                        else "Not yet loaded"
            row.root.setOnClickListener {
                startActivity(Intent(this, SourceSettingsActivity::class.java)
                    .putExtra(SourceSettingsActivity.EXTRA_IS_LOCAL, true))
            }
            binding.urlSourcesContainer.addView(row.root)
        }

        for (url in prefs.icsSources) {
            val row = ItemSourceRowBinding.inflate(LayoutInflater.from(this),
                                                   binding.urlSourcesContainer, false)
            val id = prefs.urlId(url)
            row.sourceNameText.text   = prefs.icsDisplayName(url)
            row.sourceStatusText.text = briefStatus(id)
            row.root.setOnClickListener {
                startActivity(Intent(this, SourceSettingsActivity::class.java)
                    .putExtra(SourceSettingsActivity.EXTRA_URL, url))
            }
            binding.urlSourcesContainer.addView(row.root)
        }
    }

    private fun populateFilters() {
        binding.filterContainer.removeAllViews()
        for (filter in prefs.eventFilters) {
            val row = ItemFilterRowBinding.inflate(LayoutInflater.from(this),
                                                  binding.filterContainer, false)
            row.filterNameText.text = filter.name.ifEmpty { "Unnamed filter" }
            row.filterStatusText.text = filterBriefStatus(filter)
            row.root.setOnClickListener {
                startActivity(Intent(this, FilterSettingsActivity::class.java)
                    .putExtra(FilterSettingsActivity.EXTRA_FILTER_ID, filter.id))
            }
            binding.filterContainer.addView(row.root)
        }
    }

    private fun filterBriefStatus(filter: EventFilter): String {
        val nc = filter.criteria.size
        val na = filter.actions.size
        val mc = prefs.filterMatchCount(filter.id)
        return buildList {
            if (mc >= 0) add("$mc ${if (mc == 1) "event" else "events"} today")
            if (nc > 0)  add("$nc ${if (nc == 1) "criterion" else "criteria"}")
            if (na > 0)  add("$na ${if (na == 1) "action" else "actions"}")
        }.joinToString(" · ").ifEmpty { "No criteria or actions" }
    }

    private fun briefStatus(id: String): String {
        return prefs.feedStatus(id).summary(prefs.icsLastFetchEventCount(id))
    }

    private fun soundPickerIntent() = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, soundUri?.let(Uri::parse))

    private fun showSound() {
        val name = soundUri?.let { RingtoneManager.getRingtone(this, Uri.parse(it))?.getTitle(this) ?: "unavailable ($it)" }
            ?: "None"
        binding.soundButton.text = "Sound: $name"
    }

    private fun minutesToHhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    private fun parseHhmm(s: String): Int? {
        val parts = s.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun save() {
        prefs.advanceMinutes      = binding.advanceInput.text.toString().toIntOrNull() ?: 5
        prefs.pollIntervalMinutes = binding.pollIntervalInput.text.toString().toIntOrNull() ?: 15
        prefs.snoozeMinutes       = binding.snoozeInput.text.toString().toIntOrNull() ?: 4
        val showTomorrow = parseHhmm(binding.showTomorrowInput.text.toString())
        if (showTomorrow != null) prefs.showTomorrowAtMinutes = showTomorrow
        else Toast.makeText(this, "Invalid time for \"Show tomorrow at\" — use HH:MM", Toast.LENGTH_SHORT).show()
        val quietStart = parseHhmm(binding.quietStartInput.text.toString())
        if (quietStart != null) prefs.quietHoursStartMinutes = quietStart
        else Toast.makeText(this, "Invalid time for \"Quiet hours start\" — use HH:MM", Toast.LENGTH_SHORT).show()
        val quietEnd = parseHhmm(binding.quietEndInput.text.toString())
        if (quietEnd != null) prefs.quietHoursEndMinutes = quietEnd
        else Toast.makeText(this, "Invalid time for \"Quiet hours end\" — use HH:MM", Toast.LENGTH_SHORT).show()
        prefs.quietHoursAlarmEnabled = binding.quietHoursAlarmCheckbox.isChecked
        val d = AlarmSound.DEFAULT
        prefs.alarmSound = AlarmSound(
            uri         = soundUri,
            delayMs     = binding.soundDelayInput.text.toString().toLongOrNull()?.times(1000) ?: d.delayMs,
            startVolume = binding.soundStartVolumeInput.text.toString().toIntOrNull()?.coerceAtMost(100) ?: d.startVolume,
            maxVolume   = binding.soundMaxVolumeInput.text.toString().toIntOrNull()?.coerceAtMost(100) ?: d.maxVolume,
            fadeMs      = binding.soundFadeInput.text.toString().toLongOrNull()?.times(1000) ?: d.fadeMs
        )
        AlarmScheduler.schedulePoll(this, 0L)

        when {
            needsExactAlarmPermission() ->
                showPermissionDialog(
                    "Alarm permission required",
                    "To fire alerts at exact times, enable \"Alarms & reminders\" for Calendar Alarm.",
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                )
            needsFullScreenPermission() ->
                showPermissionDialog(
                    "Full-screen notification required",
                    "To show the alarm over the lock screen, enable \"Display over other apps\" / full-screen notifications for Calendar Alarm.",
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                )
            else -> {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun needsExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun needsFullScreenPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    private fun showPermissionDialog(title: String, message: String, action: Intent) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ -> startActivity(action); finish() }
            .setNegativeButton("Later") { _, _ -> finish() }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val STATE_SOUND_URI = "sound_uri"
    }
}

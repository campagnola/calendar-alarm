package com.example.calendaralarm

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.calendaralarm.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        prefs = Prefs(this)
        binding.urlInput.setText(prefs.icsUrl)
        binding.advanceInput.setText(prefs.advanceMinutes.toString())
        binding.pollIntervalInput.setText(prefs.pollIntervalMinutes.toString())
        binding.snoozeInput.setText(prefs.snoozeMinutes.toString())

        binding.saveButton.setOnClickListener {
            val newUrl = binding.urlInput.text.toString().trim()
            if (newUrl != prefs.icsUrl) prefs.clearIcsCache()
            prefs.icsUrl = newUrl
            prefs.advanceMinutes = binding.advanceInput.text.toString().toIntOrNull() ?: 5
            prefs.pollIntervalMinutes = binding.pollIntervalInput.text.toString().toIntOrNull() ?: 15
            prefs.snoozeMinutes = binding.snoozeInput.text.toString().toIntOrNull() ?: 4
            AlarmScheduler.schedulePoll(this, 0L)

            if (needsExactAlarmPermission()) {
                AlertDialog.Builder(this)
                    .setTitle("Alarm permission required")
                    .setMessage(
                        "To fire alerts at exact times, this app needs the " +
                        "\"Alarms & reminders\" permission. Tap Grant to open " +
                        "the system setting, then enable it for Calendar Alarm."
                    )
                    .setPositiveButton("Grant") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        finish()
                    }
                    .setNegativeButton("Later") { _, _ -> finish() }
                    .show()
            } else {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun needsExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

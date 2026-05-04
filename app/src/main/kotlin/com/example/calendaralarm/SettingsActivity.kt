package com.example.calendaralarm

import android.app.AlarmManager
import android.app.NotificationManager
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

            when {
                needsExactAlarmPermission() ->
                    showPermissionDialog(
                        title   = "Alarm permission required",
                        message = "To fire alerts at exact times, enable \"Alarms & reminders\" for Calendar Alarm.",
                        action  = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    )
                needsFullScreenPermission() ->
                    showPermissionDialog(
                        title   = "Full-screen notification required",
                        message = "To show the alarm over the lock screen, enable \"Display over other apps\" / full-screen notifications for Calendar Alarm.",
                        action  = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                else -> {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun needsExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false  // USE_EXACT_ALARM auto-granted
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

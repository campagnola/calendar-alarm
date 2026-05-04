package com.example.calendaralarm

import android.os.Bundle
import android.widget.Toast
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
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

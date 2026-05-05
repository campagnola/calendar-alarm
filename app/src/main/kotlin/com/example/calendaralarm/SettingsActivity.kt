package com.example.calendaralarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendaralarm.databinding.ActivitySettingsBinding
import com.example.calendaralarm.databinding.ItemUrlSourceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Populate URL entries from stored sources.
        val sources = prefs.icsSources
        if (sources.isEmpty()) addUrlEntry("") else sources.forEach { addUrlEntry(it) }

        binding.addUrlButton.setOnClickListener { addUrlEntry("") }

        binding.advanceInput.setText(prefs.advanceMinutes.toString())
        binding.pollIntervalInput.setText(prefs.pollIntervalMinutes.toString())
        binding.snoozeInput.setText(prefs.snoozeMinutes.toString())

        binding.saveButton.setOnClickListener { save() }
    }

    private fun addUrlEntry(url: String) {
        val item = ItemUrlSourceBinding.inflate(LayoutInflater.from(this),
                                                binding.urlSourcesContainer, false)
        item.urlInput.setText(url)

        if (url.isNotEmpty()) {
            val id = prefs.urlId(url)
            refreshStatus(item.statusText, id)
        }

        item.reloadButton.setOnClickListener {
            val currentUrl = item.urlInput.text.toString().trim()
            if (currentUrl.isEmpty()) {
                item.statusText.text = "Enter a URL first"
                return@setOnClickListener
            }
            item.statusText.text = "Loading…"
            item.reloadButton.isEnabled = false
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { IcsFetcher.fetchOne(this@SettingsActivity, currentUrl, forceDownload = true) }
                val id = prefs.urlId(currentUrl)
                refreshStatus(item.statusText, id)
                item.reloadButton.isEnabled = true
            }
        }

        item.removeButton.setOnClickListener {
            binding.urlSourcesContainer.removeView(item.root)
        }

        binding.urlSourcesContainer.addView(item.root)
    }

    private fun refreshStatus(statusView: TextView, id: String) {
        val ms     = prefs.icsLastFetchMs(id)
        val status = prefs.icsLastFetchStatus(id)
        val count  = prefs.icsLastFetchEventCount(id)
        statusView.text = when {
            ms == 0L       -> "Not yet checked"
            status == "OK" -> {
                val countStr = if (count >= 0) "$count event${if (count == 1) "" else "s"} today"
                               else "OK"
                "Checked ${DateUtils.getRelativeTimeSpanString(ms)} – $countStr"
            }
            else           -> "Checked ${DateUtils.getRelativeTimeSpanString(ms)} – Error: $status"
        }
    }

    private fun currentUrls(): List<String> =
        (0 until binding.urlSourcesContainer.childCount)
            .mapNotNull { i ->
                val child = binding.urlSourcesContainer.getChildAt(i)
                ItemUrlSourceBinding.bind(child).urlInput.text.toString().trim().ifEmpty { null }
            }

    private fun save() {
        val oldUrls = prefs.icsSources.toSet()
        val newUrls = currentUrls()

        // Clear cached metadata for removed/changed URLs.
        oldUrls.filterNot { it in newUrls }.forEach { prefs.clearSourceCache(prefs.urlId(it)) }

        prefs.icsSources         = newUrls
        prefs.advanceMinutes     = binding.advanceInput.text.toString().toIntOrNull() ?: 5
        prefs.pollIntervalMinutes = binding.pollIntervalInput.text.toString().toIntOrNull() ?: 15
        prefs.snoozeMinutes      = binding.snoozeInput.text.toString().toIntOrNull() ?: 4
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
}

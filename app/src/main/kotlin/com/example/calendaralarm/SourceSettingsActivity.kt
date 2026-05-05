package com.example.calendaralarm

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendaralarm.databinding.ActivitySourceSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var binding: ActivitySourceSettingsBinding
    private lateinit var prefs: Prefs
    private var originalUrl = ""
    private var isNew = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = Prefs(this)
        originalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        isNew = originalUrl.isEmpty()

        supportActionBar?.title = if (isNew) "New calendar" else "Calendar source"

        if (!isNew) {
            binding.urlInput.setText(originalUrl)
            val id = prefs.urlId(originalUrl)
            binding.nameInput.setText(prefs.icsSourceName(id))
            refreshStatus(id)
        }

        binding.deleteButton.visibility = if (isNew) View.GONE else View.VISIBLE

        binding.reloadButton.setOnClickListener { doReload() }
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

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
                IcsFetcher.fetchOne(this@SourceSettingsActivity, url, forceDownload = true)
            }
            val id = prefs.urlId(url)
            if (binding.nameInput.text.isNullOrEmpty()) {
                val storedName = prefs.icsSourceName(id)
                if (storedName.isNotEmpty()) binding.nameInput.setText(storedName)
            }
            refreshStatus(id)
            binding.reloadButton.isEnabled = true
        }
    }

    private fun refreshStatus(id: String) {
        val ms     = prefs.icsLastFetchMs(id)
        val status = prefs.icsLastFetchStatus(id)
        val count  = prefs.icsLastFetchEventCount(id)
        binding.statusText.text = when {
            ms == 0L       -> "Not yet checked"
            status == "OK" -> {
                val countStr = if (count >= 0) "$count event${if (count == 1) "" else "s"} today"
                               else "OK"
                "Checked ${DateUtils.getRelativeTimeSpanString(ms)} – $countStr"
            }
            else           -> "Checked ${DateUtils.getRelativeTimeSpanString(ms)} – Error: $status"
        }
    }

    private fun save() {
        val newUrl  = binding.urlInput.text.toString().trim()
        val newName = binding.nameInput.text.toString().trim()

        if (newUrl.isEmpty()) {
            Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
            return
        }

        val sources = prefs.icsSources.toMutableList()

        if (isNew) {
            if (newUrl !in sources) sources.add(newUrl)
            prefs.icsSources = sources
        } else if (newUrl != originalUrl) {
            prefs.clearSourceCache(prefs.urlId(originalUrl))
            val idx = sources.indexOf(originalUrl)
            if (idx >= 0) sources[idx] = newUrl else sources.add(newUrl)
            prefs.icsSources = sources
        }

        prefs.setIcsSourceName(prefs.urlId(newUrl), newName)
        AlarmScheduler.schedulePoll(this, 0L)

        Toast.makeText(this, if (isNew) "Calendar added" else "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Remove calendar")
            .setMessage("Remove this calendar source? Cached data will be deleted.")
            .setPositiveButton("Remove") { _, _ ->
                val sources = prefs.icsSources.toMutableList()
                sources.remove(originalUrl)
                prefs.icsSources = sources
                prefs.clearSourceCache(prefs.urlId(originalUrl))
                AlarmScheduler.schedulePoll(this, 0L)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

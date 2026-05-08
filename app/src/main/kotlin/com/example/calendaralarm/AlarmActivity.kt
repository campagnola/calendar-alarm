package com.example.calendaralarm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class AlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm)

        val eventsJson = intent.getStringExtra("events_json") ?: return
        val arr   = JSONArray(eventsJson)
        val count = arr.length()
        val first = arr.getJSONObject(0)

        val startTime    = first.getString("start_time")
        val maxEndTimeMs = AlarmScheduler.maxEndTimeMsFromJson(eventsJson)

        // Title: single summary or "N events"
        val titleText = if (count == 1) first.getString("summary")
                        else "$count events at $startTime"
        val subtitleText = if (count == 1) "Starts at $startTime"
                           else (0 until count).joinToString("\n") { arr.getJSONObject(it).getString("summary") }

        findViewById<TextView>(R.id.alarmLabel).text =
            if (count == 1) "Upcoming Event" else "Upcoming Events"
        findViewById<TextView>(R.id.alarmTitle).text = titleText
        findViewById<TextView>(R.id.alarmTime).text  = subtitleText

        // Location: first non-empty across all events
        val location = (0 until count).firstNotNullOfOrNull {
            arr.getJSONObject(it).optString("location", "").takeIf { s -> s.isNotEmpty() }
        }
        val locationView = findViewById<TextView>(R.id.alarmLocation)
        if (location != null && count == 1) {
            locationView.text = location
            locationView.visibility = View.VISIBLE
        } else {
            locationView.visibility = View.GONE
        }

        // Join button: first event with a meeting URL (single-event only for clean UX)
        val meetingUrl = if (count == 1) first.optString("meeting_url", "").takeIf { it.isNotEmpty() }
                         else null
        val joinButton = findViewById<Button>(R.id.joinButton)
        if (meetingUrl != null) {
            joinButton.text = if (meetingUrl.contains("zoom.us", ignoreCase = true)) "Join Zoom" else "Join Teams"
            joinButton.visibility = View.VISIBLE
            joinButton.setOnClickListener {
                sendBroadcast(Intent(this, ActionReceiver::class.java).apply {
                    action = ActionReceiver.ACTION_JOIN
                    putExtra("meeting_url", meetingUrl)
                })
                finish()
            }
        } else {
            joinButton.visibility = View.GONE
        }

        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_DISMISS
            })
            finish()
        }

        val prefs = Prefs(this)
        val snoozeUntilMs = AlarmScheduler.computeSnoozeUntilMs(maxEndTimeMs, prefs)
        val snoozeUntilTime = java.time.Instant.ofEpochMilli(snoozeUntilMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val snoozeButton = findViewById<Button>(R.id.snoozeButton)
        snoozeButton.text = "Snooze until ${snoozeUntilTime.format(timeFormatter(this))}"
        snoozeButton.setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_SNOOZE
                putExtra("events_json", eventsJson)
            })
            finish()
        }
    }
}

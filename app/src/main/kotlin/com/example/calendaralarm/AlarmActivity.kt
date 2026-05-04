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

        val summary    = intent.getStringExtra("summary")     ?: "Event"
        val startTime  = intent.getStringExtra("start_time")  ?: ""
        val uid        = intent.getStringExtra("uid")         ?: ""
        val location   = intent.getStringExtra("location")    ?: ""
        val meetingUrl = intent.getStringExtra("meeting_url")

        findViewById<TextView>(R.id.alarmTitle).text = summary
        findViewById<TextView>(R.id.alarmTime).text  = "Starts at $startTime"

        val locationView = findViewById<TextView>(R.id.alarmLocation)
        if (location.isNotEmpty()) {
            locationView.text = location
            locationView.visibility = View.VISIBLE
        } else {
            locationView.visibility = View.GONE
        }

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

        findViewById<Button>(R.id.snoozeButton).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_SNOOZE
                putExtra("summary",     summary)
                putExtra("start_time",  startTime)
                putExtra("uid",         uid)
                putExtra("location",    location)
                putExtra("meeting_url", meetingUrl)
            })
            finish()
        }
    }
}

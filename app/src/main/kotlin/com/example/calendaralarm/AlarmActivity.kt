package com.example.calendaralarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen and wake the display
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

        val summary   = intent.getStringExtra("summary")    ?: "Event"
        val startTime = intent.getStringExtra("start_time") ?: ""
        val uid       = intent.getStringExtra("uid")        ?: ""

        findViewById<TextView>(R.id.alarmTitle).text = summary
        findViewById<TextView>(R.id.alarmTime).text  = "Starts at $startTime"

        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_DISMISS
            })
            finish()
        }

        findViewById<Button>(R.id.snoozeButton).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_SNOOZE
                putExtra("summary", summary)
                putExtra("start_time", startTime)
                putExtra("uid", uid)
            })
            finish()
        }
    }
}

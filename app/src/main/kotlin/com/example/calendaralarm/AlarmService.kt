package com.example.calendaralarm

import android.app.Service
import android.content.Intent
import android.os.*

class AlarmService : Service() {

    private var vibrator: Vibrator? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val summary = intent?.getStringExtra("summary") ?: "Event"
        val startTime = intent?.getStringExtra("start_time") ?: ""
        val uid = intent?.getStringExtra("uid") ?: ""

        val notification = NotificationHelper.buildAlarmNotification(this, summary, startTime, uid)
        startForeground(NotificationHelper.ALARM_NOTIF_ID, notification)

        startVibration()
        return START_NOT_STICKY
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        // Pattern: 0ms wait, 800ms on, 400ms off — repeating from index 0
        val pattern = longArrayOf(0, 800, 400)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    override fun onDestroy() {
        vibrator?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

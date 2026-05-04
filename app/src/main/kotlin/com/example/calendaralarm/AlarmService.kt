package com.example.calendaralarm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log

class AlarmService : Service() {

    private var vibrator: Vibrator? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CalAlarm", "AlarmService onStartCommand")
        val summary    = intent?.getStringExtra("summary")     ?: "Event"
        val startTime  = intent?.getStringExtra("start_time")  ?: ""
        val uid        = intent?.getStringExtra("uid")         ?: ""
        val location   = intent?.getStringExtra("location")    ?: ""
        val meetingUrl = intent?.getStringExtra("meeting_url")

        val notification = NotificationHelper.buildAlarmNotification(this, summary, startTime, uid, location, meetingUrl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.ALARM_NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationHelper.ALARM_NOTIF_ID, notification)
        }

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

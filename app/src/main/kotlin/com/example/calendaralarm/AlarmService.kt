package com.example.calendaralarm

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log

class AlarmService : Service() {

    private var vibrator: Vibrator? = null
    private var eventsJson: String? = null
    private var screenOnReceiver: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CalAlarm", "AlarmService onStartCommand")
        eventsJson = intent?.getStringExtra("events_json") ?: return START_NOT_STICKY

        val notification = NotificationHelper.buildAlarmNotification(this, eventsJson!!)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.ALARM_NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationHelper.ALARM_NOTIF_ID, notification)
        }

        startVibration()
        registerScreenOnReceiver()
        return START_NOT_STICKY
    }

    private fun registerScreenOnReceiver() {
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val evJson = eventsJson ?: return
                // Re-launch AlarmActivity when the screen wakes — showWhenLocked on the
                // activity handles appearing over the lock screen, but only triggers when
                // the activity is actively starting/resuming, not when it's already paused.
                context.startActivity(
                    Intent(context, AlarmActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra("events_json", evJson)
                    }
                )
            }
        }
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
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
        screenOnReceiver?.let { unregisterReceiver(it) }
        screenOnReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

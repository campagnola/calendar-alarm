package com.example.calendaralarm

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.*

/**
 * The sole owner of the ringing hardware and the ringing notifications. It renders every RINGING
 * alarm in [AlarmStore] (one notification each, one vibration, one sound) and stops itself when none remain.
 */
class AlarmService : Service() {

    private var vibrator: Vibrator? = null
    private var screenOnReceiver: BroadcastReceiver? = null
    private lateinit var sound: AlarmSoundPlayer

    override fun onCreate() {
        super.onCreate()
        sound = AlarmSoundPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is the system restarting the service after its process died.
        AppLog.i("AlarmService onStartCommand startId=$startId alarm=${intent?.getStringExtra(EXTRA_ALARM_ID) ?: "(restart)"}")
        if (!render()) {
            AppLog.i("AlarmService: nothing ringing, stopping")
            stopSelf(startId)
        }
        return START_STICKY
    }

    /** Posts a notification for each ringing alarm and rings; false if nothing is ringing. */
    private fun render(): Boolean {
        val ringing = AlarmStore.from(this).ringing()
        if (ringing.isEmpty()) {
            sound.update(null)
            return false
        }
        AppLog.i("AlarmService: rendering ${ringing.map { it.id }}")

        // The most recent alarm holds the foreground slot; the others are plain notifications.
        val notifications = ringing.map {
            NotificationHelper.ringingNotificationId(it.id) to NotificationHelper.buildRingingNotification(this, it)
        }
        val (foregroundId, foreground) = notifications.first()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(foregroundId, foreground, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(foregroundId, foreground)
        }
        val nm = getSystemService(NotificationManager::class.java)
        notifications.drop(1).forEach { (id, notification) -> nm.notify(id, notification) }
        cancelRingingNotifications(except = notifications.map { it.first }.toSet())

        if (!PermissionPrompt.FULL_SCREEN_INTENT.isGranted(this)) {
            AppLog.w("AlarmService: full-screen intent not permitted, the alarm screen cannot appear")
            NotificationHelper.showPermissionReminder(this, PermissionPrompt.FULL_SCREEN_INTENT)
        }
        if (vibrator == null) startVibration()
        // The sound follows the alarm that has rung longest.
        sound.update(ringing.minOf { it.updatedMs })
        if (screenOnReceiver == null) registerScreenOnReceiver()
        return true
    }

    /** Removes ringing notifications of alarms that stopped ringing, including any left by a dead process. */
    private fun cancelRingingNotifications(except: Set<Int>) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            .filter { it.notification.channelId == NotificationHelper.CHANNEL_ALARM && it.id !in except }
            .forEach { nm.cancel(it.id) }
    }

    // Re-posting (rather than startActivity, which background-launch rules block) lets the
    // system show the full-screen intent when locked, or a heads-up otherwise.
    private fun registerScreenOnReceiver() {
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                AppLog.i("AlarmService: re-posting notifications (${intent.action})")
                render()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenOnReceiver, filter)
    }

    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        this.vibrator = vibrator
        // Pattern: 0ms wait, 800ms on, 400ms off — repeating from index 0
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("AlarmService onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.i("AlarmService onDestroy")
        vibrator?.cancel()
        sound.update(null)
        screenOnReceiver?.let { unregisterReceiver(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelRingingNotifications(except = emptySet())
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

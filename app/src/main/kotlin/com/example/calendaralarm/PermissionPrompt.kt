package com.example.calendaralarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** A permission alarms depend on, which the user grants in system Settings. */
enum class PermissionPrompt(val title: String, val message: String, private val settingsAction: String) {
    FULL_SCREEN_INTENT(
        "Lock-screen alarm permission needed",
        "To show alarm alerts when your phone is locked, grant this app the 'Display over lock screen' permission in Settings.",
        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
    ),
    EXACT_ALARM(
        "Exact alarm permission needed",
        "Alarms may ring late until you grant this app the 'Alarms & reminders' permission in Settings.",
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
    );

    fun isGranted(context: Context): Boolean = when (this) {
        FULL_SCREEN_INTENT -> Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        EXACT_ALARM -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun settingsIntent(context: Context): Intent =
        Intent(settingsAction, Uri.parse("package:${context.packageName}"))
}

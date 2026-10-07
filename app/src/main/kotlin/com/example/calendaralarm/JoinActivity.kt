package com.example.calendaralarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Joins an alarm's meeting: dismisses the alarm and opens the meeting link. Has no UI of its own. */
class JoinActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id  = requireNotNull(intent.getStringExtra(EXTRA_ALARM_ID)) { "JoinActivity started without an alarm id" }
        val url = requireNotNull(intent.getStringExtra(EXTRA_MEETING_URL)) { "JoinActivity started without a meeting url" }
        AppLog.i("JoinActivity: joining meeting of $id")
        AlarmController.from(this).dismiss(id)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        finish()
    }

    companion object {
        private const val EXTRA_MEETING_URL = "meeting_url"

        fun intent(context: Context, alarmId: String, meetingUrl: String): Intent =
            Intent(context, JoinActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_MEETING_URL, meetingUrl)
    }
}

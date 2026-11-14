package com.example.calendaralarm

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * The alarm screen: a view of one ringing alarm in [AlarmStore]. It closes itself once that alarm
 * stops ringing, by any path; its buttons only send commands to the [AlarmController].
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var alarmId: String

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
        volumeControlStream = AudioManager.STREAM_ALARM

        setContentView(R.layout.activity_alarm)

        // Back is ignored; only the buttons end an alarm.
        onBackPressedDispatcher.addCallback(this) {
            AppLog.i("AlarmActivity: Back ignored")
        }

        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            AlarmController.from(this).dismiss(alarmId)
        }
        findViewById<Button>(R.id.snoozeButton).setOnClickListener {
            AlarmController.from(this).snooze(alarmId)
        }

        AppLog.i("AlarmActivity onCreate")
        alarmId = alarmIdFrom(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmStore.changes.collect { render() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i("AlarmActivity onNewIntent")
        setIntent(intent)
        alarmId = alarmIdFrom(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("AlarmActivity onResume")
    }

    override fun onPause() {
        AppLog.i("AlarmActivity onPause")
        super.onPause()
    }

    override fun onDestroy() {
        AppLog.i("AlarmActivity onDestroy")
        super.onDestroy()
    }

    private fun alarmIdFrom(intent: Intent): String =
        requireNotNull(intent.getStringExtra(EXTRA_ALARM_ID)) { "AlarmActivity started without an alarm id" }

    private fun render() {
        val instance = AlarmStore.from(this).find(alarmId)
        if (instance?.state != AlarmInstance.State.RINGING) {
            AppLog.i("AlarmActivity: $alarmId is ${instance?.state ?: "unknown"}, closing")
            finish()
            return
        }
        AppLog.i("AlarmActivity: showing $alarmId")
        val presentation = AlarmPresentation(this, instance)

        findViewById<TextView>(R.id.alarmLabel).text = presentation.heading
        findViewById<TextView>(R.id.alarmTitle).text = presentation.title
        findViewById<TextView>(R.id.alarmTime).text  = presentation.subtitle
        findViewById<TextView>(R.id.alarmLocation).showText(presentation.location)
        findViewById<Button>(R.id.snoozeButton).text = presentation.snoozeLabel

        val joinButton = findViewById<Button>(R.id.joinButton)
        joinButton.showText(presentation.joinLabel)
        joinButton.setOnClickListener {
            startActivity(JoinActivity.intent(this, instance.id, checkNotNull(presentation.meetingUrl)))
        }
    }

    private fun TextView.showText(value: String?) {
        text = value
        visibility = if (value == null) View.GONE else View.VISIBLE
    }
}

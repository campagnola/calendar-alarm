package com.example.calendaralarm

import android.content.Context

/** How an alarm is described to the user; shared by its notifications and its screen. */
class AlarmPresentation(private val context: Context, private val instance: AlarmInstance) {
    private val events = instance.events
    private val first = events.first()
    private val single = events.size == 1
    private val startTime = formatTime(context, first.startMs)

    val heading: String = if (single) "Upcoming Event" else "Upcoming Events"
    val title: String = if (single) first.summary else "${events.size} events at $startTime"
    val subtitle: String = if (single) "Starts at $startTime" else events.joinToString("\n") { it.summary }
    val location: String? = first.location.takeIf { single && it.isNotEmpty() }

    /** Joining is offered for a single event only. */
    val meetingUrl: String? = first.meetingUrl.takeIf { single }
    val joinLabel: String? = meetingUrl?.let(::joinLabel)

    val snoozeLabel: String get() {
        check(instance.state == AlarmInstance.State.RINGING) { "snooze label for ${instance.id} in state ${instance.state}" }
        val untilMs = AlarmStateMachine.snoozeUntilMs(instance, AppClock.now(), Prefs(context).snoozeMinutes)
        return "Snooze until ${formatTime(context, untilMs)}"
    }

    companion object {
        fun joinLabel(meetingUrl: String): String =
            if (meetingUrl.contains("zoom.us", ignoreCase = true)) "Join Zoom" else "Join Teams"
    }
}

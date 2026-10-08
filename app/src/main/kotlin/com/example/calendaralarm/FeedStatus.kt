package com.example.calendaralarm

import android.text.format.DateUtils
import org.json.JSONObject

/**
 * Sync health of one ICS feed. [problem] describes why the latest attempt failed, and is null
 * while the feed is healthy; [failingSinceMs] is when the current run of failures began.
 */
data class FeedStatus(
    val lastAttemptMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val problem: String? = null,
    val failingSinceMs: Long = 0L,
    val notified: Boolean = false
) {
    fun afterAttempt(nowMs: Long, problem: String?): FeedStatus =
        if (problem == null) FeedStatus(lastAttemptMs = nowMs, lastSuccessMs = nowMs)
        else copy(lastAttemptMs = nowMs, problem = problem,
                  failingSinceMs = if (this.problem == null) nowMs else failingSinceMs)

    /** One line for the settings screens, given today's event count (-1 = unknown). */
    fun summary(eventsToday: Int): String = when {
        lastAttemptMs == 0L -> "Not yet checked"
        problem != null     -> "Error: $problem"
        eventsToday >= 0    -> "$eventsToday event${if (eventsToday == 1) "" else "s"} today"
        else                -> "OK"
    }

    /** Body of the warning shown while failing; its heading is [problemTitle]. */
    fun problemDetails(): String = "Last successful sync: " +
        (if (lastSuccessMs == 0L) "never" else DateUtils.getRelativeTimeSpanString(lastSuccessMs)) +
        "\n$problem"

    /** True once the feed has been failing too long to be a temporary outage. */
    fun isOverdue(nowMs: Long, pollIntervalMinutes: Int): Boolean =
        problem != null && nowMs - failingSinceMs > maxOf(60L, 2L * pollIntervalMinutes) * 60_000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("lastAttemptMs", lastAttemptMs)
        put("lastSuccessMs", lastSuccessMs)
        problem?.let { put("problem", it) }
        put("failingSinceMs", failingSinceMs)
        put("notified", notified)
    }

    companion object {
        fun problemTitle(feedName: String) = "$feedName is not syncing"

        fun fromJson(json: JSONObject) = FeedStatus(
            lastAttemptMs  = json.getLong("lastAttemptMs"),
            lastSuccessMs  = json.getLong("lastSuccessMs"),
            problem        = if (json.has("problem")) json.getString("problem") else null,
            failingSinceMs = json.getLong("failingSinceMs"),
            notified       = json.getBoolean("notified")
        )
    }
}

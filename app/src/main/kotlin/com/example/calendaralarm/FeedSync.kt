package com.example.calendaralarm

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant

/**
 * Downloads and parses one ICS feed, recording the outcome as its [FeedStatus] and warning the
 * user by notification once a feed has been failing for longer than a temporary outage.
 */
object FeedSync {
    private val _attempts = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after every sync attempt, once the feed's cache and [FeedStatus] are updated. */
    val attempts: SharedFlow<Unit> = _attempts

    /** Events in [windowStart, windowEnd), or null when the feed has no data at all. */
    suspend fun sync(context: Context, url: String, windowStart: Instant, windowEnd: Instant): List<CalendarEvent>? {
        val prefs  = Prefs(context)
        val id     = prefs.urlId(url)
        val source = IcsFetcher.fetchOne(context, url)
        val parsed = source.text?.let { IcsParser.parse(it, windowStart, windowEnd, id) }
        parsed?.problems?.forEach { AppLog.w("FeedSync [$id]: $it") }
        val problem = describe(source, parsed)

        val now = AppClock.now()
        var status = prefs.feedStatus(id).afterAttempt(now, problem)
        when {
            status.problem == null -> NotificationHelper.cancelFeedProblem(context, id)
            status.isOverdue(now, prefs.pollIntervalMinutes) && !status.notified -> {
                NotificationHelper.showFeedProblem(context, id, prefs.icsDisplayName(url), status)
                status = status.copy(notified = true)
            }
        }
        prefs.setFeedStatus(id, status)
        _attempts.tryEmit(Unit)
        return parsed?.events
    }

    /** Drops everything stored about a feed that is no longer configured. */
    fun forget(context: Context, url: String) {
        val prefs = Prefs(context)
        val id = prefs.urlId(url)
        prefs.clearSourceCache(id)
        NotificationHelper.cancelFeedProblem(context, id)
    }

    private fun describe(source: IcsFetcher.FetchedSource, parsed: IcsParser.ParsedFeed?): String? {
        val problems = parsed?.problems.orEmpty()
        return listOfNotNull(
            source.downloadError?.let { "Download failed: $it" },
            problems.firstOrNull()?.let { if (problems.size > 1) "$it (and ${problems.size - 1} more)" else it }
        ).joinToString("\n").ifEmpty { null }
    }
}

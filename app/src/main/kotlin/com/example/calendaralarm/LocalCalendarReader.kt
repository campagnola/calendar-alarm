package com.example.calendaralarm

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object LocalCalendarReader {

    data class DeviceCalendar(val id: Long, val name: String, val accountName: String)

    fun availableCalendars(context: Context): List<DeviceCalendar> {
        val result = mutableListOf<DeviceCalendar>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection,
            null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val nameCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val acctCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            while (cursor.moveToNext()) {
                result.add(DeviceCalendar(
                    id          = cursor.getLong(idCol),
                    name        = cursor.getString(nameCol) ?: "",
                    accountName = cursor.getString(acctCol) ?: ""
                ))
            }
        }
        return result
    }

    fun fetchEvents(
        context: Context,
        calendarIds: Set<Long>,
        windowStart: LocalDateTime,
        windowEnd: LocalDateTime
    ): List<CalendarEvent> {
        if (calendarIds.isEmpty()) return emptyList()

        val zone    = ZoneId.systemDefault()
        val startMs = windowStart.atZone(zone).toInstant().toEpochMilli()
        val endMs   = windowEnd.atZone(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, startMs); ContentUris.appendId(it, endMs) }
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ORGANIZER,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.UID_2445,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Events.RRULE
        )

        val idList    = calendarIds.joinToString(",")
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($idList)"

        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri, projection, selection, null, CalendarContract.Instances.BEGIN
        )?.use { cursor ->
            val eventIdCol  = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val titleCol    = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginCol    = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endCol      = cursor.getColumnIndex(CalendarContract.Instances.END)
            val allDayCol   = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val locationCol = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val orgCol      = cursor.getColumnIndex(CalendarContract.Instances.ORGANIZER)
            val descCol     = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
            val uidCol      = cursor.getColumnIndex(CalendarContract.Instances.UID_2445)
            val rruleCol    = cursor.getColumnIndex(CalendarContract.Events.RRULE)

            while (cursor.moveToNext()) {
                val eventId  = cursor.getLong(eventIdCol)
                val uid      = cursor.getString(uidCol)?.takeIf { it.isNotEmpty() }
                               ?: "local_$eventId"
                val beginMs  = cursor.getLong(beginCol)
                val endMs2   = cursor.getLong(endCol)
                val isAllDay = cursor.getInt(allDayCol) != 0
                val desc     = cursor.getString(descCol) ?: ""
                val isSeries = rruleCol >= 0 && !cursor.getString(rruleCol).isNullOrEmpty()

                val startDt = eventDateTime(beginMs, isAllDay, zone)
                val endDt   = eventDateTime(endMs2, isAllDay, zone)

                val calId = cursor.getLong(cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID))
                events.add(CalendarEvent(
                    uid         = uid,
                    summary     = cursor.getString(titleCol) ?: "(No title)",
                    start       = startDt,
                    end         = endDt,
                    isAllDay    = isAllDay,
                    location    = cursor.getString(locationCol) ?: "",
                    organizer   = cursor.getString(orgCol)?.removePrefix("mailto:") ?: "",
                    meetingUrl  = extractMeetingUrl(desc),
                    sourceId    = "local_$calId",
                    description = desc,
                    isSeries    = isSeries
                ))
            }
        }
        return events
    }

    private val zoomRe  = Regex("""https://[^\s]*zoom\.us/j/[^\s<"']+""")
    private val teamsRe = Regex("""https://teams\.microsoft\.com/l/meetup-join/[^\s<"']+""")

    private fun extractMeetingUrl(desc: String): String? =
        zoomRe.find(desc)?.value ?: teamsRe.find(desc)?.value

    private fun eventDateTime(epochMs: Long, isAllDay: Boolean, zone: ZoneId): LocalDateTime =
        if (isAllDay) {
            LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC).atStartOfDay()
        } else {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        }
}

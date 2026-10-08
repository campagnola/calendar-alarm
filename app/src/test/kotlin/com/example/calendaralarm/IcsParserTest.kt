package com.example.calendaralarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone

/** Device zone is pinned to Pacific so feed zones differ from it, as on the real phone. */
class IcsParserTest {
    private lateinit var savedTz: TimeZone
    @Before fun pinPacific() { savedTz = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")) }
    @After fun restoreTz() { TimeZone.setDefault(savedTz) }

    // VTIMEZONE blocks copied verbatim from an Outlook-published feed.
    private val mountain = """
        BEGIN:VTIMEZONE
        TZID:Mountain Standard Time
        BEGIN:STANDARD
        DTSTART:16010101T020000
        TZOFFSETFROM:-0600
        TZOFFSETTO:-0700
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:16010101T020000
        TZOFFSETFROM:-0700
        TZOFFSETTO:-0600
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3
        END:DAYLIGHT
        END:VTIMEZONE
    """.trimIndent()

    private val alberta = """
        BEGIN:VTIMEZONE
        TZID:Alberta Standard Time
        BEGIN:STANDARD
        DTSTART:16010101T000000
        TZOFFSETFROM:-0600
        TZOFFSETTO:-0700
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1TH;BYMONTH=1
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:16010101T020000
        TZOFFSETFROM:-0700
        TZOFFSETTO:-0600
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3
        END:DAYLIGHT
        END:VTIMEZONE
    """.trimIndent()

    private val pacific = """
        BEGIN:VTIMEZONE
        TZID:Pacific Standard Time
        BEGIN:STANDARD
        DTSTART:16010101T020000
        TZOFFSETFROM:-0700
        TZOFFSETTO:-0800
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:16010101T020000
        TZOFFSETFROM:-0800
        TZOFFSETTO:-0700
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3
        END:DAYLIGHT
        END:VTIMEZONE
    """.trimIndent()

    private fun calendar(vararg blocks: String) =
        (listOf("BEGIN:VCALENDAR") + blocks + "END:VCALENDAR").joinToString("\r\n")

    private fun event(vararg props: String) =
        (listOf("BEGIN:VEVENT", "UID:test@example.com", "SUMMARY:test") + props + "END:VEVENT").joinToString("\n")

    private fun parse(ics: String, from: String = "2026-01-01T00:00:00Z", to: String = "2027-01-01T00:00:00Z") =
        IcsParser.parse(ics, Instant.parse(from), Instant.parse(to)).events

    @Test fun `Outlook Mountain Standard Time is resolved from the feed's VTIMEZONE`() {
        val ics = calendar(mountain, event(
            "DTSTART;TZID=Mountain Standard Time:20261007T162000",
            "DTEND;TZID=Mountain Standard Time:20261007T165000"))
        val ev = parse(ics).single()
        assertEquals(Instant.parse("2026-10-07T22:20:00Z"), ev.start)
        assertEquals(Instant.parse("2026-10-07T22:50:00Z"), ev.end)
        assertEquals(LocalDateTime.parse("2026-10-07T15:20"), ev.localStart)
    }

    @Test fun `Alberta Standard Time follows the feed's own rules in summer and winter`() {
        val summer = calendar(alberta, event("DTSTART;TZID=Alberta Standard Time:20261007T160600"))
        assertEquals(Instant.parse("2026-10-07T22:06:00Z"), parse(summer).single().start)

        // After the first Thursday of January the feed switches back to -07:00.
        val winter = calendar(alberta, event("DTSTART;TZID=Alberta Standard Time:20270115T160600"))
        assertEquals(Instant.parse("2027-01-15T23:06:00Z"),
            parse(winter, to = "2028-01-01T00:00:00Z").single().start)
    }

    @Test fun `VTIMEZONE after the events that use it is still applied`() {
        val ics = calendar(event("DTSTART;TZID=Mountain Standard Time:20261007T162000"), mountain)
        assertEquals(Instant.parse("2026-10-07T22:20:00Z"), parse(ics).single().start)
    }

    @Test fun `local times follow the device to a new time zone`() {
        val ev = parse(calendar(mountain, event("DTSTART;TZID=Mountain Standard Time:20261007T162000"))).single()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(LocalDateTime.parse("2026-10-07T18:20"), ev.localStart)
    }

    @Test fun `event with an unknown TZID is reported and the others are kept`() {
        val bad = event("DTSTART;TZID=Nowhere Standard Time:20261007T162000").replace("SUMMARY:test", "SUMMARY:bad")
        val ics = calendar(bad, event("DTSTART:20261007T162000Z"))
        val parsed = IcsParser.parse(ics, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        assertEquals(listOf(Instant.parse("2026-10-07T16:20:00Z")), parsed.events.map { it.start })
        assertEquals(1, parsed.problems.size)
        assertTrue(parsed.problems.single(), parsed.problems.single().contains("'bad'"))
    }

    @Test fun `unreadable VTIMEZONE is reported`() {
        val ics = calendar(mountain.replace("BYDAY=1SU", "BYDAY=SU"),
            event("DTSTART;TZID=Mountain Standard Time:20261007T162000"))
        val parsed = IcsParser.parse(ics, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        assertEquals(emptyList<CalendarEvent>(), parsed.events)
        assertEquals(2, parsed.problems.size)
    }

    @Test fun `IANA TZID without a VTIMEZONE is resolved`() {
        val ics = calendar(event("DTSTART;TZID=America/Denver:20261007T162000"))
        assertEquals(Instant.parse("2026-10-07T22:20:00Z"), parse(ics).single().start)
    }

    @Test fun `quoted TZID containing a colon is parsed`() {
        val tz = mountain.replace("TZID:Mountain Standard Time", "TZID:(UTC-07:00) Mountain Time")
        val ics = calendar(tz, event("DTSTART;TZID=\"(UTC-07:00) Mountain Time\":20261007T162000"))
        assertEquals(Instant.parse("2026-10-07T22:20:00Z"), parse(ics).single().start)
    }

    @Test fun `floating time is device-local`() {
        val ics = calendar(event("DTSTART:20261007T162000"))
        assertEquals(Instant.parse("2026-10-07T23:20:00Z"), parse(ics).single().start)
    }

    @Test fun `weekly recurrence keeps the organizer's weekday when it differs from the device's`() {
        // Monday 09:00 in Tokyo is Sunday 17:00 in Pacific.
        val ics = calendar(event(
            "DTSTART;TZID=Asia/Tokyo:20261005T090000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=3"))
        assertEquals(
            listOf("2026-10-05T00:00:00Z", "2026-10-12T00:00:00Z", "2026-10-19T00:00:00Z").map(Instant::parse),
            parse(ics).map { it.start })
    }

    @Test fun `weekly recurrence keeps wall time across the feed zone's DST change`() {
        val ics = calendar(pacific, event(
            "DTSTART;TZID=Pacific Standard Time:20261026T100000",
            "RRULE:FREQ=WEEKLY;COUNT=2"))
        assertEquals(
            listOf("2026-10-26T17:00:00Z", "2026-11-02T18:00:00Z").map(Instant::parse),
            parse(ics).map { it.start })
    }

    @Test fun `UTC UNTIL and EXDATE are compared as instants`() {
        val ics = calendar(mountain, event(
            "DTSTART;TZID=Mountain Standard Time:20261005T090000",
            "RRULE:FREQ=DAILY;UNTIL=20261008T150000Z",
            "EXDATE:20261006T150000Z"))
        // 09:00 MDT is 15:00Z; UNTIL is inclusive, the 6th is excluded.
        assertEquals(
            listOf("2026-10-05T15:00:00Z", "2026-10-07T15:00:00Z", "2026-10-08T15:00:00Z").map(Instant::parse),
            parse(ics).map { it.start })
    }

    @Test fun `all-day event starts at device-local midnight`() {
        val ics = calendar(event("DTSTART;VALUE=DATE:20261007", "DTEND;VALUE=DATE:20261008"))
        val ev = parse(ics).single()
        assertEquals(true, ev.isAllDay)
        assertEquals(LocalDateTime.parse("2026-10-07T00:00"), ev.localStart)
        assertEquals(LocalDateTime.parse("2026-10-08T00:00"), ev.localEnd)
    }
}

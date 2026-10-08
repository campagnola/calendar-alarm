package com.example.calendaralarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStatusTest {
    private val min = 60_000L

    @Test fun `a failure run starts at its first failure and ends at the next success`() {
        val failing = FeedStatus().afterAttempt(0L, null)
            .afterAttempt(15 * min, "HTTP 500")
            .afterAttempt(30 * min, "timeout")
        assertEquals(15 * min, failing.failingSinceMs)
        assertEquals(0L, failing.lastSuccessMs)
        assertEquals("timeout", failing.problem)

        assertEquals(FeedStatus(lastAttemptMs = 45 * min, lastSuccessMs = 45 * min),
            failing.copy(notified = true).afterAttempt(45 * min, null))
    }

    @Test fun `overdue after one hour or two poll intervals, whichever is longer`() {
        val failing = FeedStatus().afterAttempt(0L, "HTTP 500")
        assertFalse(failing.isOverdue(60 * min, pollIntervalMinutes = 15))
        assertTrue(failing.isOverdue(61 * min, pollIntervalMinutes = 15))
        assertFalse(failing.isOverdue(119 * min, pollIntervalMinutes = 60))
        assertTrue(failing.isOverdue(121 * min, pollIntervalMinutes = 60))
        assertFalse(FeedStatus().afterAttempt(0L, null).isOverdue(500 * min, pollIntervalMinutes = 15))
    }
}

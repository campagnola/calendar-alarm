package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId

class PollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                poll(context)
            } finally {
                result.finish()
            }
        }
    }

    private fun poll(context: Context) {
        val prefs = Prefs(context)
        val url = prefs.icsUrl.ifEmpty { return }

        val (icsText, newEtag, newLastModified) = fetchUrl(url, prefs.icsEtag, prefs.icsLastModified)
        if (icsText == null) {
            // 304 Not Modified or network error — either way, nothing new to schedule
            AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
            return
        }
        if (newEtag.isNotEmpty())        prefs.icsEtag = newEtag
        if (newLastModified.isNotEmpty()) prefs.icsLastModified = newLastModified

        val nowDt = LocalDateTime.now()
        val events = IcsParser.parse(icsText, nowDt, nowDt.plusHours(25))

        val advanceMs = prefs.advanceMinutes * 60_000L
        val now = System.currentTimeMillis()

        for (event in events) {
            val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val triggerMs = startMs - advanceMs
            if (triggerMs > now) {
                Log.i("CalAlarm", "PollReceiver scheduling alarm for '${event.summary}' trigger=${triggerMs - now}ms from now")
                AlarmScheduler.scheduleEventAlarm(context, event, triggerMs)
            }
        }

        prefs.lastPollTime = System.currentTimeMillis()

        AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
    }

    private data class FetchResult(val text: String?, val etag: String, val lastModified: String)

    private fun fetchUrl(urlStr: String, etag: String, lastModified: String): FetchResult {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (etag.isNotEmpty())         conn.setRequestProperty("If-None-Match", etag)
            if (lastModified.isNotEmpty()) conn.setRequestProperty("If-Modified-Since", lastModified)

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                conn.disconnect()
                return FetchResult(null, etag, lastModified) // unchanged
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val newEtag        = conn.getHeaderField("ETag")        ?: ""
            val newLastModified = conn.getHeaderField("Last-Modified") ?: ""
            conn.disconnect()
            FetchResult(text, newEtag, newLastModified)
        } catch (_: Exception) {
            FetchResult(null, etag, lastModified)
        }
    }
}

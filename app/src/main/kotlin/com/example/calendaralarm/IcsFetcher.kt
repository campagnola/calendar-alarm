package com.example.calendaralarm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object IcsFetcher {
    private val mutex = Mutex()

    data class Result(val text: String, val cacheTimestamp: Long)

    suspend fun fetch(context: Context): Result? = mutex.withLock {
        val prefs = Prefs(context)
        val url = prefs.icsUrl.ifEmpty { return@withLock null }
        val cacheFile = File(context.cacheDir, "calendar.ics")

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            if (prefs.icsEtag.isNotEmpty())
                conn.setRequestProperty("If-None-Match", prefs.icsEtag)
            if (prefs.icsLastModified.isNotEmpty())
                conn.setRequestProperty("If-Modified-Since", prefs.icsLastModified)

            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Log.i("CalAlarm", "IcsFetcher: 304 Not Modified")
                    conn.disconnect()
                }
                HttpURLConnection.HTTP_OK -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.getHeaderField("ETag")?.let          { if (it.isNotEmpty()) prefs.icsEtag = it }
                    conn.getHeaderField("Last-Modified")?.let { if (it.isNotEmpty()) prefs.icsLastModified = it }
                    conn.disconnect()
                    cacheFile.writeText(text)
                    Log.i("CalAlarm", "IcsFetcher: fetched ${text.length} bytes, cached at ${cacheFile.lastModified()}")
                }
                else -> {
                    Log.w("CalAlarm", "IcsFetcher: unexpected HTTP $code")
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("CalAlarm", "IcsFetcher: network error: ${e.message}")
        }

        if (!cacheFile.exists()) return@withLock null
        Result(cacheFile.readText(), cacheFile.lastModified())
    }
}

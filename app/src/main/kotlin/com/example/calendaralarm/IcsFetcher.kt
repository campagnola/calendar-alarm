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

    /**
     * Returns the ICS content and the timestamp of the cache file.
     *
     * If [forceDownload] is false and the cache is younger than (pollInterval - 1) minutes,
     * the cached file is returned immediately without a network request.
     *
     * Otherwise, a conditional GET is attempted. On 304 the cache is returned as-is;
     * on 200 the cache is overwritten. On network error the cache is returned if it exists.
     */
    suspend fun fetch(context: Context, forceDownload: Boolean = false): Result? = mutex.withLock {
        val prefs = Prefs(context)
        val url = prefs.icsUrl.ifEmpty { return@withLock null }
        val cacheFile = File(context.cacheDir, "calendar.ics")

        if (!forceDownload && cacheFile.exists()) {
            val ageMs    = System.currentTimeMillis() - cacheFile.lastModified()
            val maxAgeMs = (prefs.pollIntervalMinutes - 1).coerceAtLeast(1) * 60_000L
            if (ageMs < maxAgeMs) {
                Log.i("CalAlarm", "IcsFetcher: cache is ${ageMs / 1000}s old (max ${maxAgeMs / 1000}s), skipping download")
                return@withLock Result(cacheFile.readText(), cacheFile.lastModified())
            }
        }

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000

            val sentEtag     = prefs.icsEtag
            val sentLastMod  = prefs.icsLastModified
            if (sentEtag.isNotEmpty())    conn.setRequestProperty("If-None-Match", sentEtag)
            if (sentLastMod.isNotEmpty()) conn.setRequestProperty("If-Modified-Since", sentLastMod)
            Log.i("CalAlarm", "IcsFetcher: GET  If-None-Match=${sentEtag.ifEmpty { "(none)" }}  If-Modified-Since=${sentLastMod.ifEmpty { "(none)" }}")

            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Log.i("CalAlarm", "IcsFetcher: 304 Not Modified")
                    conn.disconnect()
                }
                HttpURLConnection.HTTP_OK -> {
                    val etag         = conn.getHeaderField("ETag")          ?: "(none)"
                    val lastMod      = conn.getHeaderField("Last-Modified")  ?: "(none)"
                    val contentLen   = conn.getHeaderField("Content-Length") ?: "(none)"
                    val cacheControl = conn.getHeaderField("Cache-Control")  ?: "(none)"
                    val expires      = conn.getHeaderField("Expires")        ?: "(none)"
                    Log.i("CalAlarm", "IcsFetcher: 200 OK  ETag=$etag  Last-Modified=$lastMod  Content-Length=$contentLen  Cache-Control=$cacheControl  Expires=$expires")

                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    if (etag    != "(none)") prefs.icsEtag         = etag
                    if (lastMod != "(none)") prefs.icsLastModified = lastMod
                    conn.disconnect()
                    cacheFile.writeText(text)
                    Log.i("CalAlarm", "IcsFetcher: cached ${text.length} chars at ${cacheFile.lastModified()}")
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

package com.example.calendaralarm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object IcsFetcher {
    private val mutex = Mutex()

    data class Result(val text: String, val cacheTimestamp: Long)

    suspend fun fetch(context: Context, forceDownload: Boolean = false): Result? = mutex.withLock {
        val prefs     = Prefs(context)
        val url       = prefs.icsUrl.ifEmpty { return@withLock null }
        val cacheFile = File(context.cacheDir, "calendar.ics")

        // Return cache immediately if it is fresh enough.
        if (!forceDownload && cacheFile.exists()) {
            val ageMs    = System.currentTimeMillis() - cacheFile.lastModified()
            val maxAgeMs = (prefs.pollIntervalMinutes - 1).coerceAtLeast(1) * 60_000L
            if (ageMs < maxAgeMs) {
                Log.i("CalAlarm", "IcsFetcher: cache ${ageMs / 1000}s old (max ${maxAgeMs / 1000}s), using cache")
                return@withLock cacheFile.toResult()
            }
        }

        val hasCondHeaders = prefs.icsEtag.isNotEmpty() || prefs.icsLastModified.isNotEmpty()

        // When the server provides no caching headers, use HEAD to check size cheaply.
        // Skip this on forceDownload so the user always gets a real download.
        if (!forceDownload && !hasCondHeaders && cacheFile.exists()) {
            when (headSizeChanged(url, prefs, cacheFile)) {
                false -> {
                    // Content-Length matches cached file — assume unchanged.
                    Log.i("CalAlarm", "IcsFetcher: HEAD size matches cache, skipping GET")
                    return@withLock cacheFile.toResult()
                }
                true  -> Log.i("CalAlarm", "IcsFetcher: HEAD size differs, doing full GET")
                null  -> Log.i("CalAlarm", "IcsFetcher: HEAD inconclusive, doing full GET")
            }
        }

        doGet(url, prefs, cacheFile)

        if (!cacheFile.exists()) return@withLock null
        cacheFile.toResult()
    }

    // Returns true if size changed, false if same, null if undetermined.
    private fun headSizeChanged(url: String, prefs: Prefs, cacheFile: File): Boolean? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod  = "HEAD"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        return try {
            val code       = conn.responseCode
            val etag       = conn.getHeaderField("ETag")
            val lastMod    = conn.getHeaderField("Last-Modified")
            val serverSize = conn.getHeaderField("Content-Length")?.toLongOrNull()
            Log.i("CalAlarm", "IcsFetcher: HEAD $code  Content-Length=${serverSize ?: "(none)"}  ETag=${etag ?: "(none)"}  Last-Modified=${lastMod ?: "(none)"}")
            conn.disconnect()

            // If the server started advertising ETag/LastMod, store for next time.
            if (!etag.isNullOrEmpty())    prefs.icsEtag         = etag
            if (!lastMod.isNullOrEmpty()) prefs.icsLastModified = lastMod

            if (serverSize == null) null else serverSize != cacheFile.length()
        } catch (e: Exception) {
            Log.w("CalAlarm", "IcsFetcher: HEAD failed: ${e.message}")
            conn.runCatching { disconnect() }
            null
        }
    }

    private fun doGet(url: String, prefs: Prefs, cacheFile: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000

        val sentEtag   = prefs.icsEtag
        val sentLastMod = prefs.icsLastModified
        if (sentEtag.isNotEmpty())    conn.setRequestProperty("If-None-Match",     sentEtag)
        if (sentLastMod.isNotEmpty()) conn.setRequestProperty("If-Modified-Since", sentLastMod)
        Log.i("CalAlarm", "IcsFetcher: GET  If-None-Match=${sentEtag.ifEmpty { "(none)" }}  If-Modified-Since=${sentLastMod.ifEmpty { "(none)" }}")

        try {
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
                    Log.i("CalAlarm", "IcsFetcher: 200 OK  ETag=$etag  Last-Modified=$lastMod  " +
                        "Content-Length=$contentLen  Cache-Control=$cacheControl  Expires=$expires")

                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()

                    if (etag    != "(none)") prefs.icsEtag         = etag
                    if (lastMod != "(none)") prefs.icsLastModified = lastMod

                    val newHash = md5(bytes)
                    if (cacheFile.exists() && newHash == prefs.icsContentHash) {
                        Log.i("CalAlarm", "IcsFetcher: content hash unchanged, skipping cache write")
                    } else {
                        cacheFile.writeBytes(bytes)
                        prefs.icsContentHash = newHash
                        Log.i("CalAlarm", "IcsFetcher: cached ${bytes.size} bytes at ${cacheFile.lastModified()}")
                    }
                }
                else -> {
                    Log.w("CalAlarm", "IcsFetcher: unexpected HTTP $code")
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("CalAlarm", "IcsFetcher: GET failed: ${e.message}")
            conn.runCatching { disconnect() }
        }
    }

    private fun File.toResult() = Result(readText(Charsets.UTF_8), lastModified())

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

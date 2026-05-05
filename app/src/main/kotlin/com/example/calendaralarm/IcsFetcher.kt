package com.example.calendaralarm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object IcsFetcher {

    /** What callers (PollReceiver, MainActivity) receive per source. */
    data class FetchedSource(val url: String, val text: String, val cacheTimestamp: Long)

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(id: String) = mutexes.getOrPut(id) { Mutex() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch a single URL. Updates per-URL status in Prefs so the settings page
     * can display it. Returns null only when no data is available at all
     * (download failed AND no cache file exists).
     */
    suspend fun fetchOne(context: Context, url: String, forceDownload: Boolean = false): FetchedSource? {
        val prefs = Prefs(context)
        val id    = prefs.urlId(url)
        return mutexFor(id).withLock { fetchLocked(context, url, id, prefs, forceDownload) }
    }

    /**
     * Fetch all configured sources and return the results. Sources that fail
     * with no existing cache are silently omitted. Safe to call from multiple
     * coroutines simultaneously — each URL has its own mutex.
     */
    suspend fun fetchAll(context: Context, forceDownload: Boolean = false): List<FetchedSource> {
        val prefs   = Prefs(context)
        val sources = prefs.icsSources
        if (sources.isEmpty()) return emptyList()
        return sources.mapNotNull { url -> fetchOne(context, url, forceDownload) }
    }

    // ── Core fetch logic ──────────────────────────────────────────────────────

    private fun fetchLocked(
        context: Context, url: String, id: String, prefs: Prefs, forceDownload: Boolean
    ): FetchedSource? {
        val cacheFile = File(context.cacheDir, "calendar_$id.ics")

        // Return cache if it is fresh enough and no forced download requested.
        if (!forceDownload && cacheFile.exists()) {
            val ageMs    = System.currentTimeMillis() - cacheFile.lastModified()
            val maxAgeMs = (prefs.pollIntervalMinutes - 1).coerceAtLeast(1) * 60_000L
            if (ageMs < maxAgeMs) {
                Log.i("CalAlarm", "[$id] cache ${ageMs / 1000}s old (max ${maxAgeMs / 1000}s), using cache")
                return cacheFile.toResult(url)
            }
        }

        val hasCondHeaders = prefs.icsEtag(id).isNotEmpty() || prefs.icsLastModified(id).isNotEmpty()

        // When the server provides no caching headers, use HEAD to check size cheaply.
        if (!forceDownload && !hasCondHeaders && cacheFile.exists()) {
            when (headSizeChanged(url, id, prefs, cacheFile)) {
                false -> {
                    Log.i("CalAlarm", "[$id] HEAD size matches cache, skipping GET")
                    return cacheFile.toResult(url)
                }
                true  -> Log.i("CalAlarm", "[$id] HEAD size differs, doing full GET")
                null  -> Log.i("CalAlarm", "[$id] HEAD inconclusive, doing full GET")
            }
        }

        val fetchOk = doGet(url, id, prefs, cacheFile)

        if (!fetchOk) return null
        if (!cacheFile.exists()) return null
        return cacheFile.toResult(url)
    }

    // Returns true=size changed, false=same, null=undetermined.
    private fun headSizeChanged(url: String, id: String, prefs: Prefs, cacheFile: File): Boolean? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod  = "HEAD"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        return try {
            val code       = conn.responseCode
            val etag       = conn.getHeaderField("ETag")
            val lastMod    = conn.getHeaderField("Last-Modified")
            val serverSize = conn.getHeaderField("Content-Length")?.toLongOrNull()
            Log.i("CalAlarm", "[$id] HEAD $code  Content-Length=${serverSize ?: "(none)"}  ETag=${etag ?: "(none)"}  Last-Modified=${lastMod ?: "(none)"}")
            conn.disconnect()
            if (!etag.isNullOrEmpty())    prefs.setIcsEtag(id, etag)
            if (!lastMod.isNullOrEmpty()) prefs.setIcsLastModified(id, lastMod)
            if (serverSize == null) null else serverSize != cacheFile.length()
        } catch (e: Exception) {
            Log.w("CalAlarm", "[$id] HEAD failed: ${e.message}")
            conn.runCatching { disconnect() }
            null
        }
    }

    /** Returns true if usable ICS data is now in [cacheFile], false on any error. */
    private fun doGet(url: String, id: String, prefs: Prefs, cacheFile: File): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000

        val sentEtag    = prefs.icsEtag(id)
        val sentLastMod = prefs.icsLastModified(id)
        if (sentEtag.isNotEmpty())    conn.setRequestProperty("If-None-Match",     sentEtag)
        if (sentLastMod.isNotEmpty()) conn.setRequestProperty("If-Modified-Since", sentLastMod)
        Log.i("CalAlarm", "[$id] GET  If-None-Match=${sentEtag.ifEmpty { "(none)" }}  If-Modified-Since=${sentLastMod.ifEmpty { "(none)" }}")

        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Log.i("CalAlarm", "[$id] 304 Not Modified")
                    conn.disconnect()
                    prefs.setIcsLastFetchMs(id, System.currentTimeMillis())
                    prefs.setIcsLastFetchStatus(id, "OK")
                    return true
                }
                HttpURLConnection.HTTP_OK -> {
                    val etag         = conn.getHeaderField("ETag")          ?: "(none)"
                    val lastMod      = conn.getHeaderField("Last-Modified")  ?: "(none)"
                    val contentLen   = conn.getHeaderField("Content-Length") ?: "(none)"
                    val contentType  = conn.getHeaderField("Content-Type")   ?: "(none)"
                    val cacheControl = conn.getHeaderField("Cache-Control")  ?: "(none)"
                    val expires      = conn.getHeaderField("Expires")        ?: "(none)"
                    Log.i("CalAlarm", "[$id] 200 OK  ETag=$etag  Last-Modified=$lastMod  " +
                        "Content-Length=$contentLen  Content-Type=$contentType  Cache-Control=$cacheControl  Expires=$expires")

                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()

                    val bodyStart = bytes.toString(Charsets.UTF_8).trimStart()
                    if (!bodyStart.startsWith("BEGIN:VCALENDAR")) {
                        Log.w("CalAlarm", "[$id] 200 OK but response is not a valid ICS file " +
                            "(Content-Type=$contentType, ${bytes.size} bytes)")
                        prefs.setIcsLastFetchMs(id, System.currentTimeMillis())
                        prefs.setIcsLastFetchStatus(id, "Not an ICS file (Content-Type: $contentType)")
                        return false
                    }

                    if (etag    != "(none)") prefs.setIcsEtag(id, etag)
                    if (lastMod != "(none)") prefs.setIcsLastModified(id, lastMod)

                    val newHash = md5(bytes)
                    if (cacheFile.exists() && newHash == prefs.icsContentHash(id)) {
                        Log.i("CalAlarm", "[$id] content hash unchanged, skipping cache write")
                    } else {
                        cacheFile.writeBytes(bytes)
                        prefs.setIcsContentHash(id, newHash)
                        Log.i("CalAlarm", "[$id] cached ${bytes.size} bytes at ${cacheFile.lastModified()}")
                    }
                    if (prefs.icsSourceName(id).isEmpty()) {
                        IcsParser.calendarName(bytes.toString(Charsets.UTF_8))
                            ?.let { prefs.setIcsSourceName(id, it) }
                    }
                    prefs.setIcsLastFetchMs(id, System.currentTimeMillis())
                    prefs.setIcsLastFetchStatus(id, "OK")
                    return true
                }
                else -> {
                    Log.w("CalAlarm", "[$id] unexpected HTTP $code")
                    conn.disconnect()
                    prefs.setIcsLastFetchMs(id, System.currentTimeMillis())
                    prefs.setIcsLastFetchStatus(id, "HTTP $code")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.w("CalAlarm", "[$id] GET failed: ${e.message}")
            conn.runCatching { disconnect() }
            prefs.setIcsLastFetchMs(id, System.currentTimeMillis())
            prefs.setIcsLastFetchStatus(id, e.message ?: "Network error")
            return false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun File.toResult(url: String) = FetchedSource(url, readText(Charsets.UTF_8), lastModified())

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

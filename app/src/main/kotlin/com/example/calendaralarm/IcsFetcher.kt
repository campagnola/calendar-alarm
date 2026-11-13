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

    /**
     * What callers receive per source: the newest available [text], and [downloadError] when this
     * fetch's download failed. [text] is null only together with a [downloadError].
     */
    data class FetchedSource(val url: String, val text: String?, val downloadError: String?)

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(id: String) = mutexes.getOrPut(id) { Mutex() }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Download a single URL, falling back to the cached copy when the download fails. */
    suspend fun fetchOne(context: Context, url: String): FetchedSource {
        val prefs = Prefs(context)
        val id    = prefs.urlId(url)
        return mutexFor(id).withLock {
            val cacheFile = cacheFile(context, id)
            val error = doGet(url, id, prefs, cacheFile)
            if (error != null) AppLog.w("IcsFetcher [$id]: fetch failed ($error), using cached copy if any")
            cacheFile.toResult(url, error)
        }
    }

    /** The most recently downloaded text for [url], or null if it has never been downloaded. */
    fun cached(context: Context, url: String): String? =
        cacheFile(context, Prefs(context).urlId(url)).textOrNull()

    // ── Core fetch logic ──────────────────────────────────────────────────────

    private fun cacheFile(context: Context, id: String) = File(context.cacheDir, "calendar_$id.ics")

    /** Downloads into [cacheFile]; returns null on success or a description of the failure. */
    private fun doGet(url: String, id: String, prefs: Prefs, cacheFile: File): String? {
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
                    return if (cacheFile.exists()) null else "Server reported no change, but there is no cached copy"
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
                        return "Not an ICS file (Content-Type: $contentType)"
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
                    return null
                }
                else -> {
                    Log.w("CalAlarm", "[$id] unexpected HTTP $code")
                    conn.disconnect()
                    return "HTTP $code"
                }
            }
        } catch (e: Exception) {
            Log.w("CalAlarm", "[$id] GET failed: ${e.message}")
            conn.runCatching { disconnect() }
            return e.toString()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun File.textOrNull() = takeIf { it.exists() }?.readText(Charsets.UTF_8)

    private fun File.toResult(url: String, downloadError: String?) = FetchedSource(url, textOrNull(), downloadError)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

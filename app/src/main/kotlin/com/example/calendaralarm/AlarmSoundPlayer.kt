package com.example.calendaralarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.IOException

/**
 * Plays the [AlarmSound] for [AlarmService]. The volume is a function of the time since ringing
 * started, so a restarted service resumes the fade where it was. Main thread only.
 */
class AlarmSoundPlayer(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { apply() }
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(ATTRIBUTES).build()
    // Keeps the CPU awake so the delayed start and the fade steps run on time with the screen off.
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalendarAlarm:sound")
        .apply { setReferenceCounted(false) }

    private var ringStartMs: Long? = null
    private var player: MediaPlayer? = null
    private var unplayable = false

    /** Sounds for alarms ringing since [ringStartMs]; null silences it. */
    fun update(ringStartMs: Long?) {
        this.ringStartMs = ringStartMs
        apply()
    }

    private fun apply() {
        handler.removeCallbacks(tick)
        val sound = Prefs(context).alarmSound
        val elapsedMs = ringStartMs?.let { AppClock.now() - it }
        val volume = elapsedMs?.let(sound::volumeAt)
        if (volume == null) stop() else (player ?: start(checkNotNull(sound.uri)))?.setVolume(volume, volume)

        val waitMs = elapsedMs?.let { sound.nextChangeAt(it)?.minus(it) }
        if (waitMs == null) {
            if (wakeLock.isHeld) wakeLock.release()
        } else {
            wakeLock.acquire(waitMs + WAKE_MARGIN_MS)
            handler.postDelayed(tick, waitMs)
        }
    }

    private fun start(uri: String): MediaPlayer? {
        if (unplayable) return null
        val started = open(Uri.parse(uri))
            ?: open(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                ?.also { AppLog.w("AlarmSoundPlayer: playing the default alarm sound instead of $uri") }
        if (started == null) {
            AppLog.e("AlarmSoundPlayer: no alarm sound can be played")
            unplayable = true
            return null
        }
        AppLog.i("AlarmSoundPlayer: started")
        audioManager.requestAudioFocus(focusRequest)
        player = started
        return started
    }

    /** A started, looping player for [uri], or null if it cannot be played. */
    private fun open(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        fun failed(e: Exception): MediaPlayer? {
            AppLog.w("AlarmSoundPlayer: cannot play $uri: $e")
            mp.release()
            return null
        }
        return try {
            mp.setAudioAttributes(ATTRIBUTES)
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.prepare()
            mp.start()
            mp
        } catch (e: IOException) { failed(e) }
          catch (e: IllegalArgumentException) { failed(e) }
          catch (e: SecurityException) { failed(e) }
    }

    private fun stop() {
        val mp = player ?: return
        AppLog.i("AlarmSoundPlayer: stopped")
        player = null
        mp.stop()
        mp.release()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    companion object {
        private const val WAKE_MARGIN_MS = 10_000L
        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

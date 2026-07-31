package com.example.calendaralarm

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime

object AppLog {
    private const val TAG = "CalAlarm"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) logFile = File(context.applicationContext.filesDir, "calarm.log")
    }

    fun i(msg: String) { Log.i(TAG, msg); append("I", msg) }
    fun w(msg: String) { Log.w(TAG, msg); append("W", msg) }
    fun e(msg: String) { Log.e(TAG, msg); append("E", msg) }

    private fun append(level: String, msg: String) {
        val f = logFile ?: return
        val line = "${LocalDateTime.now()}  $level  $msg\n"
        try {
            synchronized(this) {
                f.appendText(line)
                if (f.length() > MAX_BYTES) trim(f)
            }
        } catch (_: Exception) {}
    }

    private fun trim(f: File) {
        val lines = f.readLines()
        f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
    }
}

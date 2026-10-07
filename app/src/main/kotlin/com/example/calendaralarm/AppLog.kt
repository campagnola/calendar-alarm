package com.example.calendaralarm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime

object AppLog {
    private const val TAG = "CalAlarm"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "calarm.log")
    }

    fun i(msg: String) { Log.i(TAG, msg); append("I", msg) }
    fun w(msg: String) { Log.w(TAG, msg); append("W", msg) }
    fun e(msg: String) { Log.e(TAG, msg); append("E", msg) }

    private fun append(level: String, msg: String) {
        val f = logFile ?: return
        val line = "${OffsetDateTime.now()}  $level  $msg\n"
        try {
            synchronized(this) {
                f.appendText(line)
                if (f.length() > MAX_BYTES) trim(f)
            }
        } catch (e: IOException) {
            // A file-log failure must not crash the alarm path; surface it in logcat instead.
            Log.e(TAG, "AppLog: write to ${f.path} failed", e)
        }
    }

    private fun trim(f: File) {
        val lines = f.readLines()
        f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
    }
}

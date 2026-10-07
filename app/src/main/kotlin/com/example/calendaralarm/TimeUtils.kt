package com.example.calendaralarm

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun timeFormatter(context: Context): DateTimeFormatter =
    if (DateFormat.is24HourFormat(context))
        DateTimeFormatter.ofPattern("HH:mm")
    else
        DateTimeFormatter.ofPattern("h:mm a")

fun formatTime(context: Context, epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormatter(context))

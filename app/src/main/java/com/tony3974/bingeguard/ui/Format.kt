package com.tony3974.bingeguard.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun startOfTodayMs(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

fun startOfTomorrowMs(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

/** "45 s", "12 min", "1 h 05 min". [roundToMinutes] drops the seconds form. */
fun formatDuration(ms: Long, roundToMinutes: Boolean = false): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    if (totalSec < 60 && !roundToMinutes) return "$totalSec s"
    val minutes = totalSec / 60
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
}

/** "4:07", for countdowns. */
fun formatClock(ms: Long): String {
    val totalSec = ((ms + 999) / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")

/** "14:32" or "2:32 PM", following the phone's locale. */
fun formatTime(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun formatDate(ms: Long): String = dateFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

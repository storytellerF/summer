package com.storytellerf.summer.data.recognition

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun parseLocalDateTime(text: String, timeZone: TimeZone = TimeZone.getDefault()): Long? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}").matches(text)) return null
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
        isLenient = false
        this.timeZone = timeZone
    }
    val position = ParsePosition(0)
    return parser.parse(text, position)?.time?.takeIf { position.index == text.length && it > 0 }
}

fun formatLocalDateTime(timestamp: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { this.timeZone = timeZone }
        .format(java.util.Date(timestamp))

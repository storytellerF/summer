package com.storytellerf.summer.data.recognition

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun parseLocalDateTime(text: String, timeZone: TimeZone = TimeZone.getDefault()): Long? {
    if (!Regex("\\d{4}-\\d{1,2}-\\d{1,2}T\\d{1,2}:\\d{1,2}:\\d{1,2}").matches(text)) return null
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

internal fun parseScreenshotDate(text: String, timeZone: TimeZone = TimeZone.getDefault()): Long? {
    val copied = text.trim()
    if (Regex("今天|昨天|today|yesterday", RegexOption.IGNORE_CASE).containsMatchIn(copied)) return null
    val normalized = copied.replace('年', '-').replace('月', '-').replace("日", " ")
        .replace('/', '-').replace('.', '-').trim()
    val numeric = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T]+(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?)?").matchEntire(normalized)
    if (numeric != null) {
        val parts = numeric.groupValues
        return parseLocalDateTime("${parts[1]}-${parts[2]}-${parts[3]}T${parts[4].ifEmpty { "00" }}:${parts[5].ifEmpty { "00" }}:${parts[6].ifEmpty { "00" }}", timeZone)
    }
    if (!Regex("\\b\\d{4}\\b").containsMatchIn(copied)) return null
    for (pattern in listOf("MMM d, yyyy HH:mm:ss", "MMM d, yyyy HH:mm", "MMM d, yyyy",
        "MMMM d, yyyy HH:mm:ss", "MMMM d, yyyy HH:mm", "MMMM d, yyyy")) {
        val parser = SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false; this.timeZone = timeZone }
        val position = ParsePosition(0)
        val parsed = parser.parse(copied, position)
        if (parsed != null && position.index == copied.length && parsed.time > 0) return parsed.time
    }
    return null
}

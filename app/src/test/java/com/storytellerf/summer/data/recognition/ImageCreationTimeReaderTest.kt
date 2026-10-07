package com.storytellerf.summer.data.recognition

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ImageCreationTimeReaderTest {
    @Test fun exifCreationDateUsesOffsetAndSubseconds_orLocalZoneIfNoOffset() {
        val utc = TimeZone.getTimeZone("UTC")
        val expected = requireNotNull(parseLocalDateTime("2026-10-07T04:30:00", utc))
        assertEquals(expected + 123, parseExifCreationTime("2026:10:07 12:30:00", "+08:00", "1234", utc))
        assertEquals(expected + 100, parseExifCreationTime("2026:10:07 12:30:00", null, "1", TimeZone.getTimeZone("Asia/Shanghai")))
    }

    @Test fun absentMalformedDatesAndOffsetsAreNotTreatedAsCreationTimes() {
        assertNull(parseExifCreationTime(null, null, null))
        assertNull(parseExifCreationTime("2026:02:30 12:30:00", null, null))
        assertNull(parseExifCreationTime("2026:10:07 12:30:00", "+25:00", null))
        assertNull(parseExifCreationTime("2026:10:07 12:30:00 trailing", null, null))
    }
}

package com.storytellerf.summer

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.recognition.AndroidImageCreationTimeReader
import com.storytellerf.summer.data.recognition.parseLocalDateTime
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ImageCreationTimeReaderTest {
    @Test fun originalFileExifDateIsReadWithOffsetAndSubseconds() = runTest {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File.createTempFile("image-creation-", ".jpg", context.cacheDir)
            try {
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
                finally { bitmap.recycle() }
                ExifInterface(file.path).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 12:34:56")
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+08:00")
                    setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "123")
                    saveAttributes()
                }
                val expected = requireNotNull(parseLocalDateTime("2020-01-02T04:34:56", TimeZone.getTimeZone("UTC"))) + 123
                assertEquals(expected, AndroidImageCreationTimeReader(context).readCreationTime(Uri.fromFile(file).toString()))
            } finally { file.delete() }
        }
    }

    @Test fun modificationTimeIsNotMistakenForCreationTime_whenPngHasNoMetadata() = runTest {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File.createTempFile("image-creation-", ".png", context.cacheDir)
            try {
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                assertTrue(file.setLastModified(1700000000000L))
                assertNull(AndroidImageCreationTimeReader(context).readCreationTime(Uri.fromFile(file).toString()))
            } finally { file.delete() }
        }
    }
}

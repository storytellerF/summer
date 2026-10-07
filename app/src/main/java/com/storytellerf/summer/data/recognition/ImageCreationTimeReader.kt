package com.storytellerf.summer.data.recognition

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import java.util.TimeZone

fun interface ImageCreationTimeReader {
    suspend fun readCreationTime(imageReference: String): Long?
}

/** Reads the original selected image on the caller's IO dispatcher, before JPEG re-encoding. */
class AndroidImageCreationTimeReader(context: Context) : ImageCreationTimeReader {
    private val context = context.applicationContext

    override suspend fun readCreationTime(imageReference: String): Long? {
        val uri = Uri.parse(imageReference)
        queryDateTaken(uri)?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) {
            optionalMetadata { MediaStore.getMediaUri(context, uri) }?.let { mediaUri ->
                queryDateTaken(mediaUri)?.let { return it }
            }
        }
        return optionalMetadata {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                parseExifCreationTime(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                ) ?: parseExifCreationTime(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
                    exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED),
                )
            }
        }
    }

    private fun queryDateTaken(uri: Uri): Long? = optionalMetadata {
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column).takeIf { it > 0 } else null
        }
    }
}

private inline fun <T> optionalMetadata(read: () -> T?): T? = try {
    read()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    // Providers may not expose metadata, or an image may contain no EXIF creation date.
    null
}

internal fun parseExifCreationTime(
    date: String?, offset: String?, subseconds: String?, timeZone: TimeZone = TimeZone.getDefault(),
): Long? {
    if (date == null || !Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(date)) return null
    if (offset != null && !Regex("[+-](?:0\\d|1[0-3]):[0-5]\\d|[+-]14:00").matches(offset)) return null
    val zone = offset?.let { TimeZone.getTimeZone("GMT$it") } ?: timeZone
    val local = date.replaceRange(10, 11, "T").replaceRange(7, 8, "-").replaceRange(4, 5, "-")
    val timestamp = parseLocalDateTime(local, zone) ?: return null
    val millis = subseconds?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
    return timestamp + millis
}

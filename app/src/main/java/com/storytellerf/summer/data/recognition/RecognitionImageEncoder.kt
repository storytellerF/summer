package com.storytellerf.summer.data.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Bounds decoding prevents full-resolution allocations before resizing and encoding. */
class RecognitionImageEncoder(private val context: Context) {
    fun encode(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open image URI")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not a valid image" }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalArgumentException("Cannot decode selected image")

        var working = decoded.scaledToFit(MAX_IMAGE_DIMENSION)
        if (working !== decoded) decoded.recycle()
        if (working.hasAlpha()) {
            val flattened = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
            Canvas(flattened).apply {
                drawColor(Color.WHITE)
                drawBitmap(working, 0f, 0f, null)
            }
            working.recycle()
            working = flattened
        }

        return try {
            var quality = INITIAL_JPEG_QUALITY
            var bytes = working.compressAsJpeg(quality)
            while (bytes.size > MAX_ENCODED_IMAGE_BYTES && quality > MIN_JPEG_QUALITY) {
                quality -= JPEG_QUALITY_STEP
                bytes = working.compressAsJpeg(quality)
            }
            require(bytes.size <= MAX_ENCODED_IMAGE_BYTES) {
                "Image is too detailed to send safely. Please crop it and try again."
            }
            bytes
        } finally {
            working.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > MAX_IMAGE_DIMENSION * 2 ||
            height / sampleSize > MAX_IMAGE_DIMENSION * 2
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun Bitmap.scaledToFit(maxDimension: Int): Bitmap {
        val largestDimension = maxOf(width, height)
        if (largestDimension <= maxDimension) return this
        val scale = maxDimension.toFloat() / largestDimension
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun Bitmap.compressAsJpeg(quality: Int): ByteArray {
        return ByteArrayOutputStream().use { output ->
            check(compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Failed to encode image" }
            output.toByteArray()
        }
    }

    companion object {
        private const val MAX_IMAGE_DIMENSION = 1600
        private const val MAX_ENCODED_IMAGE_BYTES = 500_000
        private const val INITIAL_JPEG_QUALITY = 85
        private const val MIN_JPEG_QUALITY = 45
        private const val JPEG_QUALITY_STEP = 10
    }
}

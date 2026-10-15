package com.storytellerf.summer.data.recognition

import java.io.File

fun interface RecognitionImageStore {
    /** Stores the already compressed JPEG and returns a path relative to the app files directory. */
    fun save(jpeg: ByteArray): String
}

class FileRecognitionImageStore(private val filesDir: File) : RecognitionImageStore {
    override fun save(jpeg: ByteArray): String {
        require(jpeg.isNotEmpty()) { "Cannot save an empty recognition image" }
        val directory = File(filesDir, "recognition-images")
        if (!directory.isDirectory) directory.mkdirs()
        check(directory.isDirectory) { "Cannot create recognition image directory" }
        val target = File(directory, "${imageHash(jpeg)}.jpg")
        if (!target.isFile) {
            val temporary = File.createTempFile("image-", ".tmp", directory)
            try {
                temporary.outputStream().use { it.write(jpeg) }
                check(temporary.renameTo(target) || target.isFile) { "Cannot save recognition image" }
            } finally {
                temporary.delete()
            }
        }
        return target.relativeTo(filesDir).invariantSeparatorsPath
    }
}

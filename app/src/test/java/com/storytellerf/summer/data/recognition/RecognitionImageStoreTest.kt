package com.storytellerf.summer.data.recognition

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionImageStoreTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun savedImageSurvivesTheInput_andIdenticalBytesShareOneRelativePath() {
        val store = FileRecognitionImageStore(files.root)
        val jpeg = byteArrayOf(-1, -40, 1, 2, -1, -39)
        val path = store.save(jpeg)
        val file = File(files.root, path)
        assertFalse(File(path).isAbsolute)
        assertArrayEquals(jpeg, file.readBytes())
        assertEquals(path, store.save(jpeg.copyOf()))
        assertEquals(1, file.parentFile!!.listFiles()!!.size)
        assertNotEquals(path, store.save(jpeg + byteArrayOf(3)))
        assertEquals(2, file.parentFile!!.listFiles()!!.size)
    }

    @Test fun emptyInputLeavesNoPartialImage() {
        val store = FileRecognitionImageStore(files.root)
        assertThrows(IllegalArgumentException::class.java) { store.save(byteArrayOf()) }
        assertTrue(files.root.listFiles()!!.isEmpty())
    }
}

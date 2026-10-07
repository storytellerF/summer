package com.storytellerf.summer.data.recognition

import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.llmd.LlmdTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConfiguredImageAnalyzerTest {
    @Test fun switchingBackend_routesImageToSelectedConnectionAndPreservesLlmdTarget() = runTest {
        val settings = RecognitionTestSettings()
        val llmd = object : FinanceImageAnalyzer {
            var selected: LlmdTarget? = null
            override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget): Result<Double> {
                selected = target
                return Result.success(12.0)
            }
        }
        var uploads = 0
        val connection = KoogConnection("https://openrouter.ai/api/v1", "test-vision:free", "test-key")
        val analyzer = ConfiguredImageAnalyzer(settings, llmd, { byteArrayOf(1, 2) },
            RemoteImageRecognizer { backend, actual, jpeg ->
                uploads++
                assertEquals(RecognitionBackend.OpenRouter, backend)
                assertEquals(connection, actual)
                assertArrayEquals(byteArrayOf(1, 2), jpeg)
                -245.70
            }, imageStore = RecognitionImageStore { "recognition-images/test.jpg" })
        assertEquals(12.0, analyzer.extractBalanceFromImage("test-image", LlmdTarget.Alpha).getOrThrow(), 0.0)
        assertEquals(LlmdTarget.Alpha, llmd.selected)
        assertEquals(0, uploads)
        settings.save(RecognitionBackend.OpenRouter, connection)
        assertEquals(-245.70, analyzer.extractBalanceFromImage("test-image", LlmdTarget.Debug).getOrThrow(), 0.0)
        assertEquals(1, uploads)
        settings.save(RecognitionBackend.Llmd, KoogConnection())
        assertEquals(12.0, analyzer.extractBalanceFromImage("test-image", LlmdTarget.Debug).getOrThrow(), 0.0)
        assertEquals(LlmdTarget.Debug, llmd.selected)
        assertEquals(connection, settings.config.value.connectionFor(RecognitionBackend.OpenRouter))
    }

    @Test fun invalidConfiguration_doesNotReadOrUploadImage() = runTest {
        val settings = RecognitionTestSettings(RecognitionConfig(RecognitionBackend.OpenRouter))
        val analyzer = ConfiguredImageAnalyzer(settings, unusedLlmd(), { error("Image must not be read") },
            RemoteImageRecognizer { _, _, _ -> error("Image must not be sent") }, imageStore = RecognitionImageStore { error("Must not save") })
        assertTrue(analyzer.extractBalanceFromImage("test", LlmdTarget.Release).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun remoteCancellation_isPropagated() = runTest {
        val settings = RecognitionTestSettings()
        settings.save(RecognitionBackend.OpenRouter, KoogConnection("https://example.com/v1", "vision", "test-key"))
        val analyzer = ConfiguredImageAnalyzer(settings, unusedLlmd(), { byteArrayOf(1) },
            RemoteImageRecognizer { _, _, _ -> throw CancellationException("cancelled") }, imageStore = RecognitionImageStore { error("Must not save") })
        try {
            analyzer.extractBalanceFromImage("test", LlmdTarget.Release)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun balanceAndTransactionRecognitionSaveTheSameCompressedImage_andReturnPaths() = runTest {
        val settings = RecognitionTestSettings()
        settings.save(RecognitionBackend.OpenRouter, KoogConnection("https://example.com/v1", "vision", "test-key"))
        val jpeg = byteArrayOf(1, 2, 3)
        var saves = 0
        val analyzer = ConfiguredImageAnalyzer(settings, unusedLlmd(), { jpeg }, object : RemoteImageRecognizer {
            override suspend fun recognize(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray) = 12.5
            override suspend fun recognizeTransactions(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray) =
                listOf(RecognizedTransaction(1_790_000_000_000, -2.5, "Purchase", "TX-001"))
        }, imageStore = RecognitionImageStore { bytes ->
            assertArrayEquals(jpeg, bytes)
            saves++
            "recognition-images/shared.jpg"
        })
        val balance = analyzer.extractBalanceWithImage("image").getOrThrow()
        val transactions = analyzer.extractTransactionsFromImage("image").getOrThrow()
        assertEquals("recognition-images/shared.jpg", balance.imagePath)
        assertEquals(balance.imagePath, transactions.imagePath)
        assertEquals(imageHash(jpeg), transactions.imageHash)
        assertEquals(2, saves)
    }

    private fun unusedLlmd() = object : FinanceImageAnalyzer {
        override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget): Result<Double> =
            error("LLMD must not be used")
    }
}

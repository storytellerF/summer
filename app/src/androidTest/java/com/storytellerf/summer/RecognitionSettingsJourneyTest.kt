package com.storytellerf.summer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.recognition.DataStoreRecognitionSettings
import com.storytellerf.summer.data.recognition.KoogConnection
import com.storytellerf.summer.data.recognition.RecognitionBackend
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecognitionSettingsJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun configureOpenRouter_switchToLlmdAndReload_preservesEncryptedConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = DataStoreRecognitionSettings(context)
        val previous = runBlocking(Dispatchers.IO) { settings.config.first() }
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Image recognition").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Provider: ${previous.backend.displayName}") and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Provider: ${previous.backend.displayName}").performScrollTo().performClick()
            compose.onNodeWithText("OpenRouter", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Image model").performScrollTo().performTextReplacement("test-vision:free")
            compose.onNodeWithText("API key").performScrollTo().performTextReplacement(TEST_KEY)
            compose.onNodeWithText("Save recognition settings").performScrollTo().performClick()
            waitForSave()
            compose.onNodeWithText("Recognition settings saved").assertIsDisplayed()

            val saved = runBlocking(Dispatchers.IO) { settings.config.first() }
            assertEquals(RecognitionBackend.OpenRouter, saved.backend)
            assertTrue(saved.connectionFor().apiKey == TEST_KEY)
            val persisted = runBlocking(Dispatchers.IO) {
                File(context.noBackupFilesDir, "recognition.preferences_pb").readBytes()
            }
            assertFalse("API key must never be stored in plaintext", persisted.toString(Charsets.UTF_8).contains(TEST_KEY))

            compose.onNodeWithText("Provider: OpenRouter").performScrollTo().performClick()
            compose.onNodeWithText("LLMD", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Save recognition settings").performScrollTo().performClick()
            waitForSave()
            compose.onNodeWithText("Recognition settings saved").assertIsDisplayed()
            compose.onNodeWithText("LLMD build").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Image recognition").performClick()
            compose.onNodeWithText("Provider: LLMD").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Provider: LLMD").performClick()
            compose.onNodeWithText("OpenRouter", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Image model").performScrollTo().assertIsDisplayed()
            val reloaded = runBlocking(Dispatchers.IO) { DataStoreRecognitionSettings(context).config.first() }
            assertEquals("test-vision:free", reloaded.connectionFor(RecognitionBackend.OpenRouter).model)
            assertTrue(reloaded.connectionFor(RecognitionBackend.OpenRouter).apiKey == TEST_KEY)
        } finally {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val original = previous.connectionFor(RecognitionBackend.OpenRouter)
                    settings.clearConnection(RecognitionBackend.OpenRouter)
                    if (original.apiKey.isNotBlank()) settings.save(RecognitionBackend.OpenRouter, original)
                    settings.save(previous.backend, previous.connectionFor())
                }
            }
        }
    }

    private fun waitForSave() = compose.waitUntil(10_000) {
        compose.onAllNodesWithText("Recognition settings saved").fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val TEST_KEY = "instrumentation-placeholder-key"
    }
}

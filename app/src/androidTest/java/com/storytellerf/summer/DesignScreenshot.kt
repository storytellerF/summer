package com.storytellerf.summer

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import java.io.File

/** Optional evidence from synthetic UI journeys; never capture a user's installed app data. */
internal fun saveDesignScreenshot(compose: ComposeTestRule, name: String) {
    if (InstrumentationRegistry.getArguments().getString("design_screenshots") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars =
                activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        }
    }
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val folder = File(context.cacheDir, "design-screenshots").apply { mkdirs() }
    File(folder, "$name.png").outputStream().use { output ->
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        val nodes = roots.fetchSemanticsNodes()
        val editor = nodes.indexOfFirst { containsTitle(it, "Balance details") || containsTitle(it, "Transaction details") }
        roots[editor.takeIf { it >= 0 } ?: 0].captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, output)
    }
}

private fun containsTitle(node: SemanticsNode, title: String): Boolean =
    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true ||
        node.children.any { containsTitle(it, title) }

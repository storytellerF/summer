package com.storytellerf.summer.data.llmd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.llmd.ipc.ILlmdChatCallback
import com.storytellerf.llmd.ipc.ILlmdService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: an authorized installed LLMD must already be in the requested state. */
class LlmdServiceContractTest {
    @Test fun imageProbeUsesTheExplicitActiveModelWithoutLaunchingSummerUi() = realDeviceTest {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Supply llmd_image_probe=true", args.getString("llmd_image_probe") == "true")
        val target = LlmdTarget.entries.single { it.preferenceValue == args.getString("llmd_target") }
        val expected = requireNotNull(args.getString("llmd_active_model"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "llmd-image-probe.png")
        val bitmap = android.graphics.Bitmap.createBitmap(900, 600, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK; textSize = 48f
            }
            canvas.drawText("Account balance", 60f, 160f, paint)
            paint.textSize = 88f
            canvas.drawText("-245.70", 60f, 300f, paint)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val client = LlmdServiceConnection(context)
            val imageDirectory = java.io.File(context.cacheDir, "llmd-image-probe-output")
            val analyzer = LlmdImageAnalyzer(context, client,
                imageStore = com.storytellerf.summer.data.recognition.FileRecognitionImageStore(imageDirectory))
            try {
                assertEquals(expected, client.activeModel(target))
                val result = analyzer.extractBalancesFromImage(file.toURI().toString(),
                    listOf(com.storytellerf.summer.data.recognition.BalanceReadTarget(1, "Bank", "Account balance", "CNY")), target).getOrThrow()
                assertEquals(-245.70, result.records.single().balance, 0.001)
                assertEquals(expected, client.activeModel(target))
                result.imagePath?.let { java.io.File(imageDirectory, it).delete() }
            } finally { analyzer.close() }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun twoTextRequestsUseTheExplicitActiveModelAcrossClientDisconnects() = realDeviceTest {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Supply llmd_text_probe=true", args.getString("llmd_text_probe") == "true")
        val target = LlmdTarget.entries.single { it.preferenceValue == args.getString("llmd_target") }
        val expected = requireNotNull(args.getString("llmd_active_model"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(2) {
            val client = LlmdServiceConnection(context)
            try {
                assertEquals(expected, client.activeModel(target))
                val request = JSONObject().put("model", expected).put("temperature", 0.0)
                    .put("messages", org.json.JSONArray().put(JSONObject()
                        .put("role", "user").put("content", "Reply with exactly the word OK.")))
                val result = JSONObject(client.chatCompletion(target, request.toString()))
                assertFalse("Generation must succeed", result.has("error"))
                assertEquals(expected, result.getString("model"))
                assertTrue(result.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").isNotBlank())
            } finally { client.close() }
        }
    }

    @Test fun inventoryAndInvalidRequestsNeverStartOrSubstituteModels() = realDeviceTest {
        val args = InstrumentationRegistry.getArguments()
        val expectedState = args.getString("llmd_service_state")
        assumeTrue("Supply llmd_service_state=off or ready", expectedState != null)
        require(expectedState in listOf("off", "ready"))
        val target = LlmdTarget.entries.single { it.preferenceValue == args.getString("llmd_target") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CompletableDeferred<ILlmdService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(ILlmdService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = withContext(Dispatchers.Main) {
            context.bindService(Intent(LlmdServiceConnection.ACTION_BIND_IPC)
                .setComponent(ComponentName(target.packageName, LlmdTarget.SERVICE_CLASS_NAME)),
                connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(bound)
        try {
            val service = withTimeout(10_000) { connected.await() }
            suspend fun response(call: (ILlmdChatCallback) -> Unit): JSONObject {
                val result = CompletableDeferred<String>()
                call(object : ILlmdChatCallback.Stub() {
                    override fun onComplete(responseJson: String) { result.complete(responseJson) }
                })
                return JSONObject(withTimeout(10_000) { result.await() })
            }
            val health = response(service::healthAsync)
            assertFalse("Authorize Summer Debug first", health.has("error"))
            assertEquals(expectedState, health.getString("serviceState"))
            assertEquals(expectedState == "ready", health.getBoolean("engineReady"))
            val expectedModel = args.getString("llmd_active_model")
            if (expectedState == "off") assertTrue(health.isNull("activeModel"))
            else assertEquals(requireNotNull(expectedModel), health.getString("activeModel"))

            val models = response(service::listModelsAsync).getJSONArray("data")
            val ids = (0 until models.length()).map { models.getJSONObject(it).getString("id") }.toSet()
            val expectedIds = requireNotNull(args.getString("llmd_model_ids")).split(',').toSet()
            assertEquals(expectedIds, ids)
            val status = AndroidLlmdStatusReader(context).read(target)
            assertTrue(status is LlmdStatus.Available)
            status as LlmdStatus.Available
            assertEquals(expectedState, status.service.value)
            assertEquals(expectedIds, status.models.toSet())
            assertEquals(expectedModel, status.activeModel)
            val invalidModels = mutableListOf<String?>(null, "", "missing-test-model")
            invalidModels.addAll(ids.filter { expectedState == "off" || it != expectedModel })
            for (model in invalidModels) {
                val request = JSONObject().put("messages", org.json.JSONArray())
                if (model != null) request.put("model", model)
                val result = response { service.chatCompletionAsync(request.toString(), it) }
                assertTrue("Invalid model must return an error", result.has("error"))
            }
            val after = response(service::healthAsync)
            assertEquals(health.getString("serviceState"), after.getString("serviceState"))
            assertEquals(health.opt("activeModel"), after.opt("activeModel"))
        } finally {
            withContext(Dispatchers.Main) { context.unbindService(connection) }
        }
        val client = LlmdServiceConnection(context)
        try {
            val rebound = JSONObject(client.health(target))
            assertEquals(expectedState, rebound.getString("serviceState"))
            if (expectedState == "ready") assertEquals(args.getString("llmd_active_model"), client.activeModel(target))
            else assertTrue(runCatching { client.activeModel(target) }.exceptionOrNull() is LlmdRecognitionException)
        } finally { client.close() }
    }

    private fun realDeviceTest(block: suspend () -> Unit) = runTest(timeout = 300.seconds) {
        withContext(Dispatchers.Default) { block() }
    }
}

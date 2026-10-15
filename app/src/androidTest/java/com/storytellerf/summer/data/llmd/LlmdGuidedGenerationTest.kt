package com.storytellerf.summer.data.llmd

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.recognition.BALANCES_RESPONSE_SCHEMA
import com.storytellerf.summer.data.recognition.BalanceReadTarget
import com.storytellerf.summer.data.recognition.parseBalances
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Opt-in, actual Binder generation; LLMD stays foreground throughout these probes. */
class LlmdGuidedGenerationTest {
    @Test fun textSchemaPreservesRequestedValue() = probe { client, target, model ->
        val schema = JSONObject("""{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false}""")
        val request = JSONObject().put("model", model).put("temperature", 0.0)
            .put("response_format", schemaFormat(schema))
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "Return a JSON object with value equal to 7.")))
        assertEquals(7, JSONObject(extractResponseContent(client.chatCompletion(target, request.toString()))).getInt("value"))
    }

    @Test fun imageTextFormatPreservesBalance() = imageProbe(JSONObject().put("type", "text"))
    @Test fun imageJsonObjectPreservesBalance() = imageProbe(JSONObject().put("type", "json_object"))
    @Test fun imageJsonSchemaPreservesBalance() = imageProbe(schemaFormat(JSONObject(BALANCES_RESPONSE_SCHEMA)))

    private fun schemaFormat(schema: JSONObject) = JSONObject().put("type", "json_schema")
        .put("json_schema", JSONObject().put("name", "probe").put("strict", true).put("schema", schema))

    private fun imageProbe(format: JSONObject) = probe { client, target, model ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "llmd-images").apply { mkdirs() }
        val image = File.createTempFile("format-probe-", ".jpg", directory)
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.llmd-images", image)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            canvas.drawText("Account balance", 60f, 160f, paint)
            paint.textSize = 88f
            canvas.drawText("-245.70", 60f, 300f, paint)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            context.grantUriPermission(target.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val targets = listOf(BalanceReadTarget(1, "Bank", "Account balance", "CNY"))
            val request = JSONObject(buildBalancesExtractionRequest(uri.toString(), targets, model))
                .put("temperature", 0.0).put("response_format", format)
            val records = parseBalances(extractResponseContent(client.chatCompletion(target, request.toString())), targets)
            assertEquals("Exactly one recognized balance", 1, records.size)
            assertEquals(-245.70, records.single().balance, 0.001)
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            bitmap.recycle()
            image.delete()
        }
    }

    private fun probe(block: suspend (LlmdServiceConnection, LlmdTarget, String) -> Unit) = runTest(timeout = 150.seconds) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Supply llmd_format_probe=true", args.getString("llmd_format_probe") == "true")
        val target = LlmdTarget.entries.single { it.preferenceValue == args.getString("llmd_target") }
        val model = requireNotNull(args.getString("llmd_active_model"))
        val client = LlmdServiceConnection(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            withContext(Dispatchers.IO) {
                assertEquals(model, client.activeModel(target))
                block(client, target, model)
                assertEquals(model, client.activeModel(target))
            }
        } finally { client.close() }
    }
}

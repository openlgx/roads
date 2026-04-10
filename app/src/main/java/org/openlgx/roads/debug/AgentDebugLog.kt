package org.openlgx.roads.debug

import android.app.Application
import android.os.Build
import java.io.File
import java.util.concurrent.Executors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.openlgx.roads.BuildConfig
import timber.log.Timber

/** Session log file name; mirror on device matches workspace name for easy adb pull → analysis. */
const val AGENT_DEBUG_LOG_FILE_NAME: String = "debug-1ac86d.log"

/**
 * Debug-session NDJSON (Cursor debug mode). Best-effort; never throws.
 *
 * - **Always** appends one NDJSON line per event under the app’s USB-visible storage
 *   (`Android/data/org.openlgx.roads/files/debug-1ac86d.log`) so you can `adb pull` after a crash.
 * - Optionally POSTs to host ingest (emulator: `10.0.2.2`; physical + `adb reverse tcp:7603 tcp:7603`: `127.0.0.1`).
 */
object AgentDebugLog {
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient()
    private const val INGEST_PATH =
        "/ingest/2434b8a3-90a4-40b9-919f-10fef3ce0b8e"

    @Volatile
    private var logFile: File? = null

    /** Call once from [Application.onCreate] before any [emit]. */
    fun install(app: Application) {
        if (!BuildConfig.DEBUG) return
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        logFile = File(dir, AGENT_DEBUG_LOG_FILE_NAME)
        Timber.i(
            "Debug NDJSON log (adb pull): %s",
            logFile!!.absolutePath,
        )
    }

    private fun buildLine(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?>,
        runId: String?,
    ): String =
        JSONObject().apply {
            put("sessionId", "1ac86d")
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            if (runId != null) put("runId", runId)
            val dataObj = JSONObject()
            for ((k, v) in data) {
                when (v) {
                    null -> dataObj.put(k, JSONObject.NULL)
                    is Number, is Boolean, is String -> dataObj.put(k, v)
                    else -> dataObj.put(k, v.toString())
                }
            }
            put("data", dataObj)
        }.toString()

    /** Append one line to the on-device log **before** the process exits (uncaught handler). */
    fun appendSync(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val sink = logFile ?: return
        val line = buildLine(hypothesisId, location, message, data, runId)
        runCatching {
            sink.appendText(line + "\n", Charsets.UTF_8)
        }.onFailure { Timber.w(it, "AgentDebugLog sync append failed") }
    }

    // #region agent log
    fun emit(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val base =
            if (isProbablyEmulator()) {
                "http://10.0.2.2:7603"
            } else {
                "http://127.0.0.1:7603"
            }
        val url = "$base$INGEST_PATH"
        val line = buildLine(hypothesisId, location, message, data, runId)
        val body = line.toRequestBody("application/json".toMediaType())
        val req =
            Request.Builder()
                .url(url)
                .header("X-Debug-Session-Id", "1ac86d")
                .post(body)
                .build()
        executor.execute {
            val sink = logFile
            if (sink != null) {
                runCatching {
                    sink.appendText(line + "\n", Charsets.UTF_8)
                }.onFailure { Timber.d(it, "AgentDebugLog file append failed") }
            }
            runCatching {
                client.newCall(req).execute().close()
            }.onFailure { Timber.d(it, "AgentDebugLog ingest failed") }
        }
    }

    private fun isProbablyEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emu64") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            "google_sdk" == Build.PRODUCT
    }
    // #endregion
}

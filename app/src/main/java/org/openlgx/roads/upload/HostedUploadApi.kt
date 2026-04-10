package org.openlgx.roads.upload

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.openlgx.roads.export.ExportConstants

data class UploadCreateResponse(
    val uploadJobId: String,
    val recordingSessionId: String,
    val storageBucket: String,
    val objectKey: String,
    val signedUploadUrl: String,
    val signedUploadMethod: String,
    val signedUploadHeaders: Map<String, String>,
    val signedUrlExpiresAt: String,
    val maxBytes: Long,
    val artifactKind: String? = null,
    val checksumAlgorithm: String? = null,
    val multipart: Boolean = false,
)

data class UploadCompleteResponse(
    val uploadJobId: String,
    val state: String,
    val artifactId: String?,
    val processingJobId: String?,
)

class HostedUploadApi(
    private val baseUrl: String,
    private val apiKey: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun url(functionName: String): String {
        val root = baseUrl.trimEnd('/')
        return "$root/$functionName"
    }

    fun createUpload(
        projectId: String,
        deviceId: String,
        clientSessionUuid: String,
        artifactKind: String,
        byteSize: Long,
        contentChecksumSha256: String,
        mimeType: String,
        startedAtEpochMs: Long,
    ): UploadCreateResponse {
        val body = JSONObject().apply {
            put("apiVersion", 1)
            put("projectId", projectId)
            put("deviceId", deviceId)
            put("clientSessionUuid", clientSessionUuid)
            put("artifactKind", artifactKind)
            put("exportSchemaVersion", ExportConstants.EXPORT_SCHEMA_VERSION)
            put("byteSize", byteSize)
            put("contentChecksumSha256", contentChecksumSha256.lowercase())
            put("mimeType", mimeType)
            put("startedAtEpochMs", startedAtEpochMs)
        }.toString()

        val req = Request.Builder()
            .url(url("uploads-create"))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("uploads-create ${resp.code}: $text")
            }
            val o = JSONObject(text)
            val headersObj = o.optJSONObject("signedUploadHeaders")
            val headers = mutableMapOf<String, String>()
            if (headersObj != null) {
                val it = headersObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    headers[k] = headersObj.getString(k)
                }
            }
            val storageBucketStr =
                when {
                    o.has("storageBucket") -> o.getString("storageBucket")
                    o.has("bucket") -> o.getString("bucket")
                    else -> ""
                }.ifEmpty { o.getString("storageBucket") }
            val expires =
                when {
                    o.has("signedUrlExpiresAt") -> o.getString("signedUrlExpiresAt")
                    o.has("expiresAt") -> o.getString("expiresAt")
                    else -> o.getString("signedUrlExpiresAt")
                }
            return UploadCreateResponse(
                uploadJobId = o.getString("uploadJobId"),
                recordingSessionId = o.getString("recordingSessionId"),
                storageBucket = storageBucketStr,
                objectKey = o.getString("objectKey"),
                signedUploadUrl = o.getString("signedUploadUrl"),
                signedUploadMethod = o.optString("signedUploadMethod", "PUT"),
                signedUploadHeaders = headers,
                signedUrlExpiresAt = expires,
                maxBytes = o.getLong("maxBytes"),
                artifactKind = if (o.has("artifactKind")) o.getString("artifactKind") else null,
                checksumAlgorithm =
                    if (o.has("checksumAlgorithm")) o.getString("checksumAlgorithm") else null,
                multipart = o.optBoolean("multipart", false),
            )
        }
    }

    fun completeUpload(
        uploadJobId: String,
        objectKey: String,
        byteSize: Long,
        contentChecksumSha256: String,
        clientSessionUuid: String? = null,
        artifactKind: String? = null,
    ): UploadCompleteResponse {
        val body = JSONObject().apply {
            put("apiVersion", 1)
            put("uploadJobId", uploadJobId)
            put("objectKey", objectKey)
            put("byteSize", byteSize)
            put("contentChecksumSha256", contentChecksumSha256.lowercase())
            if (clientSessionUuid != null) put("clientSessionUuid", clientSessionUuid)
            if (artifactKind != null) put("artifactKind", artifactKind)
        }.toString()
        val req = Request.Builder()
            .url(url("uploads-complete"))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("uploads-complete ${resp.code}: $text")
            }
            val o = JSONObject(text)
            return UploadCompleteResponse(
                uploadJobId = o.getString("uploadJobId"),
                state = o.getString("state"),
                artifactId = if (!o.isNull("artifactId")) o.getString("artifactId") else null,
                processingJobId =
                    if (o.has("processingJobId") && !o.isNull("processingJobId")) {
                        o.getString("processingJobId")
                    } else {
                        null
                    },
            )
        }
    }

    fun putToSignedUrl(
        signedUrl: String,
        file: java.io.File,
        contentType: String,
        extraHeaders: Map<String, String>,
    ) {
        val media = contentType.toMediaType()
        val body = file.asRequestBody(media)
        val b = Request.Builder().url(signedUrl).put(body)
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("PUT upload ${resp.code} ${resp.body?.string()}")
            }
        }
    }
}

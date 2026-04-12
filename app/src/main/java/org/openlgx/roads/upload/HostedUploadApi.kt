package org.openlgx.roads.upload

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
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
    val multipartGroupId: String? = null,
    val multipartPartIndex: Int? = null,
    val multipartPartTotal: Int? = null,
)

data class MultipartCreateParams(
    val groupId: String,
    val partIndex: Int,
    val partTotal: Int,
    val wholeFileBytes: Long,
    val wholeFileChecksumSha256: String,
)

data class UploadCompleteResponse(
    val uploadJobId: String,
    val state: String,
    val artifactId: String?,
    val processingJobId: String?,
    val multipartPending: Boolean = false,
    val completedParts: Int = 0,
    val totalParts: Int = 1,
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
        multipart: MultipartCreateParams? = null,
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
            if (multipart != null) {
                put(
                    "multipart",
                    JSONObject().apply {
                        put("groupId", multipart.groupId)
                        put("partIndex", multipart.partIndex)
                        put("partTotal", multipart.partTotal)
                        put("wholeFileBytes", multipart.wholeFileBytes)
                        put("wholeFileChecksumSha256", multipart.wholeFileChecksumSha256.lowercase())
                    },
                )
            }
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
                multipartGroupId = if (o.has("multipartGroupId") && !o.isNull("multipartGroupId")) {
                    o.getString("multipartGroupId")
                } else {
                    null
                },
                multipartPartIndex = if (o.has("multipartPartIndex") && !o.isNull("multipartPartIndex")) {
                    o.getInt("multipartPartIndex")
                } else {
                    null
                },
                multipartPartTotal = if (o.has("multipartPartTotal") && !o.isNull("multipartPartTotal")) {
                    o.getInt("multipartPartTotal")
                } else {
                    null
                },
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
                multipartPending = o.optBoolean("multipartPending", false),
                completedParts = o.optInt("completedParts", 0),
                totalParts = o.optInt("totalParts", 1),
            )
        }
    }

    /**
     * PUT [file] to the signed URL. [onProgress] is invoked on the OkHttp thread with
     * (bytesUploaded, bytesTotal) as bytes are written; may be throttled by the caller.
     */
    fun putToSignedUrl(
        signedUrl: String,
        file: java.io.File,
        contentType: String,
        extraHeaders: Map<String, String>,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        putToSignedUrl(
            signedUrl = signedUrl,
            file = file,
            offset = 0L,
            length = file.length(),
            contentType = contentType,
            extraHeaders = extraHeaders,
            onProgress = onProgress,
        )
    }

    /**
     * PUT a byte range of [file] (for multipart session ZIP parts under the Storage per-object limit).
     */
    fun putToSignedUrl(
        signedUrl: String,
        file: java.io.File,
        offset: Long,
        length: Long,
        contentType: String,
        extraHeaders: Map<String, String>,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val media = contentType.toMediaType()
        val total = length
        val body =
            object : RequestBody() {
                override fun contentType() = media

                override fun contentLength(): Long = total

                override fun writeTo(sink: BufferedSink) {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(offset)
                        val buf = ByteArray(64 * 1024)
                        var uploaded = 0L
                        while (uploaded < total) {
                            val toRead = kotlin.math.min(buf.size.toLong(), total - uploaded).toInt()
                            val n = raf.read(buf, 0, toRead)
                            if (n <= 0) break
                            sink.write(buf, 0, n)
                            uploaded += n.toLong()
                            onProgress?.invoke(uploaded, total)
                        }
                    }
                }
            }
        val b = Request.Builder().url(signedUrl).put(body)
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                val storageHint =
                    if (resp.code == 413 ||
                        errBody.contains("Payload too large", ignoreCase = true) ||
                        errBody.contains("maximum allowed size", ignoreCase = true)
                    ) {
                        " — Storage rejected the object (often Free tier 50 MiB cap per file; " +
                            "the app should use chunked multipart uploads — if you see this, " +
                            "report it. On Pro+ you can raise Storage global file size limit."
                    } else {
                        ""
                    }
                throw IllegalStateException("PUT upload ${resp.code} $errBody$storageHint")
            }
        }
    }
}

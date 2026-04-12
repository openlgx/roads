package org.openlgx.roads.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.UploadBatchEntity
import org.openlgx.roads.data.local.db.model.BatchUploadState
import org.openlgx.roads.data.local.db.model.SessionHostedPipelineState
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.export.FilteredSessionExporter
import org.openlgx.roads.roadpack.RoadPackManager

@HiltWorker
class SessionUploadWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val appSettingsRepository: AppSettingsRepository,
    private val database: RoadsDatabase,
    private val filteredSessionExporter: FilteredSessionExporter,
    private val roadPackManager: RoadPackManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        if (sessionId < 0) return Result.failure()

        val settings = appSettingsRepository.settings.first()
        if (!settings.uploadEnabled) {
            resetStaleUploadingState(sessionId)
            return Result.success()
        }

        if (settings.uploadRoadFilterEnabled &&
            settings.uploadRoadPackRequiredForAutoUpload &&
            !roadPackManager.hasPackForCouncil(settings.uploadCouncilSlug)
        ) {
            database.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.UPLOAD_SKIPPED,
            )
            return Result.success()
        }

        if (settings.uploadBaseUrl.isBlank() || settings.uploadApiKey.isBlank() ||
            settings.uploadProjectId.isBlank() || settings.uploadDeviceId.isBlank()
        ) {
            database.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.UPLOAD_SKIPPED,
            )
            return Result.success()
        }

        val row = database.recordingSessionDao().getById(sessionId) ?: return Result.failure()

        val existingBatch = database.uploadBatchDao().findAckedBatchForSession(sessionId)
        if (existingBatch != null) {
            database.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.PROCESSING_REMOTE,
            )
            return Result.success()
        }

        val retryLimit = settings.uploadRetryLimit

        appSettingsRepository.markHostedUploadAttempt(sessionId)
        database.recordingSessionDao().updateHostedPipelineState(
            sessionId,
            SessionHostedPipelineState.UPLOADING,
        )

        val resumeStaging = database.uploadBatchDao().findStagingBatchForSession(sessionId)
        val resumeBundle = resumeStaging?.let { bundleFromStagingRow(it) }
        if (resumeStaging != null && resumeBundle == null) {
            database.uploadBatchDao().deleteStagingBatchesForSession(sessionId)
        }

        val prepResult: kotlin.Result<FilteredSessionExporter.UploadPreparation> =
            if (resumeBundle != null) {
                kotlin.Result.success(
                    FilteredSessionExporter.UploadPreparation.Uploadable(resumeBundle),
                )
            } else {
                setProgress(
                    workDataOf(
                        SessionUploadProgressKeys.PROGRESS_SESSION_ID to sessionId,
                        SessionUploadProgressKeys.PROGRESS_PHASE to SessionUploadProgressKeys.PHASE_PREPARE,
                        SessionUploadProgressKeys.PROGRESS_BYTES_UPLOADED to 0L,
                        SessionUploadProgressKeys.PROGRESS_BYTES_TOTAL to 0L,
                    ),
                )
                filteredSessionExporter.prepareForUpload(
                    sessionId = sessionId,
                    settings = settings,
                    onPrepareProgress = { rowsDone, rowsTotal ->
                        setProgress(
                            workDataOf(
                                SessionUploadProgressKeys.PROGRESS_SESSION_ID to sessionId,
                                SessionUploadProgressKeys.PROGRESS_PHASE to SessionUploadProgressKeys.PHASE_PREPARE,
                                SessionUploadProgressKeys.PROGRESS_PREPARE_ROWS_DONE to rowsDone,
                                SessionUploadProgressKeys.PROGRESS_PREPARE_ROWS_TOTAL to rowsTotal,
                            ),
                        )
                    },
                )
            }

        val prep =
            prepResult.getOrElse { e ->
                database.recordingSessionDao().updateHostedPipelineState(
                    sessionId,
                    SessionHostedPipelineState.FAILED,
                )
                appSettingsRepository.markHostedUploadFailure(
                    sessionId,
                    e.message ?: "prepareForUpload failed",
                )
                return Result.retry()
            }

        return when (val p = prep) {
            is FilteredSessionExporter.UploadPreparation.Skipped -> {
                val now = System.currentTimeMillis()
                database.uploadBatchDao().insert(
                    UploadBatchEntity(
                        sessionId = sessionId,
                        createdAtEpochMs = now,
                        payloadManifestJson = "{}",
                        batchUploadState = BatchUploadState.SKIPPED,
                        attemptCount = 0,
                        roadFilterSummaryJson = p.roadFilterSummaryJson,
                        sourceSessionUuid = row.uuid,
                        uploadSkipReason = p.uploadSkipReason,
                        filterChangedPayload = false,
                        networkPolicySnapshotJson = """{"wifiOnly":${settings.uploadWifiOnly}}""",
                    ),
                )
                database.recordingSessionDao().updateHostedPipelineState(
                    sessionId,
                    SessionHostedPipelineState.UPLOAD_SKIPPED,
                )
                Result.success()
            }
            is FilteredSessionExporter.UploadPreparation.Uploadable -> {
                val prepared = p.bundle
                val stagingRowId: Long =
                    if (resumeBundle != null) {
                        requireNotNull(resumeStaging).id
                    } else {
                        val checksum = FileChecksums.sha256Hex(prepared.zipFile)
                        database.uploadBatchDao().deleteStagingBatchesForSession(sessionId)
                        database.uploadBatchDao().insert(
                            UploadBatchEntity(
                                sessionId = sessionId,
                                createdAtEpochMs = System.currentTimeMillis(),
                                payloadManifestJson = prepared.payloadManifestJson,
                                batchUploadState = BatchUploadState.READY,
                                attemptCount = 0,
                                roadFilterSummaryJson = prepared.roadFilterSummaryJson,
                                sourceSessionUuid = row.uuid,
                                localRawArtifactUri = prepared.localRawArtifactUri,
                                localFilteredArtifactUri = prepared.localFilteredArtifactUri,
                                filterChangedPayload = prepared.filterChangedPayload,
                                networkPolicySnapshotJson = """{"wifiOnly":${settings.uploadWifiOnly}}""",
                                contentChecksumSha256 = checksum,
                                artifactKind = prepared.artifactKind,
                            ),
                        )
                    }

                coroutineScope {
                    uploadPreparedArtifact(
                        scope = this,
                        sessionId = sessionId,
                        stagingRowId = stagingRowId,
                        prepared = prepared,
                        row = row,
                        settings = settings,
                        retryLimit = retryLimit,
                        runAttemptCount = runAttemptCount,
                    )
                }
            }
        }
    }

    /**
     * Reconstructs a [PreparedBundle] from a persisted staging row if the ZIP still exists and matches
     * [UploadBatchEntity.contentChecksumSha256].
     */
    private fun bundleFromStagingRow(
        entity: UploadBatchEntity,
    ): FilteredSessionExporter.PreparedBundle? {
        val path = entity.localFilteredArtifactUri ?: entity.localRawArtifactUri ?: return null
        val zip = File(path)
        if (!zip.isFile) return null
        val expected = entity.contentChecksumSha256 ?: return null
        val actual = FileChecksums.sha256Hex(zip)
        if (!actual.equals(expected, ignoreCase = true)) return null
        return FilteredSessionExporter.PreparedBundle(
            zipFile = zip,
            roadFilterSummaryJson = entity.roadFilterSummaryJson ?: "{}",
            artifactKind = entity.artifactKind ?: "RAW_UPLOAD",
            payloadManifestJson = entity.payloadManifestJson,
            localRawArtifactUri = entity.localRawArtifactUri,
            localFilteredArtifactUri =
                entity.localFilteredArtifactUri ?: entity.localRawArtifactUri ?: path,
            filterChangedPayload = entity.filterChangedPayload,
        )
    }

    private suspend fun uploadPreparedArtifact(
        scope: CoroutineScope,
        sessionId: Long,
        stagingRowId: Long,
        prepared: FilteredSessionExporter.PreparedBundle,
        row: RecordingSessionEntity,
        settings: AppSettings,
        retryLimit: Int,
        runAttemptCount: Int,
    ): Result {
        val zip = prepared.zipFile
        val checksum = FileChecksums.sha256Hex(zip)
        val api = HostedUploadApi(settings.uploadBaseUrl, settings.uploadApiKey)

        return try {
            val totalBytes = zip.length()
            val complete: UploadCompleteResponse
            val remoteStorageKey: String
            val remoteUploadJobId: String

            if (totalBytes <= UPLOAD_CHUNK_MAX_BYTES) {
                val created =
                    api.createUpload(
                        projectId = settings.uploadProjectId,
                        deviceId = settings.uploadDeviceId,
                        clientSessionUuid = row.uuid,
                        artifactKind = prepared.artifactKind,
                        byteSize = zip.length(),
                        contentChecksumSha256 = checksum,
                        mimeType = "application/zip",
                        startedAtEpochMs = row.startedAtEpochMs,
                    )

                val putHeaders = created.signedUploadHeaders.toMutableMap()
                if (!putHeaders.containsKey("Content-Type")) {
                    putHeaders["Content-Type"] = "application/zip"
                }

                setProgress(
                    workDataOf(
                        SessionUploadProgressKeys.PROGRESS_SESSION_ID to sessionId,
                        SessionUploadProgressKeys.PROGRESS_PHASE to SessionUploadProgressKeys.PHASE_UPLOAD,
                        SessionUploadProgressKeys.PROGRESS_BYTES_UPLOADED to 0L,
                        SessionUploadProgressKeys.PROGRESS_BYTES_TOTAL to totalBytes,
                    ),
                )

                var lastReported = 0L
                var lastProgressNs = 0L
                api.putToSignedUrl(
                    signedUrl = created.signedUploadUrl,
                    file = zip,
                    contentType = "application/zip",
                    extraHeaders = putHeaders,
                    onProgress = { uploaded, total ->
                        val now = System.nanoTime()
                        val step =
                            if (total > 0) (total / 100).coerceAtLeast(256_000L) else 256_000L
                        val shouldReport =
                            uploaded >= total ||
                                uploaded - lastReported >= step ||
                                now - lastProgressNs >= 300_000_000L
                        if (!shouldReport) return@putToSignedUrl
                        lastProgressNs = now
                        lastReported = uploaded
                        scope.launch {
                            setProgress(
                                workDataOf(
                                    SessionUploadProgressKeys.PROGRESS_SESSION_ID to sessionId,
                                    SessionUploadProgressKeys.PROGRESS_PHASE to
                                        SessionUploadProgressKeys.PHASE_UPLOAD,
                                    SessionUploadProgressKeys.PROGRESS_BYTES_UPLOADED to uploaded,
                                    SessionUploadProgressKeys.PROGRESS_BYTES_TOTAL to total,
                                ),
                            )
                        }
                    },
                )

                complete =
                    api.completeUpload(
                        uploadJobId = created.uploadJobId,
                        objectKey = created.objectKey,
                        byteSize = zip.length(),
                        contentChecksumSha256 = checksum,
                        clientSessionUuid = row.uuid,
                        artifactKind = prepared.artifactKind,
                    )
                remoteStorageKey = created.objectKey
                remoteUploadJobId = complete.uploadJobId
            } else {
                val wholeChecksum = checksum
                val groupId = UUID.randomUUID().toString()
                val partCount =
                    ((totalBytes + UPLOAD_CHUNK_MAX_BYTES - 1) / UPLOAD_CHUNK_MAX_BYTES).toInt()
                var firstObjectKey: String? = null
                var lastReportedOverall = 0L
                var lastProgressNs = 0L
                var lastPartComplete: UploadCompleteResponse? = null

                setProgress(
                    workDataOf(
                        SessionUploadProgressKeys.PROGRESS_SESSION_ID to sessionId,
                        SessionUploadProgressKeys.PROGRESS_PHASE to SessionUploadProgressKeys.PHASE_UPLOAD,
                        SessionUploadProgressKeys.PROGRESS_BYTES_UPLOADED to 0L,
                        SessionUploadProgressKeys.PROGRESS_BYTES_TOTAL to totalBytes,
                    ),
                )

                for (partIndex in 0 until partCount) {
                    val offset = partIndex.toLong() * UPLOAD_CHUNK_MAX_BYTES
                    val len = min(UPLOAD_CHUNK_MAX_BYTES, totalBytes - offset)
                    val chunkChecksum = FileChecksums.sha256Hex(zip, offset, len)
                    val created =
                        api.createUpload(
                            projectId = settings.uploadProjectId,
                            deviceId = settings.uploadDeviceId,
                            clientSessionUuid = row.uuid,
                            artifactKind = prepared.artifactKind,
                            byteSize = len,
                            contentChecksumSha256 = chunkChecksum,
                            mimeType = "application/octet-stream",
                            startedAtEpochMs = row.startedAtEpochMs,
                            multipart =
                                MultipartCreateParams(
                                    groupId = groupId,
                                    partIndex = partIndex,
                                    partTotal = partCount,
                                    wholeFileBytes = totalBytes,
                                    wholeFileChecksumSha256 = wholeChecksum,
                                ),
                        )
                    if (firstObjectKey == null) {
                        firstObjectKey = created.objectKey
                    }
                    val putHeaders = created.signedUploadHeaders.toMutableMap()
                    if (!putHeaders.containsKey("Content-Type")) {
                        putHeaders["Content-Type"] = "application/octet-stream"
                    }

                    api.putToSignedUrl(
                        signedUrl = created.signedUploadUrl,
                        file = zip,
                        offset = offset,
                        length = len,
                        contentType = "application/octet-stream",
                        extraHeaders = putHeaders,
                        onProgress = { uploadedInPart, partLen ->
                            val overall = offset + uploadedInPart
                            val now = System.nanoTime()
                            val step =
                                if (totalBytes > 0) (totalBytes / 100).coerceAtLeast(256_000L)
                                else 256_000L
                            val shouldReport =
                                overall >= totalBytes ||
                                    overall - lastReportedOverall >= step ||
                                    now - lastProgressNs >= 300_000_000L
                            if (!shouldReport) return@putToSignedUrl
                            lastProgressNs = now
                            lastReportedOverall = overall
                            scope.launch {
                                setProgress(
                                    workDataOf(
                                        SessionUploadProgressKeys.PROGRESS_SESSION_ID to sessionId,
                                        SessionUploadProgressKeys.PROGRESS_PHASE to
                                            SessionUploadProgressKeys.PHASE_UPLOAD,
                                        SessionUploadProgressKeys.PROGRESS_BYTES_UPLOADED to overall,
                                        SessionUploadProgressKeys.PROGRESS_BYTES_TOTAL to totalBytes,
                                    ),
                                )
                            }
                        },
                    )

                    lastPartComplete =
                        api.completeUpload(
                            uploadJobId = created.uploadJobId,
                            objectKey = created.objectKey,
                            byteSize = len,
                            contentChecksumSha256 = chunkChecksum,
                            clientSessionUuid = row.uuid,
                            artifactKind = prepared.artifactKind,
                        )
                }

                complete =
                    lastPartComplete
                        ?: throw IllegalStateException("multipart upload produced no parts")
                remoteStorageKey = requireNotNull(firstObjectKey) { "multipart first object key" }
                remoteUploadJobId = complete.uploadJobId
            }

            val now = System.currentTimeMillis()
            val staging = database.uploadBatchDao().getById(stagingRowId)
            if (staging != null) {
                database.uploadBatchDao().update(
                    staging.copy(
                        batchUploadState = BatchUploadState.ACKED,
                        attemptCount = staging.attemptCount + 1,
                        payloadManifestJson = prepared.payloadManifestJson,
                        remoteUploadJobId = remoteUploadJobId,
                        remoteStorageKey = remoteStorageKey,
                        roadFilterSummaryJson = prepared.roadFilterSummaryJson,
                        lastSuccessfulUploadAtEpochMs = now,
                        lastErrorCategory = null,
                        networkPolicySnapshotJson = """{"wifiOnly":${settings.uploadWifiOnly}}""",
                        localRawArtifactUri = prepared.localRawArtifactUri,
                        localFilteredArtifactUri = prepared.localFilteredArtifactUri,
                        filterChangedPayload = prepared.filterChangedPayload,
                        contentChecksumSha256 = checksum,
                        artifactKind = prepared.artifactKind,
                    ),
                )
            }

            val uploadedState =
                if (complete.processingJobId != null) {
                    SessionHostedPipelineState.PROCESSING_REMOTE
                } else {
                    SessionHostedPipelineState.UPLOADED
                }
            database.recordingSessionDao().updateHostedPipelineState(sessionId, uploadedState)
            appSettingsRepository.markHostedUploadSuccess(sessionId)
            Result.success()
        } catch (e: Exception) {
            database.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.FAILED,
            )
            val msg = e.message?.let { m -> if (m.length > 350) m.take(350) + "…" else m }
                ?: e.javaClass.simpleName
            appSettingsRepository.markHostedUploadFailure(sessionId, msg)
            val staging = database.uploadBatchDao().getById(stagingRowId)
            if (staging != null) {
                database.uploadBatchDao().update(
                    staging.copy(
                        batchUploadState = BatchUploadState.FAILED_RETRYABLE,
                        attemptCount = staging.attemptCount + 1,
                        lastErrorCategory = msg.take(120),
                    ),
                )
            }
            if (runAttemptCount < retryLimit) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * If a previous run left the session stuck at UPLOADING (e.g. process killed mid-upload,
     * then upload was disabled), reset it so backfill can pick it up later.
     */
    private suspend fun resetStaleUploadingState(sessionId: Long) {
        val current = database.recordingSessionDao().getById(sessionId)
        if (current?.hostedPipelineState == SessionHostedPipelineState.UPLOADING) {
            database.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.NOT_STARTED,
            )
        }
    }

    companion object {
        const val KEY_SESSION_ID = "sessionId"
        /** Stay under Supabase Free Storage per-object limit (50 MiB); matches Edge CHUNK_MAX_BYTES. */
        private const val UPLOAD_CHUNK_MAX_BYTES = 48L * 1024L * 1024L
    }
}

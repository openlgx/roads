package org.openlgx.roads.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.model.BatchUploadState
import org.openlgx.roads.data.local.db.model.SessionHostedPipelineState
import org.openlgx.roads.data.local.db.entity.UploadBatchEntity
import org.openlgx.roads.data.local.settings.AppSettingsRepository
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
        if (!settings.uploadEnabled) return Result.success()

        if (settings.uploadRoadPackRequiredForAutoUpload &&
            !roadPackManager.hasPackForCouncil(settings.uploadCouncilSlug)
        ) {
            return Result.retry()
        }

        if (settings.uploadBaseUrl.isBlank() || settings.uploadApiKey.isBlank() ||
            settings.uploadProjectId.isBlank() || settings.uploadDeviceId.isBlank()
        ) {
            database.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.FAILED,
            )
            return Result.failure()
        }

        val row = database.recordingSessionDao().getById(sessionId) ?: return Result.failure()
        val retryLimit = settings.uploadRetryLimit

        database.recordingSessionDao().updateHostedPipelineState(
            sessionId,
            SessionHostedPipelineState.UPLOADING,
        )

        val prepared =
            filteredSessionExporter.prepareForUpload(sessionId, settings).getOrElse {
                database.recordingSessionDao().updateHostedPipelineState(
                    sessionId,
                    SessionHostedPipelineState.FAILED,
                )
                return Result.retry()
            }

        val zip = prepared.zipFile
        val checksum = FileChecksums.sha256Hex(zip)
        val api = HostedUploadApi(settings.uploadBaseUrl, settings.uploadApiKey)

        return try {
            val created = api.createUpload(
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
            api.putToSignedUrl(
                signedUrl = created.signedUploadUrl,
                file = zip,
                contentType = "application/zip",
                extraHeaders = created.signedUploadHeaders,
            )

            val complete = api.completeUpload(
                uploadJobId = created.uploadJobId,
                objectKey = created.objectKey,
                byteSize = zip.length(),
                contentChecksumSha256 = checksum,
            )

            val now = System.currentTimeMillis()
            database.uploadBatchDao().insert(
                UploadBatchEntity(
                    sessionId = sessionId,
                    createdAtEpochMs = now,
                    payloadManifestJson = "{}",
                    batchUploadState = BatchUploadState.ACKED,
                    attemptCount = 1,
                    remoteUploadJobId = complete.uploadJobId,
                    remoteStorageKey = created.objectKey,
                    roadFilterSummaryJson = prepared.roadFilterSummaryJson,
                    sourceSessionUuid = row.uuid,
                    lastSuccessfulUploadAtEpochMs = now,
                    networkPolicySnapshotJson = """{"wifiOnly":${settings.uploadWifiOnly}}""",
                ),
            )

            val uploadedState =
                if (complete.processingJobId != null) {
                    SessionHostedPipelineState.PROCESSING_REMOTE
                } else {
                    SessionHostedPipelineState.UPLOADED
                }
            database.recordingSessionDao().updateHostedPipelineState(sessionId, uploadedState)
            Result.success()
        } catch (_: Exception) {
            database.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.FAILED,
            )
            return if (runAttemptCount < retryLimit) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_SESSION_ID = "sessionId"
    }
}

package org.openlgx.roads.upload

import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.entity.UploadBatchEntity
import org.openlgx.roads.data.local.db.model.BatchUploadState
import org.openlgx.roads.data.local.db.model.SessionHostedPipelineState
import org.openlgx.roads.data.local.db.model.SessionState

/**
 * Operator-facing hosted pipeline label (pilot). Distinct from persisted [SessionHostedPipelineState]
 * so we can show *ready* / *queued* hints without extra DB columns.
 *
 * **PUBLISHED** means “remote processing finished” on the OLGX side — council GIS layers still refresh on
 * the publish schedule (see pilot docs), not instantly from the device.
 */
enum class HostedPipelineDisplayState {
    NOT_STARTED,
    READY_TO_UPLOAD,
    SKIPPED_LOW_VALUE,
    QUEUED,
    UPLOADING,
    UPLOADED,
    PROCESSING_REMOTE,
    PUBLISHED,
    FAILED,
}

object HostedPipelineDisplayMapper {

    fun forSession(
        session: RecordingSessionEntity,
        latestBatch: UploadBatchEntity?,
        uploadEnabled: Boolean,
        uploadAutoAfterSessionEnabled: Boolean,
    ): HostedPipelineDisplayState {
        val internal = session.hostedPipelineState

        if (internal == SessionHostedPipelineState.UPLOAD_SKIPPED) {
            return HostedPipelineDisplayState.SKIPPED_LOW_VALUE
        }

        if (latestBatch != null &&
            latestBatch.batchUploadState == BatchUploadState.SKIPPED &&
            internal == SessionHostedPipelineState.NOT_STARTED
        ) {
            return HostedPipelineDisplayState.SKIPPED_LOW_VALUE
        }

        if (internal == SessionHostedPipelineState.FAILED) {
            return HostedPipelineDisplayState.FAILED
        }

        when (internal) {
            SessionHostedPipelineState.UPLOADING -> return HostedPipelineDisplayState.UPLOADING
            SessionHostedPipelineState.UPLOADED -> return HostedPipelineDisplayState.UPLOADED
            SessionHostedPipelineState.PROCESSING_REMOTE ->
                return HostedPipelineDisplayState.PROCESSING_REMOTE
            SessionHostedPipelineState.COMPLETED -> return HostedPipelineDisplayState.PUBLISHED
            SessionHostedPipelineState.QUEUED -> return HostedPipelineDisplayState.QUEUED
            else -> Unit
        }

        if (session.state == SessionState.COMPLETED &&
            internal == SessionHostedPipelineState.NOT_STARTED &&
            uploadEnabled &&
            uploadAutoAfterSessionEnabled
        ) {
            val b = latestBatch
            if (b == null) {
                return HostedPipelineDisplayState.READY_TO_UPLOAD
            }
            if (b.batchUploadState == BatchUploadState.PENDING_POLICY ||
                b.batchUploadState == BatchUploadState.READY ||
                b.batchUploadState == BatchUploadState.UPLOADING ||
                b.batchUploadState == BatchUploadState.FAILED_RETRYABLE
            ) {
                return HostedPipelineDisplayState.QUEUED
            }
        }

        return when (internal) {
            SessionHostedPipelineState.NOT_STARTED -> HostedPipelineDisplayState.NOT_STARTED
            SessionHostedPipelineState.QUEUED -> HostedPipelineDisplayState.QUEUED
            SessionHostedPipelineState.UPLOADING -> HostedPipelineDisplayState.UPLOADING
            SessionHostedPipelineState.UPLOADED -> HostedPipelineDisplayState.UPLOADED
            SessionHostedPipelineState.UPLOAD_SKIPPED -> HostedPipelineDisplayState.SKIPPED_LOW_VALUE
            SessionHostedPipelineState.PROCESSING_REMOTE -> HostedPipelineDisplayState.PROCESSING_REMOTE
            SessionHostedPipelineState.COMPLETED -> HostedPipelineDisplayState.PUBLISHED
            SessionHostedPipelineState.FAILED -> HostedPipelineDisplayState.FAILED
        }
    }
}

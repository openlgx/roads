package org.openlgx.roads.data.local.db.model

/** Hosted (Neon/remote) upload + processing visibility; distinct from on-device [SessionProcessingState]. */
enum class SessionHostedPipelineState {
    NOT_STARTED,
    QUEUED,
    UPLOADING,
    UPLOADED,
    /** Auto-upload skipped locally (e.g. low road value); see upload_batches.uploadSkipReason. */
    UPLOAD_SKIPPED,
    PROCESSING_REMOTE,
    COMPLETED,
    FAILED,
}

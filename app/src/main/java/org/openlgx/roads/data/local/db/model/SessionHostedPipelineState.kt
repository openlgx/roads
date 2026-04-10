package org.openlgx.roads.data.local.db.model

/** Hosted (Neon/remote) upload + processing visibility; distinct from on-device [SessionProcessingState]. */
enum class SessionHostedPipelineState {
    NOT_STARTED,
    QUEUED,
    UPLOADING,
    UPLOADED,
    PROCESSING_REMOTE,
    COMPLETED,
    FAILED,
}

package org.openlgx.roads.data.local.db.model

enum class SessionState {
    ACTIVE,
    COOLDOWN,
    COMPLETED,
    INTERRUPTED,
    ABORTED_POLICY,
}

enum class RecordingSource {
    AUTO,
    MANUAL_DEBUG,
}

enum class SessionUploadState {
    NOT_QUEUED,
    QUEUED,
    PARTIAL,
    COMPLETE,
}

enum class BatchUploadState {
    PENDING_POLICY,
    READY,
    UPLOADING,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    ACKED,
}

/** Conservative eligibility labels; samples are retained and flagged, not hard-deleted on-device. */
enum class RoadEligibilityDisposition {
    UNKNOWN,
    LIKELY_PUBLIC_ROAD,
    UNLIKELY_OR_OFF_ROAD,
    PRIVATE_OR_CARPARK_HEURISTIC,
}

enum class AnomalyType {
    IMPULSE_EVENT,
    SUSTAINED_ROUGHNESS,
    PERIODIC_CORRUGATION,
    UNKNOWN_EXPERIMENTAL,
}

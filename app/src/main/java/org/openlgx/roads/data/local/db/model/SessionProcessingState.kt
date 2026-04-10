package org.openlgx.roads.data.local.db.model

/**
 * On-device roughness pipeline lifecycle for a recording session row.
 * Persisted as string in Room; see [org.openlgx.roads.data.local.db.Converters].
 */
enum class SessionProcessingState {
    NOT_STARTED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
}

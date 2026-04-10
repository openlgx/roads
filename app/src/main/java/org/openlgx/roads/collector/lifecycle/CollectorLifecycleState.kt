package org.openlgx.roads.collector.lifecycle

/**
 * Runtime passive collector lifecycle (in-memory). Distinct from [org.openlgx.roads.data.local.db.model.SessionState],
 * which describes persisted recording row status.
 */
enum class CollectorLifecycleState {
    IDLE,
    ARMING,
    RECORDING,
    /** Holding session open at stop / intersection; still writing samples. */
    STOP_HOLD,
    PAUSED_POLICY,
    DEGRADED,
}

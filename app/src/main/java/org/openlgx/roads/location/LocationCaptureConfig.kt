package org.openlgx.roads.location

/** Fused location request tuning for passive recording (Phase 2B1). */
data class LocationCaptureConfig(
    val updateIntervalMs: Long = 1_000L,
    val minUpdateIntervalMs: Long = 500L,
    val maxUpdateDelayMs: Long = 1500L,
    /** Max time to wait for a fix during arming validation. */
    val armingLocationTimeoutMs: Long = 12_000L,
    val batchMaxSize: Int = 25,
    val batchFlushIntervalMs: Long = 5_000L,
)

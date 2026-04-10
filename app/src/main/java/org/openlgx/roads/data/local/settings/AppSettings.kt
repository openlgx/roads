package org.openlgx.roads.data.local.settings

/**
 * Capture policy vs upload policy are intentionally separate.
 *
 * [passiveCollectionEffective] is what runtime capture should use: disabled until onboarding, then
 * follows [passiveCollectionUserEnabled] (DataStore default is true when the key is absent).
 */
data class AppSettings(
    val onboardingCompleted: Boolean,
    /** User-visible toggle; persisted even before onboarding completes. */
    val passiveCollectionUserEnabled: Boolean,
    val passiveCollectionEffective: Boolean,
    val uploadWifiOnly: Boolean,
    val uploadAllowCellular: Boolean,
    val uploadOnlyWhileCharging: Boolean,
    val uploadPauseOnLowBatteryEnabled: Boolean,
    val uploadLowBatteryThresholdPercent: Int,
    val retentionDays: Int,
    /** 0 = unset / unlimited placeholder. */
    val maxLocalStorageMb: Int,
    val localCompactionEnabled: Boolean,
    /** Legacy key `capture_min_speed_mps` is read as fallback when this is absent. */
    val captureStartSpeedMps: Float,
    /** When [captureFastArmingEnabled], short arming debounce if speed ≥ this (m/s). */
    val captureImmediateStartSpeedMps: Float,
    /** Below this speed (m/s), movement may be treated as stopped after debounce. */
    val captureStopSpeedMps: Float,
    /** Seconds to remain in STOP_HOLD before finalising the session. */
    val captureStopHoldSeconds: Int,
    /** Radius (m) for “stationary” displacement heuristic. */
    val captureStationaryRadiusMeters: Float,
    val captureFastArmingEnabled: Boolean,
    /** Roughness lab window length (seconds). */
    val processingWindowSeconds: Float,
    val processingDistanceBinMeters: Float,
    val processingLiveAfterSessionEnabled: Boolean,
    val processingAllRunsOverlayEnabled: Boolean,
    val debugModeEnabled: Boolean,
    /**
     * Phase F: when true, completed recording sessions insert optional `calibration_runs` anchor rows
     * and exports include calibration metadata in the manifest.
     */
    val calibrationWorkflowEnabled: Boolean,
    /** DataStore log: last time a “recording started” one-shot was emitted (epoch ms). */
    val lastRecordingStartedAtEpochMs: Long?,
    /** DataStore log: last time a “recording stopped” one-shot was emitted (epoch ms). */
    val lastRecordingStoppedAtEpochMs: Long?,
)

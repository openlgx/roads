package org.openlgx.roads.data.local.settings

import kotlinx.coroutines.flow.Flow

interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setOnboardingCompleted(completed: Boolean)

    suspend fun completeOnboarding()

    suspend fun setPassiveCollectionEnabled(enabled: Boolean)

    suspend fun setUploadWifiOnly(enabled: Boolean)

    suspend fun setUploadAllowCellular(enabled: Boolean)

    suspend fun setUploadOnlyWhileCharging(enabled: Boolean)

    suspend fun setUploadPauseOnLowBatteryEnabled(enabled: Boolean)

    suspend fun setUploadLowBatteryThresholdPercent(percent: Int)

    suspend fun setRetentionDays(days: Int)

    suspend fun setMaxLocalStorageMb(mb: Int)

    suspend fun setLocalCompactionEnabled(enabled: Boolean)

    /** @deprecated Legacy; prefer [setCaptureStartSpeedMps]. Writes both keys for migration. */
    suspend fun setCaptureMinSpeedMps(mps: Float)

    suspend fun setCaptureStartSpeedMps(mps: Float)

    suspend fun setCaptureImmediateStartSpeedMps(mps: Float)

    suspend fun setCaptureStopSpeedMps(mps: Float)

    suspend fun setCaptureStopHoldSeconds(seconds: Int)

    suspend fun setCaptureStationaryRadiusMeters(meters: Float)

    suspend fun setCaptureFastArmingEnabled(enabled: Boolean)

    suspend fun setProcessingWindowSeconds(seconds: Float)

    suspend fun setProcessingDistanceBinMeters(meters: Float)

    suspend fun setProcessingLiveAfterSessionEnabled(enabled: Boolean)

    suspend fun setProcessingAllRunsOverlayEnabled(enabled: Boolean)

    suspend fun applyCapturePreset(preset: CaptureSettingsPreset)

    suspend fun setDebugModeEnabled(enabled: Boolean)

    suspend fun setCalibrationWorkflowEnabled(enabled: Boolean)

    suspend fun recordRecordingStartedAt(epochMs: Long = System.currentTimeMillis())

    suspend fun recordRecordingStoppedAt(epochMs: Long = System.currentTimeMillis())

    suspend fun setUploadEnabled(enabled: Boolean)

    suspend fun setUploadBaseUrl(url: String)

    suspend fun setUploadApiKey(key: String)

    suspend fun setUploadRetryLimit(limit: Int)

    suspend fun setUploadAutoAfterSessionEnabled(enabled: Boolean)

    suspend fun setUploadRoadFilterEnabled(enabled: Boolean)

    suspend fun setUploadRoadFilterDistanceMeters(meters: Float)

    suspend fun setUploadRoadFilterUnknownPolicy(policy: UploadRoadFilterUnknownPolicy)

    suspend fun setUploadRoadPackRequiredForAutoUpload(required: Boolean)

    suspend fun setUploadCouncilSlug(slug: String)

    suspend fun setUploadProjectSlug(slug: String)

    suspend fun setUploadProjectId(id: String)

    suspend fun setUploadDeviceId(id: String)

    suspend fun setUploadChargingPreferred(preferred: Boolean)

    /** Records telemetry for Settings / diagnostics (never log secrets). */
    suspend fun markHostedUploadAttempt(sessionId: Long, timestampMs: Long = System.currentTimeMillis())

    suspend fun markHostedUploadSuccess(sessionId: Long, timestampMs: Long = System.currentTimeMillis())

    suspend fun markHostedUploadFailure(
        sessionId: Long,
        message: String,
        timestampMs: Long = System.currentTimeMillis(),
    )
}

package org.openlgx.roads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.settings.CaptureSettingsPreset
import org.openlgx.roads.data.local.settings.UploadRoadFilterUnknownPolicy

/**
 * Test double for ViewModel unit tests; setters are no-ops unless you mutate [mutState] externally.
 */
class FakeAppSettingsRepository(
    initial: AppSettings,
) : AppSettingsRepository {
    private val mutState = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = mutState.asStateFlow()

    fun overrideSettings(value: AppSettings) {
        mutState.value = value
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) = Unit

    override suspend fun completeOnboarding() = Unit

    override suspend fun setPassiveCollectionEnabled(enabled: Boolean) = Unit

    override suspend fun setUploadWifiOnly(enabled: Boolean) = Unit

    override suspend fun setUploadAllowCellular(enabled: Boolean) = Unit

    override suspend fun setUploadOnlyWhileCharging(enabled: Boolean) = Unit

    override suspend fun setUploadPauseOnLowBatteryEnabled(enabled: Boolean) = Unit

    override suspend fun setUploadLowBatteryThresholdPercent(percent: Int) = Unit

    override suspend fun setRetentionDays(days: Int) = Unit

    override suspend fun setMaxLocalStorageMb(mb: Int) = Unit

    override suspend fun setLocalCompactionEnabled(enabled: Boolean) = Unit

    override suspend fun setCaptureMinSpeedMps(mps: Float) = Unit

    override suspend fun setCaptureStartSpeedMps(mps: Float) = Unit

    override suspend fun setCaptureImmediateStartSpeedMps(mps: Float) = Unit

    override suspend fun setCaptureStopSpeedMps(mps: Float) = Unit

    override suspend fun setCaptureStopHoldSeconds(seconds: Int) = Unit

    override suspend fun setCaptureStationaryRadiusMeters(meters: Float) = Unit

    override suspend fun setCaptureFastArmingEnabled(enabled: Boolean) = Unit

    override suspend fun setProcessingWindowSeconds(seconds: Float) = Unit

    override suspend fun setProcessingDistanceBinMeters(meters: Float) = Unit

    override suspend fun setProcessingLiveAfterSessionEnabled(enabled: Boolean) = Unit

    override suspend fun setProcessingAllRunsOverlayEnabled(enabled: Boolean) = Unit

    override suspend fun applyCapturePreset(preset: CaptureSettingsPreset) = Unit

    override suspend fun setDebugModeEnabled(enabled: Boolean) = Unit

    override suspend fun setCalibrationWorkflowEnabled(enabled: Boolean) = Unit

    override suspend fun recordRecordingStartedAt(epochMs: Long) = Unit

    override suspend fun recordRecordingStoppedAt(epochMs: Long) = Unit

    override suspend fun setUploadEnabled(enabled: Boolean) = Unit

    override suspend fun setUploadBaseUrl(url: String) = Unit

    override suspend fun setUploadApiKey(key: String) = Unit

    override suspend fun setUploadRetryLimit(limit: Int) = Unit

    override suspend fun setUploadAutoAfterSessionEnabled(enabled: Boolean) = Unit

    override suspend fun setUploadRoadFilterEnabled(enabled: Boolean) = Unit

    override suspend fun setUploadRoadFilterDistanceMeters(meters: Float) = Unit

    override suspend fun setUploadRoadFilterUnknownPolicy(policy: UploadRoadFilterUnknownPolicy) = Unit

    override suspend fun setUploadRoadPackRequiredForAutoUpload(required: Boolean) = Unit

    override suspend fun setUploadCouncilSlug(slug: String) = Unit

    override suspend fun setUploadProjectSlug(slug: String) = Unit

    override suspend fun setUploadProjectId(id: String) = Unit

    override suspend fun setUploadDeviceId(id: String) = Unit

    override suspend fun setUploadChargingPreferred(preferred: Boolean) = Unit
}

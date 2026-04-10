package org.openlgx.roads.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsRepositoryImpl
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
) : AppSettingsRepository {

    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val passiveCollectionEnabled = booleanPreferencesKey("passive_collection_enabled")
        val uploadWifiOnly = booleanPreferencesKey("upload_wifi_only")
        val uploadAllowCellular = booleanPreferencesKey("upload_allow_cellular")
        val uploadOnlyWhileCharging = booleanPreferencesKey("upload_only_while_charging")
        val uploadPauseOnLowBattery = booleanPreferencesKey("upload_pause_on_low_battery_enabled")
        val uploadLowBatteryThreshold = intPreferencesKey("upload_low_battery_threshold_percent")
        val retentionDays = intPreferencesKey("retention_days")
        val maxLocalStorageMb = intPreferencesKey("max_local_storage_mb")
        val localCompactionEnabled = booleanPreferencesKey("local_compaction_enabled")
        val captureMinSpeedMpsLegacy = floatPreferencesKey("capture_min_speed_mps")
        val captureStartSpeedMps = floatPreferencesKey("capture_start_speed_mps")
        val captureImmediateStartSpeedMps =
            floatPreferencesKey("capture_immediate_start_speed_mps")
        val captureStopSpeedMps = floatPreferencesKey("capture_stop_speed_mps")
        val captureStopHoldSeconds = intPreferencesKey("capture_stop_hold_seconds")
        val captureStationaryRadiusMeters = floatPreferencesKey("capture_stationary_radius_meters")
        val captureFastArmingEnabled = booleanPreferencesKey("capture_fast_arming_enabled")
        val processingWindowSeconds = floatPreferencesKey("processing_window_seconds")
        val processingDistanceBinMeters = floatPreferencesKey("processing_distance_bin_meters")
        val processingLiveAfterSessionEnabled =
            booleanPreferencesKey("processing_live_after_session_enabled")
        val processingAllRunsOverlayEnabled =
            booleanPreferencesKey("processing_all_runs_overlay_enabled")
        val debugModeEnabled = booleanPreferencesKey("debug_mode_enabled")
        val calibrationWorkflowEnabled = booleanPreferencesKey("calibration_workflow_enabled")
        val lastRecordingStartedAtEpochMs = longPreferencesKey("last_recording_started_at_epoch_ms")
        val lastRecordingStoppedAtEpochMs = longPreferencesKey("last_recording_stopped_at_epoch_ms")
        val uploadEnabled = booleanPreferencesKey("upload_enabled")
        val uploadBaseUrl = stringPreferencesKey("upload_base_url")
        val uploadApiKey = stringPreferencesKey("upload_api_key")
        val uploadRetryLimit = intPreferencesKey("upload_retry_limit")
        val uploadAutoAfterSession = booleanPreferencesKey("upload_auto_after_session")
        val uploadRoadFilterEnabled = booleanPreferencesKey("upload_road_filter_enabled")
        val uploadRoadFilterDistanceM = floatPreferencesKey("upload_road_filter_distance_m")
        val uploadRoadFilterUnknown = stringPreferencesKey("upload_road_filter_unknown_policy")
        val uploadRoadPackRequired = booleanPreferencesKey("upload_road_pack_required")
        val uploadCouncilSlug = stringPreferencesKey("upload_council_slug")
        val uploadProjectSlug = stringPreferencesKey("upload_project_slug")
        val uploadProjectId = stringPreferencesKey("upload_project_id")
        val uploadDeviceId = stringPreferencesKey("upload_device_id")
        val uploadChargingPreferred = booleanPreferencesKey("upload_charging_preferred")
    }

    override val settings: Flow<AppSettings> =
        dataStore.data.map { prefs ->
            val onboarding = prefs[Keys.onboardingCompleted] ?: false
            val passiveUser = prefs[Keys.passiveCollectionEnabled] ?: true
            val passiveEffective = onboarding && passiveUser

            val startSpeed =
                prefs[Keys.captureStartSpeedMps]
                    ?: prefs[Keys.captureMinSpeedMpsLegacy]
                    ?: DEFAULT_CAPTURE_START_SPEED_MPS

            AppSettings(
                onboardingCompleted = onboarding,
                passiveCollectionUserEnabled = passiveUser,
                passiveCollectionEffective = passiveEffective,
                uploadWifiOnly = prefs[Keys.uploadWifiOnly] ?: true,
                uploadAllowCellular = prefs[Keys.uploadAllowCellular] ?: false,
                uploadOnlyWhileCharging = prefs[Keys.uploadOnlyWhileCharging] ?: false,
                uploadPauseOnLowBatteryEnabled = prefs[Keys.uploadPauseOnLowBattery] ?: true,
                uploadLowBatteryThresholdPercent = prefs[Keys.uploadLowBatteryThreshold] ?: 20,
                retentionDays = prefs[Keys.retentionDays] ?: 30,
                maxLocalStorageMb = prefs[Keys.maxLocalStorageMb] ?: 0,
                localCompactionEnabled = prefs[Keys.localCompactionEnabled] ?: false,
                captureStartSpeedMps = startSpeed,
                captureImmediateStartSpeedMps =
                    prefs[Keys.captureImmediateStartSpeedMps]
                        ?: DEFAULT_CAPTURE_IMMEDIATE_START_SPEED_MPS,
                captureStopSpeedMps =
                    prefs[Keys.captureStopSpeedMps] ?: DEFAULT_CAPTURE_STOP_SPEED_MPS,
                captureStopHoldSeconds =
                    prefs[Keys.captureStopHoldSeconds] ?: DEFAULT_CAPTURE_STOP_HOLD_SECONDS,
                captureStationaryRadiusMeters =
                    prefs[Keys.captureStationaryRadiusMeters]
                        ?: DEFAULT_CAPTURE_STATIONARY_RADIUS_M,
                captureFastArmingEnabled =
                    prefs[Keys.captureFastArmingEnabled] ?: DEFAULT_CAPTURE_FAST_ARMING,
                processingWindowSeconds =
                    prefs[Keys.processingWindowSeconds] ?: DEFAULT_PROCESSING_WINDOW_SECONDS,
                processingDistanceBinMeters =
                    prefs[Keys.processingDistanceBinMeters]
                        ?: DEFAULT_PROCESSING_DISTANCE_BIN_M,
                processingLiveAfterSessionEnabled =
                    prefs[Keys.processingLiveAfterSessionEnabled]
                        ?: DEFAULT_PROCESSING_LIVE_AFTER_SESSION,
                processingAllRunsOverlayEnabled =
                    prefs[Keys.processingAllRunsOverlayEnabled]
                        ?: DEFAULT_PROCESSING_ALL_RUNS_OVERLAY,
                debugModeEnabled = prefs[Keys.debugModeEnabled] ?: false,
                calibrationWorkflowEnabled = prefs[Keys.calibrationWorkflowEnabled] ?: false,
                lastRecordingStartedAtEpochMs = prefs[Keys.lastRecordingStartedAtEpochMs],
                lastRecordingStoppedAtEpochMs = prefs[Keys.lastRecordingStoppedAtEpochMs],
                uploadEnabled = prefs[Keys.uploadEnabled] ?: false,
                uploadBaseUrl = prefs[Keys.uploadBaseUrl] ?: "",
                uploadApiKey = prefs[Keys.uploadApiKey] ?: "",
                uploadRetryLimit = prefs[Keys.uploadRetryLimit] ?: 3,
                uploadAutoAfterSessionEnabled = prefs[Keys.uploadAutoAfterSession] ?: false,
                uploadRoadFilterEnabled = prefs[Keys.uploadRoadFilterEnabled] ?: false,
                uploadRoadFilterDistanceMeters =
                    prefs[Keys.uploadRoadFilterDistanceM] ?: DEFAULT_UPLOAD_ROAD_FILTER_DISTANCE_M,
                uploadRoadFilterUnknownPolicy =
                    prefs[Keys.uploadRoadFilterUnknown]?.let { raw ->
                        try {
                            UploadRoadFilterUnknownPolicy.valueOf(raw)
                        } catch (_: IllegalArgumentException) {
                            UploadRoadFilterUnknownPolicy.UPLOAD
                        }
                    } ?: UploadRoadFilterUnknownPolicy.UPLOAD,
                uploadRoadPackRequiredForAutoUpload = prefs[Keys.uploadRoadPackRequired] ?: true,
                uploadCouncilSlug = prefs[Keys.uploadCouncilSlug] ?: "",
                uploadProjectSlug = prefs[Keys.uploadProjectSlug] ?: "",
                uploadProjectId = prefs[Keys.uploadProjectId] ?: "",
                uploadDeviceId = prefs[Keys.uploadDeviceId] ?: "",
                uploadChargingPreferred = prefs[Keys.uploadChargingPreferred] ?: false,
            )
        }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    override suspend fun completeOnboarding() {
        dataStore.edit { prefs ->
            prefs[Keys.onboardingCompleted] = true
            if (!prefs.contains(Keys.passiveCollectionEnabled)) {
                prefs[Keys.passiveCollectionEnabled] = true
            }
        }
    }

    override suspend fun setPassiveCollectionEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.passiveCollectionEnabled] = enabled }
    }

    override suspend fun setUploadWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadWifiOnly] = enabled }
    }

    override suspend fun setUploadAllowCellular(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadAllowCellular] = enabled }
    }

    override suspend fun setUploadOnlyWhileCharging(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadOnlyWhileCharging] = enabled }
    }

    override suspend fun setUploadPauseOnLowBatteryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadPauseOnLowBattery] = enabled }
    }

    override suspend fun setUploadLowBatteryThresholdPercent(percent: Int) {
        require(percent in 5..50)
        dataStore.edit { it[Keys.uploadLowBatteryThreshold] = percent }
    }

    override suspend fun setRetentionDays(days: Int) {
        require(days in 1..3650)
        dataStore.edit { it[Keys.retentionDays] = days }
    }

    override suspend fun setMaxLocalStorageMb(mb: Int) {
        require(mb in 0..1_000_000)
        dataStore.edit { it[Keys.maxLocalStorageMb] = mb }
    }

    override suspend fun setLocalCompactionEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.localCompactionEnabled] = enabled }
    }

    override suspend fun setCaptureMinSpeedMps(mps: Float) {
        require(mps in 0f..50f)
        dataStore.edit {
            it[Keys.captureStartSpeedMps] = mps
            it[Keys.captureMinSpeedMpsLegacy] = mps
        }
    }

    override suspend fun setCaptureStartSpeedMps(mps: Float) {
        require(mps in 0f..50f)
        dataStore.edit {
            it[Keys.captureStartSpeedMps] = mps
            it[Keys.captureMinSpeedMpsLegacy] = mps
        }
    }

    override suspend fun setCaptureImmediateStartSpeedMps(mps: Float) {
        require(mps in 0f..80f)
        dataStore.edit { it[Keys.captureImmediateStartSpeedMps] = mps }
    }

    override suspend fun setCaptureStopSpeedMps(mps: Float) {
        require(mps in 0f..50f)
        dataStore.edit { it[Keys.captureStopSpeedMps] = mps }
    }

    override suspend fun setCaptureStopHoldSeconds(seconds: Int) {
        require(seconds in 10..3600)
        dataStore.edit { it[Keys.captureStopHoldSeconds] = seconds }
    }

    override suspend fun setCaptureStationaryRadiusMeters(meters: Float) {
        require(meters in 1f..500f)
        dataStore.edit { it[Keys.captureStationaryRadiusMeters] = meters }
    }

    override suspend fun setCaptureFastArmingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.captureFastArmingEnabled] = enabled }
    }

    override suspend fun setProcessingWindowSeconds(seconds: Float) {
        require(seconds in 0.1f..10f)
        dataStore.edit { it[Keys.processingWindowSeconds] = seconds }
    }

    override suspend fun setProcessingDistanceBinMeters(meters: Float) {
        require(meters in 1f..200f)
        dataStore.edit { it[Keys.processingDistanceBinMeters] = meters }
    }

    override suspend fun setProcessingLiveAfterSessionEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.processingLiveAfterSessionEnabled] = enabled }
    }

    override suspend fun setProcessingAllRunsOverlayEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.processingAllRunsOverlayEnabled] = enabled }
    }

    override suspend fun applyCapturePreset(preset: CaptureSettingsPreset) {
        dataStore.edit { prefs ->
            when (preset) {
                CaptureSettingsPreset.CONSERVATIVE -> {
                    prefs[Keys.captureFastArmingEnabled] = false
                    prefs[Keys.captureStartSpeedMps] = 3.5f
                    prefs[Keys.captureImmediateStartSpeedMps] = 5.5f
                    prefs[Keys.captureStopSpeedMps] = 0.8f
                    prefs[Keys.captureStopHoldSeconds] = 240
                    prefs[Keys.captureStationaryRadiusMeters] = 30f
                }
                CaptureSettingsPreset.NORMAL -> {
                    prefs[Keys.captureFastArmingEnabled] = true
                    prefs[Keys.captureStartSpeedMps] = 2.8f
                    prefs[Keys.captureImmediateStartSpeedMps] = 4.5f
                    prefs[Keys.captureStopSpeedMps] = 1.0f
                    prefs[Keys.captureStopHoldSeconds] = 180
                    prefs[Keys.captureStationaryRadiusMeters] = 25f
                }
                CaptureSettingsPreset.FAST_ARMING -> {
                    prefs[Keys.captureFastArmingEnabled] = true
                    prefs[Keys.captureStartSpeedMps] = 2.5f
                    prefs[Keys.captureImmediateStartSpeedMps] = 4.0f
                    prefs[Keys.captureStopSpeedMps] = 1.2f
                    prefs[Keys.captureStopHoldSeconds] = 120
                    prefs[Keys.captureStationaryRadiusMeters] = 20f
                }
            }
            val s = prefs[Keys.captureStartSpeedMps] ?: DEFAULT_CAPTURE_START_SPEED_MPS
            prefs[Keys.captureMinSpeedMpsLegacy] = s
        }
    }

    override suspend fun setDebugModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.debugModeEnabled] = enabled }
    }

    override suspend fun setCalibrationWorkflowEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.calibrationWorkflowEnabled] = enabled }
    }

    override suspend fun recordRecordingStartedAt(epochMs: Long) {
        require(epochMs > 0L)
        dataStore.edit { it[Keys.lastRecordingStartedAtEpochMs] = epochMs }
    }

    override suspend fun recordRecordingStoppedAt(epochMs: Long) {
        require(epochMs > 0L)
        dataStore.edit { it[Keys.lastRecordingStoppedAtEpochMs] = epochMs }
    }

    override suspend fun setUploadEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadEnabled] = enabled }
    }

    override suspend fun setUploadBaseUrl(url: String) {
        dataStore.edit { it[Keys.uploadBaseUrl] = url }
    }

    override suspend fun setUploadApiKey(key: String) {
        dataStore.edit { it[Keys.uploadApiKey] = key }
    }

    override suspend fun setUploadRetryLimit(limit: Int) {
        require(limit in 1..20)
        dataStore.edit { it[Keys.uploadRetryLimit] = limit }
    }

    override suspend fun setUploadAutoAfterSessionEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadAutoAfterSession] = enabled }
    }

    override suspend fun setUploadRoadFilterEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadRoadFilterEnabled] = enabled }
    }

    override suspend fun setUploadRoadFilterDistanceMeters(meters: Float) {
        require(meters in 5f..200f)
        dataStore.edit { it[Keys.uploadRoadFilterDistanceM] = meters }
    }

    override suspend fun setUploadRoadFilterUnknownPolicy(policy: UploadRoadFilterUnknownPolicy) {
        dataStore.edit { it[Keys.uploadRoadFilterUnknown] = policy.name }
    }

    override suspend fun setUploadRoadPackRequiredForAutoUpload(required: Boolean) {
        dataStore.edit { it[Keys.uploadRoadPackRequired] = required }
    }

    override suspend fun setUploadCouncilSlug(slug: String) {
        dataStore.edit { it[Keys.uploadCouncilSlug] = slug }
    }

    override suspend fun setUploadProjectSlug(slug: String) {
        dataStore.edit { it[Keys.uploadProjectSlug] = slug }
    }

    override suspend fun setUploadProjectId(id: String) {
        dataStore.edit { it[Keys.uploadProjectId] = id }
    }

    override suspend fun setUploadDeviceId(id: String) {
        dataStore.edit { it[Keys.uploadDeviceId] = id }
    }

    override suspend fun setUploadChargingPreferred(preferred: Boolean) {
        dataStore.edit { it[Keys.uploadChargingPreferred] = preferred }
    }

    private companion object {
        const val DEFAULT_CAPTURE_START_SPEED_MPS = 2.8f
        const val DEFAULT_CAPTURE_IMMEDIATE_START_SPEED_MPS = 4.5f
        const val DEFAULT_CAPTURE_STOP_SPEED_MPS = 1.0f
        const val DEFAULT_CAPTURE_STOP_HOLD_SECONDS = 180
        const val DEFAULT_CAPTURE_STATIONARY_RADIUS_M = 25f
        const val DEFAULT_CAPTURE_FAST_ARMING = true
        const val DEFAULT_PROCESSING_WINDOW_SECONDS = 1.0f
        const val DEFAULT_PROCESSING_DISTANCE_BIN_M = 10f
        const val DEFAULT_PROCESSING_LIVE_AFTER_SESSION = true
        const val DEFAULT_PROCESSING_ALL_RUNS_OVERLAY = true
        const val DEFAULT_UPLOAD_ROAD_FILTER_DISTANCE_M = 40f
    }
}

package org.openlgx.roads.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.settings.CaptureSettingsPreset
import org.openlgx.roads.data.local.settings.UploadRoadFilterUnknownPolicy
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        appSettingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue =
                AppSettings(
                    onboardingCompleted = false,
                    passiveCollectionUserEnabled = true,
                    passiveCollectionEffective = false,
                    uploadWifiOnly = true,
                    uploadAllowCellular = false,
                    uploadOnlyWhileCharging = false,
                    uploadPauseOnLowBatteryEnabled = true,
                    uploadLowBatteryThresholdPercent = 20,
                    retentionDays = 30,
                    maxLocalStorageMb = 0,
                    localCompactionEnabled = false,
                    captureStartSpeedMps = 2.8f,
                    captureImmediateStartSpeedMps = 4.5f,
                    captureStopSpeedMps = 1.0f,
                    captureStopHoldSeconds = 180,
                    captureStationaryRadiusMeters = 25f,
                    captureFastArmingEnabled = true,
                    processingWindowSeconds = 1.0f,
                    processingDistanceBinMeters = 10f,
                    processingLiveAfterSessionEnabled = true,
                    processingAllRunsOverlayEnabled = true,
                    debugModeEnabled = false,
                    calibrationWorkflowEnabled = false,
                    lastRecordingStartedAtEpochMs = null,
                    lastRecordingStoppedAtEpochMs = null,
                    uploadEnabled = false,
                    uploadBaseUrl = "",
                    uploadApiKey = "",
                    uploadRetryLimit = 3,
                    uploadAutoAfterSessionEnabled = false,
                    uploadRoadFilterEnabled = false,
                    uploadRoadFilterDistanceMeters = 40f,
                    uploadRoadFilterUnknownPolicy = UploadRoadFilterUnknownPolicy.UPLOAD,
                    uploadRoadPackRequiredForAutoUpload = true,
                    uploadCouncilSlug = "",
                    uploadProjectSlug = "",
                    uploadProjectId = "",
                    uploadDeviceId = "",
                    uploadChargingPreferred = false,
                ),
        )

    fun completeOnboarding() {
        viewModelScope.launch { appSettingsRepository.completeOnboarding() }
    }

    fun setPassiveCollectionEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setPassiveCollectionEnabled(enabled) }
    }

    fun setUploadWifiOnly(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadWifiOnly(enabled) }
    }

    fun setUploadAllowCellular(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadAllowCellular(enabled) }
    }

    fun setUploadOnlyWhileCharging(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadOnlyWhileCharging(enabled) }
    }

    fun setUploadPauseOnLowBattery(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadPauseOnLowBatteryEnabled(enabled) }
    }

    fun setLowBatteryThreshold(percent: Int) {
        viewModelScope.launch { appSettingsRepository.setUploadLowBatteryThresholdPercent(percent) }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch { appSettingsRepository.setRetentionDays(days) }
    }

    fun setMaxLocalStorageMb(mb: Int) {
        viewModelScope.launch { appSettingsRepository.setMaxLocalStorageMb(mb) }
    }

    fun setLocalCompactionEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setLocalCompactionEnabled(enabled) }
    }

    fun setCaptureStartSpeedMps(mps: Float) {
        viewModelScope.launch { appSettingsRepository.setCaptureStartSpeedMps(mps) }
    }

    fun applyCapturePreset(preset: CaptureSettingsPreset) {
        viewModelScope.launch { appSettingsRepository.applyCapturePreset(preset) }
    }

    fun setCaptureFastArmingEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setCaptureFastArmingEnabled(enabled) }
    }

    fun setCaptureStopHoldSeconds(seconds: Int) {
        viewModelScope.launch { appSettingsRepository.setCaptureStopHoldSeconds(seconds) }
    }

    fun setProcessingWindowSeconds(seconds: Float) {
        viewModelScope.launch { appSettingsRepository.setProcessingWindowSeconds(seconds) }
    }

    fun setProcessingDistanceBinMeters(meters: Float) {
        viewModelScope.launch { appSettingsRepository.setProcessingDistanceBinMeters(meters) }
    }

    fun setProcessingLiveAfterSessionEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setProcessingLiveAfterSessionEnabled(enabled) }
    }

    fun setProcessingAllRunsOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setProcessingAllRunsOverlayEnabled(enabled) }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setDebugModeEnabled(enabled) }
    }

    fun setCalibrationWorkflowEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setCalibrationWorkflowEnabled(enabled) }
    }

    fun setUploadEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadEnabled(enabled) }
    }

    fun setUploadAutoAfterSessionEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadAutoAfterSessionEnabled(enabled) }
    }

    fun setUploadChargingPreferred(preferred: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadChargingPreferred(preferred) }
    }

    fun setUploadRoadPackRequired(required: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadRoadPackRequiredForAutoUpload(required) }
    }

    fun setUploadRoadFilterEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadRoadFilterEnabled(enabled) }
    }
}

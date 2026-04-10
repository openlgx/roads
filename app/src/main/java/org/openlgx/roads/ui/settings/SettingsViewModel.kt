package org.openlgx.roads.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.settings.CaptureSettingsPreset
import org.openlgx.roads.data.local.settings.UploadRoadFilterUnknownPolicy
import org.openlgx.roads.roadpack.RoadPackManager
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val roadsDatabase: RoadsDatabase,
    private val roadPackManager: RoadPackManager,
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
                    uploadChargingPreferred = true,
                    hostedUploadLastAttemptAtEpochMs = null,
                    hostedUploadLastSuccessAtEpochMs = null,
                    hostedUploadLastError = null,
                    latestHostedUploadAttemptLocalSessionId = null,
                    pilotBootstrapApplied = false,
                    pilotBootstrapLabel = null,
                ),
        )

    private val _hostedDiagnostics =
        MutableStateFlow(
            HostedUploadDiagnosticsUi(
                uploadEnabled = false,
                uploadBaseUrlHost = "",
                uploadApiKeyConfigured = false,
                uploadCouncilSlug = "",
                uploadProjectSlug = "",
                projectIdConfigured = false,
                deviceIdConfigured = false,
                roadPackPresent = false,
                roadPackVersionLabel = null,
                roadPackFeatureCount = 0,
                roadPackLoadNote = null,
                uploadQueuePendingOrRetryable = 0L,
                uploadLastAttemptAtEpochMs = null,
                uploadLastSuccessAtEpochMs = null,
                uploadLastError = null,
                wifiOnly = true,
                cellularAllowed = false,
                chargingRequiredHard = false,
                chargingPreferredSoft = true,
                uploadRetryLimit = 3,
                uploadAutoAfterSession = false,
                roadFilterEnabled = false,
                roadPackRequiredForAutoUpload = true,
                pilotBootstrapApplied = false,
                pilotBootstrapLabel = null,
                roadPackExpectedPath = "",
                uploadReadinessNotes = emptyList(),
            ),
        )
    val hostedDiagnostics: StateFlow<HostedUploadDiagnosticsUi> = _hostedDiagnostics.asStateFlow()

    init {
        viewModelScope.launch {
            appSettingsRepository.settings.collectLatest { s ->
                roadPackManager.invalidateCache()
                val diag = roadPackManager.getDiagnostics(s.uploadCouncilSlug)
                val pending = roadsDatabase.uploadBatchDao().countPendingOrRetryable()
                val slug = s.uploadCouncilSlug.trim()
                val readiness =
                    buildList {
                        if (slug.isNotEmpty() && !roadPackManager.hasPackForCouncil(slug)) {
                            add(
                                "Road pack missing for \"$slug\". Install: ${roadPackManager.expectedPublicRoadsPathHint(slug)}",
                            )
                        }
                        if (!s.uploadEnabled) {
                            add("Hosted upload is off — local capture and review still work.")
                        } else {
                            if (s.uploadApiKey.isBlank()) {
                                add("Upload on but no API key — no uploads until key is set or injected for debug.")
                            }
                            if (s.uploadBaseUrl.isBlank()) {
                                add("Upload base URL is blank.")
                            }
                            if (s.uploadProjectId.isBlank() || s.uploadDeviceId.isBlank()) {
                                add("Project id and/or device id is blank.")
                            }
                        }
                        if (isEmpty()) {
                            add("No upload blockers from settings (network/charging rules still apply).")
                        }
                    }
                _hostedDiagnostics.value =
                    HostedUploadDiagnosticsUi(
                        uploadEnabled = s.uploadEnabled,
                        uploadBaseUrlHost = uploadBaseUrlSummary(s.uploadBaseUrl),
                        uploadApiKeyConfigured = s.uploadApiKey.isNotBlank(),
                        uploadCouncilSlug = s.uploadCouncilSlug,
                        uploadProjectSlug = s.uploadProjectSlug,
                        projectIdConfigured = s.uploadProjectId.isNotBlank(),
                        deviceIdConfigured = s.uploadDeviceId.isNotBlank(),
                        roadPackPresent = roadPackManager.hasPackForCouncil(s.uploadCouncilSlug),
                        roadPackVersionLabel = diag.packVersion,
                        roadPackFeatureCount = diag.featureCount,
                        roadPackLoadNote = diag.loadError,
                        uploadQueuePendingOrRetryable = pending,
                        uploadLastAttemptAtEpochMs = s.hostedUploadLastAttemptAtEpochMs,
                        uploadLastSuccessAtEpochMs = s.hostedUploadLastSuccessAtEpochMs,
                        uploadLastError = s.hostedUploadLastError,
                        wifiOnly = s.uploadWifiOnly,
                        cellularAllowed = s.uploadAllowCellular,
                        chargingRequiredHard = s.uploadOnlyWhileCharging,
                        chargingPreferredSoft = s.uploadChargingPreferred,
                        uploadRetryLimit = s.uploadRetryLimit,
                        uploadAutoAfterSession = s.uploadAutoAfterSessionEnabled,
                        roadFilterEnabled = s.uploadRoadFilterEnabled,
                        roadPackRequiredForAutoUpload = s.uploadRoadPackRequiredForAutoUpload,
                        pilotBootstrapApplied = s.pilotBootstrapApplied,
                        pilotBootstrapLabel = s.pilotBootstrapLabel,
                        roadPackExpectedPath = roadPackManager.expectedPublicRoadsPathHint(s.uploadCouncilSlug),
                        uploadReadinessNotes = readiness,
                    )
            }
        }
    }

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

    /**
     * Recommended pilot upload posture (does not enable upload or set secrets — configure URL/API key
     * and Neon ids separately).
     */
    fun applyPilotUploadDefaults() {
        viewModelScope.launch {
            appSettingsRepository.setUploadWifiOnly(true)
            appSettingsRepository.setUploadAllowCellular(false)
            appSettingsRepository.setUploadChargingPreferred(true)
            appSettingsRepository.setUploadRetryLimit(3)
            appSettingsRepository.setUploadAutoAfterSessionEnabled(true)
            appSettingsRepository.setUploadRoadFilterEnabled(true)
            appSettingsRepository.setUploadRoadPackRequiredForAutoUpload(true)
        }
    }

    /**
     * Persists hosted alpha connection fields for pilot installs. [apiKeyIfProvided] is only written
     * when non-blank so operators can change other fields without re-pasting the key.
     */
    fun applyHostedPilotConnection(
        uploadBaseUrl: String,
        apiKeyIfProvided: String,
        councilSlug: String,
        projectSlug: String,
        projectId: String,
        deviceId: String,
    ) {
        viewModelScope.launch {
            appSettingsRepository.setUploadBaseUrl(uploadBaseUrl.trim())
            if (apiKeyIfProvided.isNotBlank()) {
                appSettingsRepository.setUploadApiKey(apiKeyIfProvided.trim())
            }
            appSettingsRepository.setUploadCouncilSlug(councilSlug.trim())
            appSettingsRepository.setUploadProjectSlug(projectSlug.trim())
            appSettingsRepository.setUploadProjectId(projectId.trim())
            appSettingsRepository.setUploadDeviceId(deviceId.trim())
        }
    }

    private companion object {
        fun uploadBaseUrlSummary(url: String): String {
            if (url.isBlank()) return "(not set)"
            return try {
                URI(url).host ?: url.take(64)
            } catch (_: Exception) {
                url.take(64)
            }
        }
    }
}

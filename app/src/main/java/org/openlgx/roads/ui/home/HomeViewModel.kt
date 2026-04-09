package org.openlgx.roads.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.openlgx.roads.collector.PassiveCollectionHandle
import org.openlgx.roads.collector.PassiveCollectionUiModel
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.repo.RecordingSessionRepository
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.location.LocationRecordingUiState
import org.openlgx.roads.sensor.SensorRecordingController
import org.openlgx.roads.sensor.SensorRecordingUiState

data class HomeUiState(
    val passiveCollectionEffective: Boolean = false,
    val passiveCollectionUserEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val uploadWifiOnly: Boolean = true,
    val uploadAllowCellular: Boolean = false,
    val debugModeEnabled: Boolean = false,
    val pendingUploadSummaryPlaceholder: String = "—",
    val collector: PassiveCollectionUiModel = PassiveCollectionUiModel(),
    val locationRecording: LocationRecordingUiState = LocationRecordingUiState(),
    val sensorRecording: SensorRecordingUiState = SensorRecordingUiState(),
    val recordingSessionCount: Long = 0L,
)

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val passiveCollection: PassiveCollectionHandle,
    locationRecordingController: LocationRecordingController,
    sensorRecordingController: SensorRecordingController,
    recordingSessionRepository: RecordingSessionRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(
            appSettingsRepository.settings,
            passiveCollection.uiState,
            locationRecordingController.uiState,
            sensorRecordingController.uiState,
            recordingSessionRepository.observeRecordingSessionCount(),
        ) { settings, collector, location, sensor, sessionCount ->
            HomeUiState(
                passiveCollectionEffective = settings.passiveCollectionEffective,
                passiveCollectionUserEnabled = settings.passiveCollectionUserEnabled,
                onboardingCompleted = settings.onboardingCompleted,
                uploadWifiOnly = settings.uploadWifiOnly,
                uploadAllowCellular = settings.uploadAllowCellular,
                debugModeEnabled = settings.debugModeEnabled,
                pendingUploadSummaryPlaceholder = "See Diagnostics for queue (Phase 1)",
                collector = collector,
                locationRecording = location,
                sensorRecording = sensor,
                recordingSessionCount = sessionCount,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = HomeUiState(),
        )

    /**
     * Field-testing path: ensures the user toggle is on, marks onboarding complete, and persists via DataStore.
     * Coordinator picks up [AppSettings.passiveCollectionEffective] on the next reconcile.
     */
    fun activatePassiveCollectionAndCompleteOnboarding() {
        viewModelScope.launch {
            appSettingsRepository.setPassiveCollectionEnabled(true)
            appSettingsRepository.completeOnboarding()
        }
    }

    /** Marks onboarding complete without forcing the passive toggle (for debug parity with Settings). */
    fun completeOnboardingOnly() {
        viewModelScope.launch {
            appSettingsRepository.completeOnboarding()
        }
    }

    fun resetOnboardingForDebug() {
        viewModelScope.launch {
            appSettingsRepository.setOnboardingCompleted(false)
        }
    }

    fun debugSetSimulatedDriving(active: Boolean?) {
        passiveCollection.debugSetSimulateDriving(active)
    }

    fun debugForceStartRecording() {
        passiveCollection.debugForceStartRecording()
    }

    fun debugResetCollector() {
        passiveCollection.debugResetStateMachine()
    }
}

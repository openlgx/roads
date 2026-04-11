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
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.permission.BatteryOptimizationChecker
import org.openlgx.roads.data.repo.RecordingSessionRepository
import org.openlgx.roads.data.repo.session.SessionInspectionRepository
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.processing.ondevice.SessionProcessingScheduler
import org.openlgx.roads.location.LocationRecordingUiState
import org.openlgx.roads.sensor.SensorRecordingController
import org.openlgx.roads.sensor.SensorRecordingUiState

private data class HomePartialState(
    val settings: AppSettings,
    val collector: PassiveCollectionUiModel,
    val location: LocationRecordingUiState,
    val sensor: SensorRecordingUiState,
    val sessionCount: Long,
)

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
    /** Most recent session row (by start time), for Home processing summary. */
    val latestSessionSummary: SessionListStats? = null,
    val lastRecordingStartedAtEpochMs: Long? = null,
    val lastRecordingStoppedAtEpochMs: Long? = null,
    /** True when battery optimization is disabled for this app (recommended for passive detection). */
    val batteryOptimizationExempt: Boolean = true,
)

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val batteryOptimizationChecker: BatteryOptimizationChecker,
    private val passiveCollection: PassiveCollectionHandle,
    locationRecordingController: LocationRecordingController,
    sensorRecordingController: SensorRecordingController,
    recordingSessionRepository: RecordingSessionRepository,
    sessionInspectionRepository: SessionInspectionRepository,
    sessionProcessingScheduler: SessionProcessingScheduler,
) : ViewModel() {

    init {
        // Same backfill entry point as [SessionsListViewModel]: pending analysis can complete
        // without forcing navigation away from Home.
        sessionProcessingScheduler.requestBackfillPendingProcessing()
    }

    val uiState: StateFlow<HomeUiState> =
        combine(
            combine(
                appSettingsRepository.settings,
                passiveCollection.uiState,
                locationRecordingController.uiState,
                sensorRecordingController.uiState,
                recordingSessionRepository.observeRecordingSessionCount(),
            ) { settings, collector, location, sensor, sessionCount ->
                HomePartialState(settings, collector, location, sensor, sessionCount)
            },
            sessionInspectionRepository.observeSessionList(),
        ) { partial, sessionRows ->
            val settings = partial.settings
            HomeUiState(
                passiveCollectionEffective = settings.passiveCollectionEffective,
                passiveCollectionUserEnabled = settings.passiveCollectionUserEnabled,
                onboardingCompleted = settings.onboardingCompleted,
                uploadWifiOnly = settings.uploadWifiOnly,
                uploadAllowCellular = settings.uploadAllowCellular,
                debugModeEnabled = settings.debugModeEnabled,
                pendingUploadSummaryPlaceholder = "See Diagnostics for queue (Phase 1)",
                collector = partial.collector,
                locationRecording = partial.location,
                sensorRecording = partial.sensor,
                recordingSessionCount = partial.sessionCount,
                latestSessionSummary = sessionRows.firstOrNull(),
                lastRecordingStartedAtEpochMs = settings.lastRecordingStartedAtEpochMs,
                lastRecordingStoppedAtEpochMs = settings.lastRecordingStoppedAtEpochMs,
                batteryOptimizationExempt = batteryOptimizationChecker.isExempt(),
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
